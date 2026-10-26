package com.musicdl.android.source;

import com.musicdl.android.net.Http;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** 部分音乐源依赖的第三方解析接口（仅解析下载地址，搜索仍走官方接口）。 */
final class ThirdParty {

    private ThirdParty() {
    }

    /**
     * music.lzmhhh.com 解析接口。
     * type 取值：kg（酷狗 hash）、wy（网易云 id）、qq（QQ songmid）。
     */
    static String lzmhhh(String type, String id) {
        try {
            Map<String, String> form = new HashMap<>();
            form.put("id", id);
            form.put("type", type);
            Map<String, String> headers = Http.headers(
                    "Referer", "https://music.lzmhhh.com/",
                    "Origin", "https://music.lzmhhh.com");
            String body = Http.postForm("https://music.lzmhhh.com/api/music/url", form, headers);
            JSONObject json = new JSONObject(body);
            if (json.optInt("code", 0) != 1) {
                return "";
            }
            String url = json.optString("data", "");
            return url.startsWith("http") ? url : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** GDStudio 解析接口，目前仅网易云可用。 */
    static String gdStudioNetease(String id) {
        try {
            String url = "https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id="
                    + Http.encode(id) + "&br=999";
            String body = Http.get(url, Http.headers("Referer", "https://music.gdstudio.xyz/"));
            JSONObject json = new JSONObject(body);
            String result = json.optString("url", "");
            return result.startsWith("http") ? result : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 网易云公开外链（免费歌曲可直接下载）。 */
    static String neteaseOuter(String id) {
        return "https://music.163.com/song/media/outer/url?id=" + id + ".mp3";
    }
}
