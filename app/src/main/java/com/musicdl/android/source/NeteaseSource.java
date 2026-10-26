package com.musicdl.android.source;

import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 网易云音乐：官方搜索 / 歌词 + 多路下载地址解析。 */
public class NeteaseSource implements MusicSource {

    @Override
    public String id() {
        return "netease";
    }

    @Override
    public String name() {
        return "网易云音乐";
    }

    @Override
    public List<Song> search(String keyword, int limit) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("s", keyword);
        params.put("type", "1");
        params.put("offset", "0");
        params.put("total", "true");
        params.put("limit", String.valueOf(Math.max(1, limit)));
        String body = Http.get("https://music.163.com/api/search/get/web?"
                + Http.buildQuery(params), Http.headers("Referer", "https://music.163.com/"));
        JSONObject root = new JSONObject(body);
        JSONObject result = root.optJSONObject("result");
        JSONArray list = result == null ? null : result.optJSONArray("songs");
        List<Song> songs = new ArrayList<>();
        if (list == null) {
            return songs;
        }

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.length() && songs.size() < limit; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Song song = new Song();
            song.source = id();
            song.sourceName = name();
            song.id = String.valueOf(item.optLong("id", 0));
            song.name = item.optString("name", "");
            song.singers = joinArtists(item.optJSONArray("artists"));
            JSONObject album = item.optJSONObject("album");
            if (album != null) {
                song.album = album.optString("name", "");
            }
            song.duration = item.optInt("duration", 0) / 1000;
            int fee = item.optInt("fee", 0);
            song.vip = fee == 1 || fee == 4;
            if (song.name.isEmpty() || "0".equals(song.id)) {
                continue;
            }
            ids.add(song.id);
            songs.add(song);
        }

        applyCovers(songs, ids);
        return songs;
    }

    /** 搜索接口不带封面，用批量详情接口补齐。 */
    private void applyCovers(List<Song> songs, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            StringBuilder jsonIds = new StringBuilder("[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    jsonIds.append(',');
                }
                jsonIds.append(ids.get(i));
            }
            jsonIds.append(']');
            String url = "https://music.163.com/api/song/detail?ids=" + Http.encode(jsonIds.toString());
            String body = Http.get(url, Http.headers("Referer", "https://music.163.com/"));
            JSONObject root = new JSONObject(body);
            JSONArray detailSongs = root.optJSONArray("songs");
            if (detailSongs == null) {
                return;
            }
            Map<String, String> covers = new LinkedHashMap<>();
            for (int i = 0; i < detailSongs.length(); i++) {
                JSONObject item = detailSongs.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject album = item.optJSONObject("album");
                String pic = album == null ? "" : album.optString("picUrl", "");
                if (pic.startsWith("http")) {
                    covers.put(String.valueOf(item.optLong("id", 0)), pic);
                }
            }
            for (Song song : songs) {
                String pic = covers.get(song.id);
                if (pic != null) {
                    song.cover = pic;
                }
            }
        } catch (Exception ignored) {
            // 封面失败不影响搜索
        }
    }

    @Override
    public List<Candidate> resolve(Song song) throws Exception {
        List<Candidate> candidates = MusicSource.candidates();
        String url = ThirdParty.lzmhhh("wy", song.id);
        if (!url.isEmpty()) {
            String ext = Http.extOfUrl(url);
            candidates.add(new Candidate(url, ext.isEmpty() ? "mp3" : ext));
        }
        url = ThirdParty.gdStudioNetease(song.id);
        if (!url.isEmpty()) {
            String ext = Http.extOfUrl(url);
            candidates.add(new Candidate(url, ext.isEmpty() ? "mp3" : ext));
        }
        candidates.add(new Candidate(ThirdParty.neteaseOuter(song.id), "mp3"));
        return candidates;
    }

    @Override
    public String lyric(Song song) throws Exception {
        String url = "https://music.163.com/api/song/lyric?id=" + Http.encode(song.id)
                + "&lv=1&kv=1&tv=-1";
        String body = Http.get(url, Http.headers("Referer", "https://music.163.com/"));
        JSONObject root = new JSONObject(body);
        JSONObject lrc = root.optJSONObject("lrc");
        String lyric = lrc == null ? "" : lrc.optString("lyric", "");
        return lyric.isEmpty() ? null : lyric;
    }

    private static String joinArtists(JSONArray artists) {
        if (artists == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            String name = artist.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(name);
        }
        return sb.toString();
    }
}
