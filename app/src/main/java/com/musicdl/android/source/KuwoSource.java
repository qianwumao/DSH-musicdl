package com.musicdl.android.source;

import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;
import com.musicdl.android.source.kuwo.KuwoCrypt;
import com.musicdl.android.util.FormatUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 酷我音乐：官方搜索 + 官方 mobi 接口解析下载地址。 */
public class KuwoSource implements MusicSource {

    private static final Pattern URL_PATTERN = Pattern.compile("http[^\\s$\"]+");

    @Override
    public String id() {
        return "kuwo";
    }

    @Override
    public String name() {
        return "酷我音乐";
    }

    @Override
    public List<Song> search(String keyword, int limit) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("vipver", "1");
        params.put("client", "kt");
        params.put("ft", "music");
        params.put("cluster", "0");
        params.put("strategy", "2012");
        params.put("encoding", "utf8");
        params.put("rformat", "json");
        params.put("mobi", "1");
        params.put("issubtitle", "1");
        params.put("show_copyright_off", "1");
        params.put("pn", "0");
        params.put("rn", String.valueOf(Math.max(1, limit)));
        params.put("all", keyword);

        String body = Http.get("http://www.kuwo.cn/search/searchMusicBykeyWord?"
                + Http.buildQuery(params), Http.headers("Referer", "http://www.kuwo.cn/"));
        JSONObject root = new JSONObject(body);
        JSONArray list = root.optJSONArray("abslist");
        if (list == null) {
            android.util.Log.w("MusicDL", "kuwo response: " + body.substring(0,
                    Math.min(200, body.length())));
            return new ArrayList<>();
        }

        List<Song> songs = new ArrayList<>();
        for (int i = 0; i < list.length() && songs.size() < limit; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Song song = new Song();
            song.source = id();
            song.sourceName = name();
            song.id = item.optString("MUSICRID", "").replace("MUSIC_", "");
            song.name = firstNonEmpty(item.optString("NAME"), item.optString("SONGNAME"));
            song.singers = firstNonEmpty(item.optString("ARTIST"), item.optString("FARTIST"));
            song.album = item.optString("ALBUM", "");
            try {
                song.duration = (int) Float.parseFloat(item.optString("DURATION", "0"));
            } catch (NumberFormatException ignored) {
                song.duration = 0;
            }
            song.cover = coverOf(item);
            applyQuality(song, item.optString("MINFO", ""));
            if (song.name.isEmpty() || song.id.isEmpty()) {
                continue;
            }
            songs.add(song);
        }
        return songs;
    }

    private static String coverOf(JSONObject item) {
        String shortPath = item.optString("web_albumpic_short", "");
        if (!shortPath.isEmpty()) {
            // 120/xxx/yyy.jpg -> 500/xxx/yyy.jpg，取更大的封面
            String large = shortPath.replaceFirst("^\\d+/", "500/");
            return "https://img1.kuwo.cn/star/albumcover/" + large;
        }
        String pic = item.optString("hts_MVPIC", "");
        return pic.startsWith("http") ? pic : "";
    }

    /** 从 MINFO 里取最高可用音质，仅用于界面展示。 */
    private static void applyQuality(Song song, String minfo) {
        if (minfo == null || minfo.isEmpty()) {
            return;
        }
        String flacSize = null;
        String mp3Size = null;
        for (String part : minfo.split(";")) {
            Map<String, String> kv = new HashMap<>();
            for (String piece : part.split(",")) {
                int split = piece.indexOf(':');
                if (split > 0) {
                    kv.put(piece.substring(0, split), piece.substring(split + 1));
                }
            }
            if ("flac".equalsIgnoreCase(kv.get("format")) && flacSize == null) {
                flacSize = kv.get("size");
            }
            if ("mp3".equalsIgnoreCase(kv.get("format"))
                    && "320".equals(kv.get("bitrate")) && mp3Size == null) {
                mp3Size = kv.get("size");
            }
        }
        if (flacSize != null) {
            song.quality = "FLAC";
            song.sizeText = FormatUtils.sizeOfMbText(flacSize);
        } else if (mp3Size != null) {
            song.quality = "MP3 320K";
            song.sizeText = FormatUtils.sizeOfMbText(mp3Size);
        }
    }

    @Override
    public List<Candidate> resolve(Song song) throws Exception {
        List<Candidate> candidates = MusicSource.candidates();
        Map<String, String> headers = Http.headers("user-agent", "okhttp/3.10.0");
        for (String format : new String[]{"flac", "mp3"}) {
            try {
                String query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk"
                        + "&p2p=1&type=convert_url2&sig=0&format=" + format + "&rid=" + song.id;
                String url = "http://mobi.kuwo.cn/mobi.s?f=kuwo&q="
                        + Http.encode(KuwoCrypt.encryptQuery(query));
                String body = Http.get(url, headers);
                Matcher matcher = URL_PATTERN.matcher(body);
                if (!matcher.find()) {
                    continue;
                }
                String streamUrl = matcher.group();
                String ext = Http.extOfUrl(streamUrl);
                if (ext.isEmpty()) {
                    ext = format;
                }
                candidates.add(new Candidate(streamUrl, ext));
                if (format.equals("flac")) {
                    break;
                }
            } catch (Exception ignored) {
                // 尝试下一种音质
            }
        }
        return candidates;
    }

    @Override
    public String lyric(Song song) throws Exception {
        String url = "http://newlyric.kuwo.cn/newlyric.lrc?"
                + KuwoCrypt.buildLyricParams(song.id);
        byte[] data = Http.getBytes(url, Http.headers());
        return KuwoCrypt.decodeLyric(data);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : (second == null ? "" : second);
    }
}
