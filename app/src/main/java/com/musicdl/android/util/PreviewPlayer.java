package com.musicdl.android.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;
import com.musicdl.android.source.MusicSource;
import com.musicdl.android.source.Sources;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 搜索结果在线播放：解析地址后流式播放，直到播放完毕。 */
public class PreviewPlayer {

    public interface Listener {
        void onState(String key, String state, int position, int duration);

        void onMessage(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private MediaPlayer player;
    private String currentKey = "";
    private boolean prepared;
    private boolean cancelled = true;
    private Runnable ticker;

    public PreviewPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void play(Song song) {
        closeCurrent();
        currentKey = song.key();
        cancelled = false;
        listener.onState(currentKey, "loading", 0, 0);

        pool.execute(() -> {
            List<MusicSource.Candidate> resolved;
            try {
                MusicSource source = Sources.get(song.source);
                resolved = source == null ? null : source.resolve(song);
            } catch (Exception e) {
                resolved = null;
            }
            final List<MusicSource.Candidate> candidates = resolved;
            main.post(() -> {
                if (cancelled) {
                    return;
                }
                if (candidates == null || candidates.isEmpty()) {
                    finish("error");
                    return;
                }
                startCandidate(candidates, 0, song.source);
            });
        });
    }

    public void toggle() {
        if (player == null || !prepared) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                emit("paused", player.getCurrentPosition(), player.getDuration());
            } else {
                player.start();
                emit("playing", player.getCurrentPosition(), player.getDuration());
                startTicker();
            }
        } catch (Exception ignored) {
            finish("error");
        }
    }

    public void stop() {
        closeCurrent();
    }

    public void release() {
        closeCurrent();
        pool.shutdownNow();
    }

    private void closeCurrent() {
        cancelled = true;
        stopTicker();
        releasePlayer();
        if (!currentKey.isEmpty()) {
            String key = currentKey;
            currentKey = "";
            listener.onState(key, "stopped", 0, 0);
        }
    }

    private void startCandidate(List<MusicSource.Candidate> candidates, int index, String source) {
        if (cancelled) {
            return;
        }
        if (index >= candidates.size()) {
            finish("error");
            return;
        }
        releasePlayer();
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        prepared = false;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Http.UA);
        headers.put("Referer", refererFor(source));
        try {
            mp.setDataSource(context, Uri.parse(candidates.get(index).url), headers);
        } catch (Exception e) {
            startCandidate(candidates, index + 1, source);
            return;
        }
        mp.setOnPreparedListener(mediaPlayer -> {
            if (cancelled) {
                releasePlayer();
                return;
            }
            prepared = true;
            try {
                mediaPlayer.start();
            } catch (Exception e) {
                finish("error");
                return;
            }
            emit("playing", 0, mediaPlayer.getDuration());
            startTicker();
        });
        mp.setOnCompletionListener(mediaPlayer -> finish("ended"));
        mp.setOnErrorListener((mediaPlayer, what, extra) -> {
            startCandidate(candidates, index + 1, source);
            return true;
        });
        try {
            mp.prepareAsync();
        } catch (Exception e) {
            startCandidate(candidates, index + 1, source);
        }
    }

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (player == null || !prepared || cancelled) {
                    return;
                }
                int position;
                try {
                    position = player.getCurrentPosition();
                } catch (Exception e) {
                    finish("error");
                    return;
                }
                emit(player.isPlaying() ? "playing" : "paused", position, player.getDuration());
                main.postDelayed(this, 500);
            }
        };
        main.postDelayed(ticker, 500);
    }

    private void stopTicker() {
        if (ticker != null) {
            main.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void finish(String state) {
        cancelled = true;
        stopTicker();
        releasePlayer();
        if (!currentKey.isEmpty()) {
            String key = currentKey;
            currentKey = "";
            listener.onState(key, state, 0, 0);
        }
        if ("error".equals(state)) {
            listener.onMessage("试听失败");
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                player.release();
            } catch (Exception ignored) {
                // ignore
            }
            player = null;
        }
    }

    private void emit(String state, int position, int duration) {
        if (!currentKey.isEmpty()) {
            listener.onState(currentKey, state, position, duration);
        }
    }

    private static String refererFor(String source) {
        switch (source) {
            case "kugou":
                return "https://www.kugou.com/";
            case "netease":
                return "https://music.163.com/";
            case "qq":
                return "https://y.qq.com/";
            default:
                return "http://www.kuwo.cn/";
        }
    }
}
