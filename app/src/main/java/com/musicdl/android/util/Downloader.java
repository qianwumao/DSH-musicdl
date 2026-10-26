package com.musicdl.android.util;

import android.content.Context;

import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;
import com.musicdl.android.source.MusicSource;
import com.musicdl.android.source.Sources;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 顺序下载队列：解析地址 -> 下载音频 -> 写入歌词。 */
public class Downloader {

    public interface Callback {
        void onStatus(int index, String status);

        void onProgress(int index, int percent);

        void onResult(int index, boolean ok, boolean skipped, String message);

        void onFinished(boolean cancelled, int success, int skipped, int failed);
    }

    private final Context context;
    private final List<Song> songs;
    private final boolean skipExisting;
    private final Callback callback;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public Downloader(Context context, List<Song> songs, boolean skipExisting, Callback callback) {
        this.context = context.getApplicationContext();
        this.songs = songs;
        this.skipExisting = skipExisting;
        this.callback = callback;
    }

    public void start() {
        executor.execute(this::run);
    }

    public void cancel() {
        cancelled.set(true);
        executor.shutdownNow();
    }

    private void run() {
        Set<String> existing = skipExisting
                ? Storage.existingNames(context)
                : new HashSet<>();
        int success = 0;
        int skipped = 0;
        int failed = 0;
        int total = songs.size();

        for (int index = 0; index < total; index++) {
            if (cancelled.get()) {
                break;
            }
            Song song = songs.get(index);
            if (skipExisting && existing.contains(Storage.normalize(song.name))) {
                skipped++;
                callback.onResult(index, true, true, "已存在于保存目录，跳过");
                continue;
            }
            callback.onStatus(index, "正在解析下载地址…");
            String error = downloadOne(index, song);
            if (error == null) {
                success++;
                callback.onResult(index, true, false, "已保存");
            } else if ("已取消".equals(error)) {
                break;
            } else {
                failed++;
                callback.onResult(index, false, false, error);
            }
        }
        callback.onFinished(cancelled.get(), success, skipped, failed);
        executor.shutdown();
    }

    /** 返回 null 表示成功，否则返回错误信息。 */
    private String downloadOne(int index, Song song) {
        MusicSource source = Sources.get(song.source);
        if (source == null) {
            return "未知音乐源";
        }
        List<MusicSource.Candidate> candidates;
        try {
            candidates = source.resolve(song);
        } catch (Exception e) {
            return "解析失败：" + shortMessage(e);
        }
        if (candidates == null || candidates.isEmpty()) {
            return "没有可用的下载地址";
        }

        for (MusicSource.Candidate candidate : candidates) {
            if (cancelled.get()) {
                return "已取消";
            }
            try {
                callback.onStatus(index, "正在连接…");
                HttpURLConnection conn = Http.open(candidate.url, Http.headers(
                        "Referer", refererFor(song.source)));
                int code = conn.getResponseCode();
                if (code >= 400) {
                    conn.disconnect();
                    continue;
                }
                String contentType = conn.getContentType();
                long length = conn.getContentLengthLong();
                if (contentType != null) {
                    String lower = contentType.toLowerCase(Locale.ROOT);
                    if (lower.contains("text/html") || lower.contains("application/json")
                            || lower.contains("text/plain")) {
                        conn.disconnect();
                        continue;
                    }
                }
                if (length > 0 && length < 20 * 1024) {
                    conn.disconnect();
                    continue;
                }

                String ext = resolveExt(candidate.ext, contentType, candidate.url);
                String baseName = FormatUtils.sanitizeFileName(song.fileNameBase());
                String audioName = baseName + "." + ext;
                String folder = folderFor(song);
                callback.onStatus(index, "正在下载…");

                try (InputStream in = Http.stream(conn)) {
                    Storage.saveAudio(context, folder, audioName, ext, in, length,
                            percent -> {
                                if (cancelled.get()) {
                                    return false;
                                }
                                callback.onProgress(index, percent);
                                return true;
                            });
                } finally {
                    conn.disconnect();
                }

                saveLyric(song, folder, baseName);
                saveCover(song, folder, baseName);
                return null;
            } catch (Exception e) {
                if (cancelled.get() || (e.getMessage() != null
                        && e.getMessage().contains("已取消"))) {
                    return "已取消";
                }
                android.util.Log.e("MusicDL", "download candidate failed: " + candidate.url, e);
                // 尝试下一个候选地址
            }
        }
        return "下载失败，请稍后重试";
    }

    /** 保存子目录名：优先使用歌手名，缺失时归入“未知歌手”。 */
    private static String folderFor(Song song) {
        String singers = song.singers == null ? "" : song.singers.trim();
        if (singers.isEmpty()) {
            return "未知歌手";
        }
        String folder = FormatUtils.sanitizeFileName(singers);
        return folder.isEmpty() || "music".equals(folder) ? "未知歌手" : folder;
    }

    private void saveLyric(Song song, String folder, String baseName) {
        try {
            MusicSource source = Sources.get(song.source);
            if (source == null) {
                return;
            }
            String lyric = source.lyric(song);
            if (lyric == null || lyric.trim().isEmpty()) {
                return;
            }
            Storage.saveLyric(context, folder, baseName + ".lrc", lyric);
        } catch (Exception e) {
            android.util.Log.e("MusicDL", "lyric failed for " + song.name, e);
        }
    }

    private void saveCover(Song song, String folder, String baseName) {
        if (song.cover == null || song.cover.isEmpty()) {
            return;
        }
        try {
            byte[] data = Http.getBytes(song.cover,
                    Http.headers("Referer", refererFor(song.source)));
            if (data == null || data.length == 0) {
                return;
            }
            String ext = Http.extOfUrl(song.cover);
            if (!"png".equals(ext) && !"webp".equals(ext)
                    && !"jpeg".equals(ext) && !"jpg".equals(ext)) {
                ext = "jpg";
            }
            String mime;
            if ("png".equals(ext)) {
                mime = "image/png";
            } else if ("webp".equals(ext)) {
                mime = "image/webp";
            } else {
                mime = "image/jpeg";
            }
            String name = FormatUtils.sanitizeFileName(baseName) + "." + ext;
            Storage.saveImage(context, folder, name, mime, data);
        } catch (Exception e) {
            android.util.Log.e("MusicDL", "cover failed for " + song.name, e);
        }
    }

    private static String resolveExt(String candidateExt, String contentType, String url) {
        Set<String> known = new HashSet<>();
        known.add("mp3");
        known.add("flac");
        known.add("wav");
        known.add("m4a");
        known.add("aac");
        known.add("ogg");
        known.add("ape");
        known.add("wma");
        if (candidateExt != null && known.contains(candidateExt.toLowerCase(Locale.ROOT))) {
            return candidateExt.toLowerCase(Locale.ROOT);
        }
        String urlExt = Http.extOfUrl(url);
        if (known.contains(urlExt)) {
            return urlExt;
        }
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.contains("flac")) {
                return "flac";
            }
            if (lower.contains("mpeg")) {
                return "mp3";
            }
            if (lower.contains("mp4") || lower.contains("m4a")) {
                return "m4a";
            }
            if (lower.contains("ogg")) {
                return "ogg";
            }
            if (lower.contains("wav")) {
                return "wav";
            }
        }
        return "mp3";
    }

    private static String refererFor(String source) {
        switch (source) {
            case "kugou":
                return "https://www.kugou.com/";
            case "netease":
                return "https://music.163.com/";
            case "qq":
                return "https://y.qq.com/";
            default:
                return "http://www.kuwo.cn/";
        }
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 40 ? message.substring(0, 40) : message;
    }
}
