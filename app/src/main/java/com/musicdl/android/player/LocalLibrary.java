package com.musicdl.android.player;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import com.musicdl.android.util.Storage;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 扫描保存目录中的音频与同名 LRC 歌词。 */
public final class LocalLibrary {

    private static final String DOWNLOAD_DIR =
            Environment.DIRECTORY_DOWNLOADS + "/" + Storage.FOLDER + "/";
    private static final String MUSIC_DIR =
            Environment.DIRECTORY_MUSIC + "/" + Storage.FOLDER + "/";
    private static final Set<String> AUDIO_EXTS = new HashSet<>();

    static {
        AUDIO_EXTS.add("mp3");
        AUDIO_EXTS.add("flac");
        AUDIO_EXTS.add("wav");
        AUDIO_EXTS.add("m4a");
        AUDIO_EXTS.add("aac");
        AUDIO_EXTS.add("ogg");
        AUDIO_EXTS.add("oga");
        AUDIO_EXTS.add("ape");
        AUDIO_EXTS.add("wma");
        AUDIO_EXTS.add("opus");
        AUDIO_EXTS.add("aiff");
        AUDIO_EXTS.add("aif");
    }

    private LocalLibrary() {
    }

    public static List<LocalTrack> scan(Context context) {
        Uri tree = Storage.getCustomTree(context);
        if (tree != null) {
            try {
                return scanTree(context, tree);
            } catch (Exception ignored) {
                return new ArrayList<>();
            }
        }
        List<LocalTrack> tracks = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                tracks = scanMediaStore(context);
            } catch (Exception ignored) {
                // 继续尝试直接读取旧目录
            }
        }
        if (tracks.isEmpty()) {
            try {
                tracks = scanLegacy(context);
            } catch (Exception ignored) {
                // 忽略读取失败
            }
        }
        return tracks;
    }

    private static List<LocalTrack> scanTree(Context context, Uri tree) throws Exception {
        List<LocalTrack> tracks = new ArrayList<>();
        String parentId = DocumentsContract.getTreeDocumentId(tree);
        Map<String, Uri> lyrics = new HashMap<>();
        Map<String, Uri> covers = new HashMap<>();
        List<Entry> audios = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        collectTree(resolver, tree, parentId, "", lyrics, covers, audios, 0);
        Set<String> seen = new HashSet<>();
        for (Entry entry : audios) {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId);
            if (!seen.add(uri.toString())) {
                continue;
            }
            LocalTrack track = new LocalTrack(uri,
                    lyrics.get(entry.path + baseName(entry.name)), entry.name);
            track.coverUri = covers.get(entry.path + baseName(entry.name));
            track.addedAt = entry.lastModified;
            fillMetadata(context, track, entry.name);
            tracks.add(track);
        }
        sortTracks(tracks);
        return tracks;
    }

    /** 递归读取所选目录，兼容按歌手名分出的子文件夹。 */
    private static void collectTree(ContentResolver resolver, Uri tree, String parentId,
                                    String path, Map<String, Uri> lyrics, Map<String, Uri> covers,
                                    List<Entry> audios, int depth) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        List<String[]> subDirs = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long modified = cursor.isNull(3) ? 0 : cursor.getLong(3);
                if (docId == null || name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 3) {
                        subDirs.add(new String[]{docId, name});
                    }
                } else if (isLyric(name, mime)) {
                    lyrics.put(path + baseName(name),
                            DocumentsContract.buildDocumentUriUsingTree(tree, docId));
                } else if (isImage(name, mime)) {
                    covers.put(path + baseName(name),
                            DocumentsContract.buildDocumentUriUsingTree(tree, docId));
                } else if (isAudio(name, mime)) {
                    audios.add(new Entry(name, docId, path, modified));
                }
            }
        }
        // 先收集子目录再递归查询，避免在同一个 ContentResolver 上嵌套 Cursor。
        for (String[] subDir : subDirs) {
            collectTree(resolver, tree, subDir[0], path + subDir[1] + "/",
                    lyrics, covers, audios, depth + 1);
        }
    }

    private static List<LocalTrack> scanMediaStore(Context context) throws Exception {
        List<LocalTrack> tracks = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Map<String, Uri> lyrics = new HashMap<>();
        Map<String, Uri> covers = new HashMap<>();
        Uri files = MediaStore.Files.getContentUri("external");
        String pathSelection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String lrcSelection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND ("
                + pathSelection + ")";
        try (Cursor cursor = resolver.query(files, new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH},
                lrcSelection, new String[]{"%.lrc", DOWNLOAD_DIR + "%", MUSIC_DIR + "%"},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String name = cursor.getString(1);
                    String relative = cursor.getString(2);
                    if (name == null || relative == null) {
                        continue;
                    }
                    lyrics.put(relative + baseName(name),
                            ContentUris.withAppendedId(files, id));
                }
            }
        }

        String coverSelection = MediaStore.MediaColumns.MIME_TYPE + " LIKE ? AND ("
                + pathSelection + ")";
        try (Cursor cursor = resolver.query(files, new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH},
                coverSelection, new String[]{"image/%", DOWNLOAD_DIR + "%", MUSIC_DIR + "%"},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String name = cursor.getString(1);
                    String relative = cursor.getString(2);
                    if (name == null || relative == null) {
                        continue;
                    }
                    covers.put(relative + baseName(name),
                            ContentUris.withAppendedId(files, id));
                }
            }
        }

        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_ADDED
        };
        try (Cursor cursor = resolver.query(collection, projection,
                pathSelection, new String[]{DOWNLOAD_DIR + "%", MUSIC_DIR + "%"}, null)) {
            if (cursor == null) {
                return tracks;
            }
            Set<String> seen = new HashSet<>();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                if (name == null) {
                    continue;
                }
                String relative = cursor.getString(6);
                Uri uri = ContentUris.withAppendedId(collection, id);
                if (!seen.add(uri.toString())) {
                    continue;
                }
                LocalTrack track = new LocalTrack(uri,
                        relative == null ? null : lyrics.get(relative + baseName(name)), name);
                track.coverUri = relative == null ? null : covers.get(relative + baseName(name));
                track.title = nonEmpty(cursor.getString(2), fallbackTitle(name));
                track.artist = nonEmpty(cursor.getString(3), fallbackArtist(name));
                track.album = nonEmpty(cursor.getString(4), "");
                track.durationMs = cursor.getLong(5);
                track.addedAt = cursor.isNull(7) ? 0 : cursor.getLong(7) * 1000L;
                tracks.add(track);
            }
        }
        sortTracks(tracks);
        return tracks;
    }

    private static List<LocalTrack> scanLegacy(Context context) {
        List<LocalTrack> result = new ArrayList<>();
        File[] dirs = {
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), Storage.FOLDER),
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), Storage.FOLDER)
        };
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            scanLegacyDir(context, dir, result, seen, 0);
        }
        sortTracks(result);
        return result;
    }

    /** 递归读取旧版目录，兼容按歌手名分出的子文件夹。 */
    private static void scanLegacyDir(Context context, File dir, List<LocalTrack> result,
                                      Set<String> seen, int depth) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Map<String, File> lyrics = new HashMap<>();
        Map<String, File> covers = new HashMap<>();
        List<File> subDirs = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory()) {
                if (depth < 2) {
                    subDirs.add(file);
                }
                continue;
            }
            if (isLyric(file.getName(), null)) {
                lyrics.put(baseName(file.getName()), file);
            } else if (isImage(file.getName(), null)) {
                covers.put(baseName(file.getName()), file);
            }
        }
        for (File file : files) {
            if (!file.isFile() || !isAudio(file.getName(), null)) {
                continue;
            }
            Uri uri = Uri.fromFile(file);
            if (!seen.add(uri.toString())) {
                continue;
            }
            File lrc = lyrics.get(baseName(file.getName()));
            LocalTrack track = new LocalTrack(uri,
                    lrc == null ? null : Uri.fromFile(lrc), file.getName());
            File cover = covers.get(baseName(file.getName()));
            track.coverUri = cover == null ? null : Uri.fromFile(cover);
            track.addedAt = file.lastModified();
            fillMetadata(context, track, file.getName());
            result.add(track);
        }
        for (File sub : subDirs) {
            scanLegacyDir(context, sub, result, seen, depth + 1);
        }
    }

    private static void fillMetadata(Context context, LocalTrack track, String fileName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if ("file".equals(track.uri.getScheme())) {
                retriever.setDataSource(track.uri.getPath());
            } else {
                retriever.setDataSource(context, track.uri);
            }
            track.title = nonEmpty(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_TITLE), fallbackTitle(fileName));
            track.artist = nonEmpty(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST), fallbackArtist(fileName));
            track.album = nonEmpty(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM), "");
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            track.durationMs = parseLong(duration);
        } catch (Exception e) {
            track.title = nonEmpty(track.title, fallbackTitle(fileName));
            track.artist = nonEmpty(track.artist, fallbackArtist(fileName));
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void sortTracks(List<LocalTrack> tracks) {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        tracks.sort((a, b) -> collator.compare(
                a.title == null ? "" : a.title,
                b.title == null ? "" : b.title));
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return true;
        }
        return AUDIO_EXTS.contains(ext(name));
    }

    private static boolean isLyric(String name, String mime) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".lrc")
                || (mime != null && mime.toLowerCase(Locale.ROOT).contains("lrc"));
    }

    private static boolean isImage(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        String ext = ext(name);
        return ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("png") || ext.equals("webp");
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String fallbackTitle(String fileName) {
        String base = stripExtension(fileName);
        int dash = base.indexOf('-');
        return dash > 0 ? base.substring(0, dash) : base;
    }

    private static String fallbackArtist(String fileName) {
        String base = stripExtension(fileName);
        int first = base.indexOf('-');
        if (first < 0) {
            return "";
        }
        int second = base.indexOf('-', first + 1);
        return second > first ? base.substring(first + 1, second) : base.substring(first + 1);
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String ext(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static final class Entry {
        final String name;
        final String docId;
        final String path;
        final long lastModified;

        Entry(String name, String docId, String path, long lastModified) {
            this.name = name;
            this.docId = docId;
            this.path = path;
            this.lastModified = lastModified;
        }
    }
}
