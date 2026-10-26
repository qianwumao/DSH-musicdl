package com.musicdl.android.source;

import java.util.LinkedHashMap;
import java.util.Map;

/** 音乐源注册表。 */
public final class Sources {

    private static final Map<String, MusicSource> ALL = new LinkedHashMap<>();

    static {
        register(new KuwoSource());
        register(new KugouSource());
        register(new NeteaseSource());
        register(new QqSource());
    }

    private Sources() {
    }

    private static void register(MusicSource source) {
        ALL.put(source.id(), source);
    }

    public static MusicSource get(String id) {
        return ALL.get(id);
    }
}
