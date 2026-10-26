package com.musicdl.android.player;

import android.net.Uri;

/** 保存目录中的一首本地歌曲。 */
public class LocalTrack {

    public String title = "";
    public String artist = "";
    public String album = "";
    public long durationMs;
    public long addedAt;
    public final Uri uri;
    public final Uri lyricUri;
    public Uri coverUri;
    public final String fileName;

    public LocalTrack(Uri uri, Uri lyricUri, String fileName) {
        this.uri = uri;
        this.lyricUri = lyricUri;
        this.fileName = fileName == null ? "" : fileName;
    }

    public String key() {
        return uri == null ? "" : uri.toString();
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (artist != null && !artist.isEmpty()) {
            sb.append(artist);
        }
        if (album != null && !album.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(album);
        }
        return sb.toString();
    }
}
