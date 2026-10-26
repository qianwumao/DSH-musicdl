package com.musicdl.android.source;

import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;
import com.musicdl.android.util.FormatUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** QQ 音乐：官方 musicu 搜索接口 + 第三方解析下载地址。 */
public class QqSource implements MusicSource {

    private static final String API = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String MOBILE_KEY =
            "music.search.SearchCgiService.DoSearchForQQMusicMobile";
    private static final String MODULE = "music.search.SearchCgiService";
    private static final String MOBILE_METHOD = "DoSearchForQQMusicMobile";

    /**
     * QQ 搜索接口会校验设备标识，缺少 QIMEI 时偶尔会返回成功但结果为空。
     * 这里使用公开的固定标识，保证冷启动后的搜索结果稳定。
     */
    private static final String QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614";
    private static final Random RANDOM = new Random();

    @Override
    public String id() {
        return "qq";
    }

    @Override
    public String name() {
        return "QQ音乐";
    }

    @Override
    public List<Song> search(String keyword, int limit) throws Exception {
        int perPage = Math.max(1, limit);
        List<Song> songs = new ArrayList<>();
        Exception lastError = null;
        boolean validResponse = false;

        // 移动端接口带设备标识，比桌面端接口稳定；失败时重试一次。
        for (int attempt = 0; attempt < 2 && songs.isEmpty(); attempt++) {
            try {
                JSONObject root = new JSONObject(Http.postJson(API,
                        buildMobilePayload(keyword, perPage), headers()));
                JSONObject node = root.optJSONObject(MOBILE_KEY);
                if (node == null) {
                    lastError = new IOException("QQ 搜索接口无有效响应");
                    continue;
                }
                int code = node.optInt("code", 0);
                if (code == 0) {
                    validResponse = true;
                } else {
                    lastError = new IOException("QQ 搜索接口返回 " + code);
                }
                songs = parseSongs(mobileList(node), limit);
            } catch (Exception e) {
                lastError = e;
            }
        }

        // 兼容旧网页搜索接口，作为第二层兜底；该接口不依赖设备标识。
        if (songs.isEmpty()) {
            try {
                JSONObject root = new JSONObject(Http.get(buildLegacyUrl(keyword, perPage), headers()));
                int code = root.optInt("code", 0);
                if (code == 0) {
                    validResponse = true;
                } else if (lastError == null) {
                    lastError = new IOException("QQ 搜索接口返回 " + code);
                }
                JSONObject data = root.optJSONObject("data");
                JSONObject song = data == null ? null : data.optJSONObject("song");
                songs = parseSongs(song == null ? null : song.optJSONArray("list"), limit);
            } catch (Exception e) {
                if (lastError == null) {
                    lastError = e;
                }
            }
        }

        // 只有所有请求都失败才报错；成功但为空表示确实没有搜索结果。
        if (songs.isEmpty() && !validResponse && lastError != null) {
            android.util.Log.w("MusicDL", "qq search failed: " + lastError.getMessage());
            throw lastError;
        }
        return songs;
    }

    private static Map<String, String> headers() {
        return Http.headers(
                "Referer", "https://y.qq.com/",
                "Origin", "https://y.qq.com/");
    }

    private static String buildMobilePayload(String keyword, int limit) throws Exception {
        JSONObject comm = new JSONObject();
        comm.put("ct", "11");
        comm.put("cv", 13020508);
        comm.put("v", 13020508);
        comm.put("tmeAppID", "qqmusic");
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("uid", "3931641530");
        comm.put("QIMEI36", QIMEI36);

        JSONObject param = new JSONObject();
        param.put("searchid", randomSearchId());
        param.put("query", keyword);
        param.put("search_type", 0);
        param.put("num_per_page", limit);
        param.put("page_num", 1);
        param.put("highlight", 1);
        param.put("grp", 1);

        JSONObject req = new JSONObject();
        req.put("module", MODULE);
        req.put("method", MOBILE_METHOD);
        req.put("param", param);

        JSONObject payload = new JSONObject();
        payload.put("comm", comm);
        payload.put(MOBILE_KEY, req);
        return payload.toString();
    }

    private static String buildLegacyUrl(String keyword, int limit) {
        return "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
                + "?w=" + Http.encode(keyword)
                + "&format=json&n=" + limit
                + "&p=1&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0"
                + "&remoteplace=txt.mqq.all&uin=0&needNewCode=1&platform=wk_v17";
    }

