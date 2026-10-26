package com.musicdl.android.player;

import android.graphics.Bitmap;

/** 桌面播放器组件读取的轻量状态快照。 */
public final class PlayerWidgetState {

    public static volatile String title = "DSH-MD 播放器";
    public static volatile String artist = "点击进入本地播放器";
    public static volatile boolean playing;
    public static volatile boolean hasTrack;
    public static volatile Bitmap cover;

    private PlayerWidgetState() {
    }
}
