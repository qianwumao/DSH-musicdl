package com.musicdl.android;

/** 音乐源的展示信息，与后端 Source.id 保持一致。 */
final class UiSources {

    static final String[] IDS = {"kuwo", "kugou", "netease", "qq"};
    static final String[] SHORTS = {"酷我", "酷狗", "网易云", "QQ"};
    static final String[] COLORS = {"#F59E0B", "#0EA5E9", "#EF4444", "#22C55E"};
    static final int[] ICONS = {
            R.drawable.ic_source_kuwo,
            R.drawable.ic_source_kugou,
            R.drawable.ic_source_netease,
            R.drawable.ic_source_qq
    };

    private UiSources() {
    }

    static String shortOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return SHORTS[i];
            }
        }
        return id;
    }

    static String colorOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return COLORS[i];
            }
        }
        return "#0078D4";
    }
}