    private static long randomSearchId() {
        long prefix = (1L + RANDOM.nextInt(20)) * 18014398509481984L;
        long middle = (long) RANDOM.nextInt(4194304) * 4294967296L;
        long timePart = System.currentTimeMillis() % 86400000L;
        return prefix + middle + timePart;
    }

    private static JSONArray mobileList(JSONObject node) {
        JSONObject data = node.optJSONObject("data");
        JSONObject body = data == null ? null : data.optJSONObject("body");
        return body == null ? null : body.optJSONArray("item_song");
    }

    private static List<Song> parseSongs(JSONArray list, int limit) {
        List<Song> songs = new ArrayList<>();
        if (list == null) {
            return songs;
        }
        for (int i = 0; i < list.length() && songs.size() < limit; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String mid = item.optString("mid", "");
            if (mid.isEmpty()) {
                mid = item.optString("songmid", "");
            }
            if (mid.isEmpty()) {
                continue;
            }
            Song song = new Song();
            song.source = "qq";
            song.sourceName = "QQ音乐";
            song.id = mid;
            song.name = firstNonEmpty(item.optString("title"),
                    firstNonEmpty(item.optString("name"), item.optString("songname")));
            song.singers = joinSingers(item.optJSONArray("singer"));
            JSONObject album = item.optJSONObject("album");
            String albumMid = "";
            if (album != null) {
                song.album = firstNonEmpty(album.optString("name"), album.optString("title"));
                albumMid = album.optString("mid", "");
            }
            if (song.album.isEmpty()) {
                song.album = item.optString("albumname", "");
            }
            if (albumMid.isEmpty()) {
                albumMid = item.optString("albummid", "");
            }
            if (!albumMid.isEmpty()) {
                song.cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
                        + albumMid + ".jpg";
            }
            song.duration = item.optInt("interval", 0);

            JSONObject pay = item.optJSONObject("pay");
            if (pay != null) {
                song.vip = pay.optInt("pay_play", 0) == 1
                        || pay.optInt("pay_down", 0) == 1
                        || pay.optInt("payplay", 0) == 1
                        || pay.optInt("paydownload", 0) == 1;
            }

            JSONObject file = item.optJSONObject("file");
            long flacSize = file == null ? item.optLong("sizeflac", 0)
                    : file.optLong("size_flac", 0);
            long mp3Size = file == null ? item.optLong("size320", 0)
                    : file.optLong("size_320mp3", 0);
            long mp3LowSize = file == null ? item.optLong("size128", 0)
                    : file.optLong("size_128mp3", 0);
            if (flacSize > 0) {
                song.quality = "FLAC";
                song.sizeText = FormatUtils.sizeOfBytes(flacSize);
            } else if (mp3Size > 0) {
                song.quality = "MP3 320K";
                song.sizeText = FormatUtils.sizeOfBytes(mp3Size);
            } else if (mp3LowSize > 0) {
                song.quality = "MP3 128K";
                song.sizeText = FormatUtils.sizeOfBytes(mp3LowSize);
            }

            if (song.name.isEmpty()) {
                continue;
            }
            songs.add(song);
        }
        return songs;
    }

    @Override
    public List<Candidate> resolve(Song song) throws Exception {
        List<Candidate> candidates = MusicSource.candidates();
        String url = ThirdParty.lzmhhh("qq", song.id);
        if (!url.isEmpty()) {
            String ext = Http.extOfUrl(url);
            candidates.add(new Candidate(url, ext.isEmpty() ? "mp3" : ext));
        }
        return candidates;
    }

    @Override
    public String lyric(Song song) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("songmid", song.id);
        params.put("format", "json");
        params.put("nobase64", "1");
        params.put("g_tk", "5381");
        String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?"
                + Http.buildQuery(params);
        String body = Http.get(url, Http.headers("Referer", "https://y.qq.com/"));
        JSONObject root = new JSONObject(body);
        String lyric = root.optString("lyric", "");
        return lyric.isEmpty() ? null : lyric;
    }

    private static String joinSingers(JSONArray singers) {
        if (singers == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < singers.length(); i++) {
            JSONObject singer = singers.optJSONObject(i);
            if (singer == null) {
                continue;
            }
            String name = singer.optString("name", "");
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

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : (second == null ? "" : second);
    }
}
