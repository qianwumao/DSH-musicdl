package com.musicdl.android.source;

import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;
import com.musicdl.android.util.FormatUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 酷狗音乐：官方搜索 + 第三方解析下载地址。 */
public class KugouSource implements MusicSource {

    @Override
    public String id() {
        return "kugou";
    }

    @Override
    public String name() {
        return "酷狗音乐";
    }

    @Override
    public List<Song> search(String keyword, int limit) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("format", "json");
        params.put("keyword", keyword);
        params.put("platform", "WebFilter");
        params.put("page", "1");
        params.put("pagesize", String.valueOf(Math.max(1, Math.min(limit, 50))));

        String body = Http.get("https://songsearch.kugou.com/song_search_v2?"
                + Http.buildQuery(params), Http.headers("Referer", "https://www.kugou.com/"));
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("lists");
        List<Song> songs = new ArrayList<>();
        if (list == null) {
            android.util.Log.w("MusicDL", "kugou response: " + body.substring(0,
                    Math.min(200, body.length())));
            return songs;
        }

        for (int i = 0; i < list.length() && songs.size() < limit; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String fileHash = item.optString("FileHash", "");
            String sqHash = item.optString("SQFileHash", "");
            String hqHash = item.optString("HQFileHash", "");
            if (fileHash.isEmpty() && sqHash.isEmpty() && hqHash.isEmpty()) {
                continue;
            }

            Song song = new Song();
            song.source = id();
            song.sourceName = name();
            // 与桌面端一致：文件名标识使用标准 FileHash，高音质哈希单独保存用于解析
            song.id = !fileHash.isEmpty() ? fileHash : (!sqHash.isEmpty() ? sqHash : hqHash);
            song.extra = !sqHash.isEmpty() ? sqHash : hqHash;
            song.name = firstNonEmpty(item.optString("SongName"), item.optString("OriSongName"));
            song.singers = item.optString("SingerName", "");
            song.album = item.optString("AlbumName", "");
            song.duration = item.optInt("Duration", item.optInt("SQDuration", 0));
            song.cover = coverOf(item);
            song.vip = item.optInt("PayType", 0) == 3;

            if (!sqHash.isEmpty()) {
                song.quality = "FLAC";
                song.sizeText = FormatUtils.sizeOfBytes(item.optLong("SQFileSize", 0));
            } else if (!hqHash.isEmpty()) {
                song.quality = "MP3 320K";
                song.sizeText = FormatUtils.sizeOfBytes(item.optLong("HQFileSize", 0));
            } else {
                song.quality = "MP3";
                song.sizeText = FormatUtils.sizeOfBytes(item.optLong("FileSize", 0));
            }

            if (song.name.isEmpty()) {
                continue;
            }
            songs.add(song);
        }
        return songs;
    }

    private static String coverOf(JSONObject item) {
        String cover = "";
        JSONObject trans = item.optJSONObject("trans_param");
        if (trans != null) {
            cover = trans.optString("union_cover", "");
        }
        if (cover.isEmpty()) {
            cover = item.optString("Image", "");
        }
        cover = cover.replace("{size}", "400");
        return cover.startsWith("http") ? cover : "";
    }

    @Override
    public List<Candidate> resolve(Song song) throws Exception {
        List<Candidate> candidates = MusicSource.candidates();
        List<String> hashes = new ArrayList<>();
        // 先尝试高音质哈希，再回退到标准哈希
        if (!song.extra.isEmpty()) {
            hashes.add(song.extra);
        }
        if (!song.id.isEmpty() && !hashes.contains(song.id)) {
            hashes.add(song.id);
        }
        for (String hash : hashes) {
            String url = ThirdParty.lzmhhh("kg", hash);
            if (!url.isEmpty()) {
                String ext = Http.extOfUrl(url);
                candidates.add(new Candidate(url, ext.isEmpty() ? "mp3" : ext));
            }
        }
        return candidates;
    }

    @Override
    public String lyric(Song song) throws Exception {
        String hash = song.id;
        Map<String, String> params = new HashMap<>();
        params.put("keyword", song.singers + " - " + song.name);
        params.put("duration", String.valueOf(song.duration));
        params.put("hash", hash);
        params.put("ver", "1");
        params.put("man", "yes");
        params.put("client", "mobi");
        String body = Http.get("https://krcs.kugou.com/search?" + Http.buildQuery(params),
                Http.headers("Referer", "https://www.kugou.com/"));
        JSONObject root = new JSONObject(body);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return null;
        }
        JSONObject first = candidates.optJSONObject(0);
        if (first == null) {
            return null;
        }
        Map<String, String> downloadParams = new HashMap<>();
        downloadParams.put("ver", "1");
        downloadParams.put("client", "pc");
        downloadParams.put("id", first.optString("id"));
        downloadParams.put("accesskey", first.optString("accesskey"));
        downloadParams.put("fmt", "lrc");
        downloadParams.put("charset", "utf8");
        String downloadBody = Http.get("https://lyrics.kugou.com/download?"
                + Http.buildQuery(downloadParams), Http.headers());
        JSONObject downloadJson = new JSONObject(downloadBody);
        String content = downloadJson.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }
        byte[] bytes = Base64.getMimeDecoder().decode(content);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : (second == null ? "" : second);
    }
}
