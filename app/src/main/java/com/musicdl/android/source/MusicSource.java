package com.musicdl.android.source;

import com.musicdl.android.model.Song;

import java.util.ArrayList;
import java.util.List;

/** 音乐源接口：每个源负责自己的搜索与下载地址解析。 */
public interface MusicSource {

    String id();

    String name();

    List<Song> search(String keyword, int limit) throws Exception;

    /** 按优先级返回下载地址候选（不同音质或备用接口）。 */
    List<Candidate> resolve(Song song) throws Exception;

    /** 歌词（可为空）。 */
    String lyric(Song song) throws Exception;

    class Candidate {
        public final String url;
        public final String ext;

        public Candidate(String url, String ext) {
            this.url = url;
            this.ext = ext == null || ext.isEmpty() ? "mp3" : ext.toLowerCase();
        }
    }

    static List<Candidate> candidates() {
        return new ArrayList<>();
    }
}
