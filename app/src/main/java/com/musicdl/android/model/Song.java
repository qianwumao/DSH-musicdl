package com.musicdl.android.model;

import org.json.JSONException;
import org.json.JSONObject;

/** 一首搜索结果。字段与桌面端 musicdl 的 song_info 保持对应。 */
public class Song {

    public String source = "";
    public String sourceName = "";
    public String id = "";
    public String name = "";
    public String singers = "";
    public String album = "";
    public int duration;
    public String cover = "";
    public String quality = "";
    public String sizeText = "";
    public boolean vip;
    public String extra = "";

    public String key() {
        return source + ":" + id;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("key", key());
            json.put("source", source);
            json.put("sourceName", sourceName);
            json.put("id", id);
            json.put("name", name);
            json.put("singers", singers);
            json.put("album", album);
            json.put("duration", duration);
            json.put("cover", cover);
            json.put("quality", quality);
            json.put("sizeText", sizeText);
            json.put("vip", vip);
        } catch (JSONException ignored) {
            // 字段均为基本类型，正常不会触发
        }
        return json;
    }

    public static Song fromJson(JSONObject json) {
        Song song = new Song();
        song.source = json.optString("source", "");
        song.sourceName = json.optString("sourceName", "");
        song.id = json.optString("id", "");
        song.name = json.optString("name", "");
        song.singers = json.optString("singers", "");
        song.album = json.optString("album", "");
        song.duration = json.optInt("duration", 0);
        song.cover = json.optString("cover", "");
        song.quality = json.optString("quality", "");
        song.sizeText = json.optString("sizeText", "");
        song.vip = json.optBoolean("vip", false);
        return song;
    }

    /** 保存文件名：歌名-歌手-专辑-标识（与桌面端一致）。 */
    public String fileNameBase() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (!singers.isEmpty()) {
            sb.append('-').append(singers);
        }
        if (!album.isEmpty()) {
            sb.append('-').append(album);
        }
        if (!id.isEmpty()) {
            sb.append('-').append(id);
        }
        return sb.toString();
    }
}
