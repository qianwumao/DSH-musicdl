package com.musicdl.android;

import android.os.Handler;
import android.os.Looper;

import com.musicdl.android.model.Song;
import com.musicdl.android.source.MusicSource;
import com.musicdl.android.source.Sources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 多音乐源并发搜索调度，结果按源回调到主线程。 */
public class SearchManager {

    public interface Callback {
        void onSourceResults(String sourceId, List<Song> songs);

        void onSourceError(String sourceId, String message);

        void onFinished();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile AtomicBoolean cancel = new AtomicBoolean(true);

    public void search(String keyword, List<String> sourceIds, int limit, Callback callback) {
        cancel.set(true);
        AtomicBoolean current = new AtomicBoolean(false);
        cancel = current;

        List<MusicSource> selected = new ArrayList<>();
        for (String id : sourceIds) {
            MusicSource source = Sources.get(id);
            if (source != null) {
                selected.add(source);
            }
        }
        if (selected.isEmpty()) {
            main.post(callback::onFinished);
            return;
        }

        AtomicInteger remaining = new AtomicInteger(selected.size());
        for (MusicSource source : selected) {
            pool.execute(() -> {
                try {
                    if (current.get()) {
                        return;
                    }
                    List<Song> songs = source.search(keyword, limit);
                    if (!current.get()) {
                        main.post(() -> {
                            if (!current.get()) {
                                callback.onSourceResults(source.id(), songs);
                            }
                        });
                    }
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.isEmpty()) {
                        message = e.getClass().getSimpleName();
                    }
                    android.util.Log.e("MusicDL", "search " + source.id() + " failed", e);
                    String detail = message;
                    if (!current.get()) {
                        main.post(() -> {
                            if (!current.get()) {
                                callback.onSourceError(source.id(), detail);
                            }
                        });
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        main.post(() -> {
                            if (!current.get()) {
                                callback.onFinished();
                            }
                        });
                    }
                }
            });
        }
    }

    public void cancel() {
        cancel.set(true);
    }
}
