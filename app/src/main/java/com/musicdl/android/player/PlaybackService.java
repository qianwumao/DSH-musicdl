package com.musicdl.android.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.musicdl.android.PlayerActivity;
import com.musicdl.android.R;
import com.musicdl.android.model.Song;
import com.musicdl.android.net.Http;
import com.musicdl.android.source.MusicSource;
import com.musicdl.android.source.Sources;
import com.musicdl.android.util.FormatUtils;
import com.musicdl.android.util.LyricParser;
import com.musicdl.android.util.Storage;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前台播放服务：本地歌曲、媒体通知、桌面组件与桌面歌词。 */
public class PlaybackService extends Service {

    public static final String ACTION_TOGGLE = "com.musicdl.android.action.TOGGLE";
    public static final String ACTION_NEXT = "com.musicdl.android.action.NEXT";
    public static final String ACTION_PREVIOUS = "com.musicdl.android.action.PREVIOUS";
    public static final String ACTION_CYCLE_MODE = "com.musicdl.android.action.CYCLE_MODE";
    public static final String ACTION_REFRESH = "com.musicdl.android.action.REFRESH";

    public static final String PREFS = "musicdl_player";
    public static final String KEY_MODE = "play_mode";
    public static final String KEY_SORT_MODE = "sort_mode";
    public static final String KEY_OVERLAY_ENABLED = "overlay_enabled";
    public static final String KEY_OVERLAY_COLOR = "overlay_color";
    public static final String KEY_OVERLAY_SIZE = "overlay_size";
    public static final String KEY_OVERLAY_LOCKED = "overlay_locked";
    public static final String KEY_OVERLAY_BG_ALPHA = "overlay_bg_alpha";
    public static final String KEY_OVERLAY_X = "overlay_x";
    public static final String KEY_OVERLAY_Y = "overlay_y";

    public static final int DEFAULT_OVERLAY_COLOR = 0xFFFFFFFF;
    public static final float DEFAULT_OVERLAY_SIZE = 20f;
    public static final int DEFAULT_OVERLAY_BG_ALPHA = 80;

    private static final String CHANNEL_ID = "dsh_md_playback";
    private static final int NOTIFICATION_ID = 4101;

    public enum PlayMode {
        SEQUENTIAL("顺序播放", R.drawable.ic_mode_seq),
        REPEAT_ALL("列表循环", R.drawable.ic_mode_all),
        REPEAT_ONE("单曲循环", R.drawable.ic_mode_one),
        SHUFFLE("随机播放", R.drawable.ic_mode_shuffle);

        public final String label;
        public final int icon;

        PlayMode(String label, int icon) {
            this.label = label;
            this.icon = icon;
        }

        PlayMode next() {
            PlayMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum SortMode {
        ADDED_TIME("按添加时间"),
        ARTIST_NAME("按歌手名"),
        TITLE_AZ("歌曲名 A-Z"),
        ARTIST_AZ("歌手名 A-Z");

        public final String label;

        SortMode(String label) {
            this.label = label;
        }
    }

    public class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    public interface OverlayListener {
        void onOverlaySettingsChanged();
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanPool = Executors.newSingleThreadExecutor();
    private final ExecutorService artPool = Executors.newCachedThreadPool();
    private final ExecutorService lyricPool = Executors.newCachedThreadPool();
    private final Random random = new Random();
    private final List<LocalTrack> tracks = new ArrayList<>();
    private final List<LyricParser.Line> lyricLines = new ArrayList<>();

    private SharedPreferences prefs;
    private PlayMode playMode = PlayMode.REPEAT_ALL;
    private SortMode sortMode = SortMode.ADDED_TIME;
    private MediaPlayer player;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private LruCache<String, Bitmap> coverCache;
    private Bitmap currentCover;
    private Bitmap logoCover;
    private Runnable ticker;

    private int currentIndex = -1;
    private int lyricIndex = -1;
    private int lyricRequest;
    private boolean lyricsLoading;
    private boolean prepared;
    private boolean autoPlay;
    private boolean scanning;
    private volatile boolean destroyed;
    private boolean foregroundStarted;
    private boolean pendingAutoPlay;
    private int libraryVersion;
    private int trackVersion;
    private int coverVersion;
    private int lyricVersion;

    private WindowManager windowManager;
    private LinearLayout overlayView;
    private TextView overlayCurrent;
    private TextView overlayNext;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayEnabled;
    private boolean overlaySuppressed;
    private int overlayColor = DEFAULT_OVERLAY_COLOR;
    private float overlayFontSize = DEFAULT_OVERLAY_SIZE;
    private int overlayBgAlpha = DEFAULT_OVERLAY_BG_ALPHA;
    private boolean overlayLocked;
    private int overlayX;
    private int overlayY;
    private OverlayListener overlayListener;

    private float overlayDownX;
    private float overlayDownY;
    private int overlayStartX;
    private int overlayStartY;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        playMode = loadPlayMode();
        sortMode = loadSortMode();
        overlayColor = prefs.getInt(KEY_OVERLAY_COLOR, DEFAULT_OVERLAY_COLOR);
        overlayFontSize = prefs.getFloat(KEY_OVERLAY_SIZE, DEFAULT_OVERLAY_SIZE);
        overlayBgAlpha = prefs.getInt(KEY_OVERLAY_BG_ALPHA, DEFAULT_OVERLAY_BG_ALPHA);
        overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        overlayX = prefs.getInt(KEY_OVERLAY_X, (int) (24 * metrics.density));
        overlayY = prefs.getInt(KEY_OVERLAY_Y, (int) (metrics.heightPixels * 0.32f));

        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        coverCache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "DSH-MD:Playback");
        wakeLock.setReferenceCounted(false);

        createNotificationChannel();
        initMediaSession();

        overlayEnabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
                && Settings.canDrawOverlays(this);
        if (overlayEnabled) {
            addOverlay();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            startForegroundCompat();
            foregroundStarted = true;
        }
        String action = intent == null ? null : intent.getAction();
        if (action != null) {
            switch (action) {
                case ACTION_TOGGLE:
                    if (tracks.isEmpty()) {
                        pendingAutoPlay = true;
                        scanIfNeeded();
                    } else {
                        togglePlay();
                    }
                    break;
                case ACTION_NEXT:
                    if (tracks.isEmpty()) {
                        pendingAutoPlay = true;
                        scanIfNeeded();
                    } else {
                        next();
                    }
                    break;
                case ACTION_PREVIOUS:
                    if (tracks.isEmpty()) {
                        pendingAutoPlay = true;
                        scanIfNeeded();
                    } else {
                        previous();
                    }
                    break;
                case ACTION_CYCLE_MODE:
                    cyclePlayMode();
                    break;
                case ACTION_REFRESH:
                    refreshLibrary();
                    break;
                default:
                    break;
            }
        }
        scanIfNeeded();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopTicker();
        releasePlayer();
        if (overlayView != null) {
            removeOverlay();
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        scanPool.shutdownNow();
        artPool.shutdownNow();
        lyricPool.shutdownNow();
        if (coverCache != null) {
            coverCache.evictAll();
        }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        super.onDestroy();
    }

    /* ---------- 对外状态 ---------- */

    public List<LocalTrack> getTracks() {
        return tracks;
    }

    public LocalTrack getCurrentTrack() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return null;
        }
        return tracks.get(currentIndex);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public boolean isScanning() {
        return scanning;
    }

    public int getLibraryVersion() {
        return libraryVersion;
    }

    public int getTrackVersion() {
        return trackVersion;
    }

    public int getCoverVersion() {
        return coverVersion;
    }

    public int getLyricVersion() {
        return lyricVersion;
    }

    public Bitmap getCurrentCover() {
        return currentCover;
    }

    public List<LyricParser.Line> getLyricLines() {
        return lyricLines;
    }

    public int getLyricIndex() {
        return lyricIndex;
    }

    public boolean isLyricsLoading() {
        return lyricsLoading;
    }

    public PlayMode getPlayMode() {
        return playMode;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    /** 切换播放列表排序，当前播放的歌曲保持不变。 */
    public void setSortMode(SortMode mode) {
        if (mode == null || sortMode == mode) {
            return;
        }
        sortMode = mode;
        prefs.edit().putInt(KEY_SORT_MODE, mode.ordinal()).apply();
        sortTracks();
        libraryVersion++;
        updateNotification();
        updateWidgets();
    }

    private void sortTracks() {
        LocalTrack current = getCurrentTrack();
        final Collator collator = Collator.getInstance(Locale.CHINA);
        switch (sortMode) {
            case ARTIST_NAME:
                tracks.sort((a, b) -> {
                    int result = collator.compare(text(a.artist), text(b.artist));
                    return result != 0 ? result : collator.compare(text(a.title), text(b.title));
                });
                break;
            case TITLE_AZ:
                tracks.sort((a, b) -> compareAz(text(a.title), text(b.title)));
                break;
            case ARTIST_AZ:
                tracks.sort((a, b) -> {
                    int result = compareAz(text(a.artist), text(b.artist));
                    return result != 0 ? result : compareAz(text(a.title), text(b.title));
                });
                break;
            case ADDED_TIME:
            default:
                tracks.sort((a, b) -> {
                    int result = Long.compare(b.addedAt, a.addedAt);
                    return result != 0 ? result : collator.compare(text(a.title), text(b.title));
                });
                break;
        }
        currentIndex = current == null ? -1 : tracks.indexOf(current);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static int compareAz(String a, String b) {
        if (a.isEmpty() != b.isEmpty()) {
            return a.isEmpty() ? 1 : -1;
        }
        boolean aLatin = isLatinStart(a);
        boolean bLatin = isLatinStart(b);
        if (aLatin != bLatin) {
            return aLatin ? -1 : 1;
        }
        if (!aLatin) {
            int result = Collator.getInstance(Locale.CHINA).compare(a, b);
            return result != 0 ? result : a.compareTo(b);
        }
        int result = a.compareToIgnoreCase(b);
        return result != 0 ? result : a.compareTo(b);
    }

    /** 英文、数字排在中文前，中文内部再按拼音排序。 */
    private static boolean isLatinStart(String value) {
        return value.isEmpty() || value.charAt(0) < 0x2E80;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    public int getPosition() {
        if (player == null || !prepared) {
            return 0;
        }
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        if (player == null || !prepared) {
            LocalTrack track = getCurrentTrack();
            return track == null ? 0 : (int) Math.max(0, track.durationMs);
        }
        try {
            return Math.max(0, player.getDuration());
        } catch (Exception e) {
            return 0;
        }
    }

    public String getCurrentLyricText() {
        if (lyricsLoading) {
            return "歌词加载中…";
        }
        if (lyricLines.isEmpty()) {
            return "暂无歌词";
        }
        String line = LyricParser.lineAt(lyricLines, getPosition());
        return line == null ? "♪" : line;
    }

    public void scanIfNeeded() {
        if (scanning || !tracks.isEmpty()) {
            return;
        }
        scanLibrary(false);
    }

    public void refreshLibrary() {
        releasePlayer();
        currentIndex = -1;
        currentCover = null;
        lyricLines.clear();
        lyricIndex = -1;
        lyricsLoading = false;
        lyricRequest++;
        trackVersion++;
        coverVersion++;
        lyricVersion++;
        scanLibrary(true);
    }

    public void togglePlay() {
        if (tracks.isEmpty() || currentIndex < 0) {
            pendingAutoPlay = true;
            scanIfNeeded();
            return;
        }
        if (player == null) {
            selectTrack(currentIndex, true);
            return;
        }
        if (!prepared) {
            // prepareAsync 长时间无回调时（例如进程被系统冻结）重新装载，避免按钮卡死。
            autoPlay = true;
            LocalTrack track = getCurrentTrack();
            if (track != null) {
                releasePlayer();
                createPlayer(track);
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                releaseWakeLock();
                stopTicker();
            } else {
                if (player.getDuration() > 0
                        && player.getCurrentPosition() >= player.getDuration() - 300) {
                    player.seekTo(0);
                }
                player.start();
                acquireWakeLock();
                startTicker();
            }
            updatePlaybackOutputs();
        } catch (Exception e) {
            toast("播放失败");
        }
    }

    public void next() {
        if (tracks.isEmpty() || currentIndex < 0) {
            return;
        }
        int next = playMode == PlayMode.SHUFFLE
                ? randomIndex() : (currentIndex + 1) % tracks.size();
        selectTrack(next, true);
    }

    public void previous() {
        if (tracks.isEmpty() || currentIndex < 0) {
            return;
        }
        if (player != null && prepared && player.getCurrentPosition() > 3000) {
            seekTo(0);
            return;
        }
        int prev = (currentIndex - 1 + tracks.size()) % tracks.size();
        selectTrack(prev, true);
    }

    public void selectTrack(int index, boolean playNow) {
        if (index < 0 || index >= tracks.size()) {
            return;
        }
        releasePlayer();
        currentIndex = index;
        prepared = false;
        autoPlay = playNow;
        lyricRequest++;
        lyricsLoading = false;
        lyricLines.clear();
        lyricIndex = -1;
        currentCover = null;
        trackVersion++;
        coverVersion++;
        lyricVersion++;

        LocalTrack track = tracks.get(index);
        updateWidgets();
        updateNotification();
        updateMediaSession();
        loadCover(track);
        loadLyrics(track);
        createPlayer(track);
        updateOverlayText();
    }

    public void cyclePlayMode() {
        playMode = playMode.next();
        prefs.edit().putInt(KEY_MODE, playMode.ordinal()).apply();
        updateWidgets();
        toast("已切换为" + playMode.label);
    }

    public void seekTo(int ms) {
        if (player == null || !prepared) {
            return;
        }
        int duration = getDuration();
        if (duration <= 0) {
            return;
        }
        int target = Math.max(0, Math.min(duration, ms));
        player.seekTo(target);
        updateLyricLine(target);
        updateMediaSession();
        updateOverlayText();
    }

    private int randomIndex() {
        if (tracks.size() <= 1) {
            return 0;
        }
        int index;
        do {
            index = random.nextInt(tracks.size());
        } while (index == currentIndex);
        return index;
    }

    /* ---------- 曲库扫描 ---------- */

    private void scanLibrary(boolean force) {
        if (scanning) {
            return;
        }
        scanning = true;
        updateNotification();
        updateWidgets();
        scanPool.execute(() -> {
            List<LocalTrack> found;
            try {
                found = LocalLibrary.scan(this);
            } catch (Exception e) {
                found = new ArrayList<>();
            }
            final List<LocalTrack> result = found;
            main.post(() -> {
                if (!isServiceAlive()) {
                    return;
                }
                scanning = false;
                currentIndex = -1;
                tracks.clear();
                tracks.addAll(result);
                sortTracks();
                libraryVersion++;
                if (tracks.isEmpty()) {
                    currentIndex = -1;
                    pendingAutoPlay = false;
                    updateNotification();
                    updateWidgets();
                    updateOverlayText();
                    return;
                }
                boolean playNow = pendingAutoPlay;
                pendingAutoPlay = false;
                selectTrack(0, playNow);
                updateNotification();
                updateWidgets();
            });
        });
    }

    private boolean isServiceAlive() {
        return !destroyed;
    }

    /* ---------- 播放内核 ---------- */

    private void createPlayer(LocalTrack track) {
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(p -> {
            if (p != player) {
                return;
            }
            prepared = true;
            updateMediaSession();
            updateWidgets();
            updateOverlayText();
            if (autoPlay) {
                autoPlay = false;
                startPlayback();
            }
        });
        mp.setOnCompletionListener(p -> {
            if (p == player) {
                onTrackCompleted();
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p == player) {
                prepared = false;
                autoPlay = false;
                updateMediaSession();
                updateWidgets();
                toast("播放失败");
            }
            return true;
        });
        try {
            Uri uri = track.uri;
            if ("file".equals(uri.getScheme())) {
                mp.setDataSource(uri.getPath());
            } else {
                mp.setDataSource(this, uri);
            }
            mp.prepareAsync();
        } catch (Exception e) {
            player = null;
            try {
                mp.release();
            } catch (Exception ignored) {
                // ignore
            }
            toast("无法播放该歌曲");
        }
    }

    private void startPlayback() {
        if (player == null || !prepared) {
            autoPlay = true;
            return;
        }
        try {
            player.start();
            acquireWakeLock();
            startTicker();
            updatePlaybackOutputs();
        } catch (Exception e) {
            toast("播放失败");
        }
    }

    private void onTrackCompleted() {
        stopTicker();
        releaseWakeLock();
        if (tracks.isEmpty()) {
            return;
        }
        switch (playMode) {
            case REPEAT_ONE:
                if (player != null) {
                    player.seekTo(0);
                    startPlayback();
                }
                break;
            case SHUFFLE:
                selectTrack(randomIndex(), true);
                break;
            case REPEAT_ALL:
                selectTrack((currentIndex + 1) % tracks.size(), true);
                break;
            case SEQUENTIAL:
            default:
                if (currentIndex < tracks.size() - 1) {
                    selectTrack(currentIndex + 1, true);
                } else {
                    updatePlaybackOutputs();
                }
                break;
        }
    }

    private void releasePlayer() {
        stopTicker();
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            try {
                if (old.isPlaying()) {
                    old.stop();
                }
            } catch (Exception ignored) {
                // ignore
            }
            try {
                old.reset();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                old.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
        prepared = false;
        releaseWakeLock();
    }

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (player == null || !prepared) {
                    return;
                }
                int pos = getPosition();
                updateLyricLine(pos);
                updateMediaSession();
                main.postDelayed(this, 500);
            }
        };
        main.postDelayed(ticker, 250);
    }

    private void stopTicker() {
        if (ticker != null) {
            main.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void updatePlaybackOutputs() {
        updateNotification();
        updateMediaSession();
        updateWidgets();
        updateOverlayText();
    }

    /* ---------- 封面 ---------- */

    private void loadCover(LocalTrack track) {
        final String key = track.key();
        Bitmap cached = coverCache.get(key);
        if (cached != null) {
            currentCover = cached;
            coverVersion++;
            updatePlaybackOutputs();
            return;
        }
        artPool.execute(() -> {
            Bitmap bitmap = readLocalCover(track);
            if (bitmap == null) {
                CoverArt online = fetchOnlineCover(track);
                if (online != null) {
                    bitmap = online.bitmap;
                    if (online.uri != null) {
                        track.coverUri = online.uri;
                    }
                }
            }
            if (bitmap != null) {
                coverCache.put(key, bitmap);
            }
            final Bitmap result = bitmap;
            main.post(() -> {
                if (currentIndex < 0 || currentIndex >= tracks.size()) {
                    return;
                }
                if (!key.equals(tracks.get(currentIndex).key())) {
                    return;
                }
                currentCover = result;
                coverVersion++;
                updatePlaybackOutputs();
            });
        });
    }

    private Bitmap readLocalCover(LocalTrack track) {
        if (track.coverUri != null) {
            try {
                byte[] data = readBytes(track.coverUri);
                Bitmap bitmap = decodeBitmap(data, 480);
                if (bitmap != null) {
                    return bitmap;
                }
            } catch (Exception ignored) {
                // 继续尝试内嵌封面
            }
        }
        return readEmbeddedArt(track.uri);
    }

    private Bitmap readEmbeddedArt(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if ("file".equals(uri.getScheme())) {
                retriever.setDataSource(uri.getPath());
            } else {
                retriever.setDataSource(this, uri);
            }
            byte[] data = retriever.getEmbeddedPicture();
            if (data == null || data.length == 0) {
                return null;
            }
            return decodeBitmap(data, 480);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private CoverArt fetchOnlineCover(LocalTrack track) {
        String title = track.title == null ? "" : track.title.trim();
        String artist = track.artist == null ? "" : track.artist.trim();
        if (title.isEmpty()) {
            return null;
        }
        try {
            MusicSource source = Sources.get("qq");
            if (source == null) {
                return null;
            }
            List<Song> results = source.search((title + " " + artist).trim(), 8);
            Song matched = null;
            for (Song song : results) {
                if (song.name == null || !song.name.trim().equalsIgnoreCase(title)) {
                    continue;
                }
                if (song.cover == null || song.cover.isEmpty()) {
                    continue;
                }
                if (!artist.isEmpty() && song.singers != null
                        && !song.singers.contains(artist) && !artist.contains(song.singers)) {
                    continue;
                }
                matched = song;
                break;
            }
            if (matched == null) {
                return null;
            }
            byte[] data = Http.getBytes(matched.cover,
                    Http.headers("Referer", "https://y.qq.com/"));
            if (data == null || data.length == 0) {
                return null;
            }
            Bitmap bitmap = decodeBitmap(data, 480);
            Uri saved = null;
            try {
                String ext = Http.extOfUrl(matched.cover);
                if (!"png".equals(ext) && !"webp".equals(ext)
                        && !"jpeg".equals(ext) && !"jpg".equals(ext)) {
                    ext = "jpg";
                }
                String mime;
                if ("png".equals(ext)) {
                    mime = "image/png";
                } else if ("webp".equals(ext)) {
                    mime = "image/webp";
                } else {
                    mime = "image/jpeg";
                }
                String coverName = FormatUtils.sanitizeFileName(
                        stripExtension(track.fileName)) + "." + ext;
                saved = Storage.saveImage(this, coverName, mime, data);
            } catch (Exception ignored) {
                // 保存失败仍显示本次图片
            }
            if (bitmap != null || saved != null) {
                return new CoverArt(bitmap, saved);
            }
        } catch (Exception ignored) {
            // 网络失败或未匹配
        }
        return null;
    }

    private Bitmap defaultCover() {
        if (logoCover == null) {
            logoCover = BitmapFactory.decodeResource(getResources(), R.drawable.app_logo);
        }
        return logoCover;
    }

    private Bitmap decodeBitmap(byte[] data, int targetWidth) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "cover";
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isEmpty() ? "cover" : base;
    }

    private static final class CoverArt {
        final Bitmap bitmap;
        final Uri uri;

        CoverArt(Bitmap bitmap, Uri uri) {
            this.bitmap = bitmap;
            this.uri = uri;
        }
    }

    /* ---------- 歌词 ---------- */

    private void loadLyrics(LocalTrack track) {
        final int request = ++lyricRequest;
        lyricsLoading = true;
        lyricLines.clear();
        lyricIndex = -1;
        lyricVersion++;
        updateOverlayText();
        final Uri lyricUri = track.lyricUri;
        final String key = track.key();
        if (lyricUri == null) {
            lyricsLoading = false;
            lyricVersion++;
            updateOverlayText();
            return;
        }
        lyricPool.execute(() -> {
            String raw = null;
            try {
                raw = readLyrics(lyricUri);
            } catch (Exception ignored) {
                // 显示暂无歌词
            }
            final List<LyricParser.Line> parsed = LyricParser.parse(raw);
            main.post(() -> {
                if (request != lyricRequest) {
                    return;
                }
                if (currentIndex < 0 || currentIndex >= tracks.size()
                        || !key.equals(tracks.get(currentIndex).key())) {
                    return;
                }
                lyricsLoading = false;
                lyricLines.clear();
                lyricLines.addAll(parsed);
                lyricIndex = -1;
                lyricVersion++;
                updateLyricLine(getPosition());
                updateOverlayText();
            });
        });
    }

    private String readLyrics(Uri uri) throws Exception {
        byte[] data = readBytes(uri);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream input;
        if ("file".equals(uri.getScheme())) {
            input = new FileInputStream(uri.getPath());
        } else {
            input = getContentResolver().openInputStream(uri);
        }
        if (input == null) {
            return null;
        }
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    private void updateLyricLine(int positionMs) {
        if (lyricsLoading || lyricLines.isEmpty()) {
            if (lyricIndex != -1) {
                lyricIndex = -1;
                updateOverlayText();
            }
            return;
        }
        int index = LyricParser.indexAt(lyricLines, positionMs);
        if (index != lyricIndex) {
            lyricIndex = index;
            updateOverlayText();
        }
    }

    /* ---------- 通知 / 媒体会话 / 桌面组件 ---------- */

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "本地播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("DSH-MD 本地播放控制");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "DSH-MD");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (player != null && prepared && !isPlaying()) {
                    startPlayback();
                } else if (!isPlaying()) {
                    togglePlay();
                }
            }

            @Override
            public void onPause() {
                if (isPlaying()) {
                    togglePlay();
                }
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                if (isPlaying()) {
                    togglePlay();
                }
            }
        });
        Intent openIntent = new Intent(this, PlayerActivity.class);
        mediaSession.setSessionActivity(PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) {
            return;
        }
        LocalTrack track = getCurrentTrack();
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        if (track != null) {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE,
                    TextUtils.isEmpty(track.title) ? track.fileName : track.title);
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist == null ? "" : track.artist);
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, track.album == null ? "" : track.album);
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, getDuration()));
            Bitmap art = currentCover != null ? currentCover : defaultCover();
            if (art != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
        }
        mediaSession.setMetadata(metadata.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        int state;
        if (track == null) {
            state = PlaybackState.STATE_STOPPED;
        } else if (!prepared) {
            state = PlaybackState.STATE_BUFFERING;
        } else if (isPlaying()) {
            state = PlaybackState.STATE_PLAYING;
        } else {
            state = PlaybackState.STATE_PAUSED;
        }
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, track == null ? 0 : getPosition(), isPlaying() ? 1f : 0f)
                .build());
    }

    private void updateNotification() {
        if (!foregroundStarted) {
            return;
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        LocalTrack track = getCurrentTrack();
        String title;
        String text;
        if (track != null) {
            title = TextUtils.isEmpty(track.title) ? track.fileName : track.title;
            text = track.subtitle();
            if (TextUtils.isEmpty(text)) {
                text = "本地播放";
            }
        } else {
            title = "DSH-MD 本地播放器";
            text = scanning ? "正在读取本地歌曲…" : "下载目录暂无歌曲";
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent openIntent = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent, flags);
        PendingIntent prev = servicePending(ACTION_PREVIOUS, 11);
        PendingIntent toggle = servicePending(ACTION_TOGGLE, 12);
        PendingIntent next = servicePending(ACTION_NEXT, 13);

        Notification.Action prevAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif_prev),
                "上一首", prev).build();
        Notification.Action toggleAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                        isPlaying() ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play),
                isPlaying() ? "暂停" : "播放", toggle).build();
        Notification.Action nextAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif_next),
                "下一首", next).build();

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setColor(getColor(R.color.accent))
                .setStyle(style)
                .addAction(prevAction)
                .addAction(toggleAction)
                .addAction(nextAction);
        Bitmap large = currentCover != null ? currentCover : defaultCover();
        if (large != null) {
            builder.setLargeIcon(large);
        }
        return builder.build();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateWidgets() {
        LocalTrack track = getCurrentTrack();
        PlayerWidgetState.title = track == null
                ? "DSH-MD 播放器"
                : (TextUtils.isEmpty(track.title) ? track.fileName : track.title);
        String sub = track == null ? (scanning ? "正在读取本地歌曲…" : "点击进入本地播放器")
                : track.subtitle();
        PlayerWidgetState.artist = sub;
        PlayerWidgetState.playing = isPlaying();
        PlayerWidgetState.hasTrack = track != null;
        PlayerWidgetState.cover = currentCover;
        PlayerWidgetProvider.updateAll(this);
    }

    /* ---------- 桌面歌词 ---------- */

    public boolean canDrawOverlay() {
        return Settings.canDrawOverlays(this);
    }

    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public int getOverlayColor() {
        return overlayColor;
    }

    public float getOverlayFontSize() {
        return overlayFontSize;
    }

    public int getOverlayBgAlpha() {
        return overlayBgAlpha;
    }

    public boolean isOverlayLocked() {
        return overlayLocked;
    }

    public void setOverlayListener(OverlayListener listener) {
        overlayListener = listener;
    }

    public boolean setOverlayEnabled(boolean enabled) {
        if (enabled) {
            if (!Settings.canDrawOverlays(this)) {
                return false;
            }
            overlayEnabled = true;
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, true).apply();
            addOverlay();
            return true;
        }
        overlayEnabled = false;
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, false).apply();
        removeOverlay();
        return true;
    }

    public void suspendOverlay() {
        overlaySuppressed = true;
        removeOverlay();
    }

    public void resumeOverlay() {
        overlaySuppressed = false;
        if (overlayEnabled) {
            addOverlay();
        }
    }

    public void setOverlayColor(int color) {
        overlayColor = color;
        prefs.edit().putInt(KEY_OVERLAY_COLOR, color).apply();
        updateOverlayAppearance();
        notifyOverlaySettings();
    }

    public void setOverlayFontSize(float size) {
        overlayFontSize = Math.max(14f, Math.min(32f, size));
        prefs.edit().putFloat(KEY_OVERLAY_SIZE, overlayFontSize).apply();
        updateOverlayAppearance();
        notifyOverlaySettings();
    }

    public void setOverlayBgAlpha(int percent) {
        overlayBgAlpha = Math.max(0, Math.min(100, percent));
        prefs.edit().putInt(KEY_OVERLAY_BG_ALPHA, overlayBgAlpha).apply();
        updateOverlayAppearance();
        notifyOverlaySettings();
    }

    public void setOverlayLocked(boolean locked) {
        overlayLocked = locked;
        prefs.edit().putBoolean(KEY_OVERLAY_LOCKED, locked).apply();
        updateOverlayFlags();
        notifyOverlaySettings();
    }

    private void notifyOverlaySettings() {
        if (overlayListener != null) {
            overlayListener.onOverlaySettingsChanged();
        }
    }

    private void addOverlay() {
        if (overlaySuppressed || !Settings.canDrawOverlays(this)) {
            return;
        }
        if (overlayView != null) {
            updateOverlayText();
            return;
        }
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setClickable(true);
        overlayView.setPadding(dp(16), dp(10), dp(16), dp(10));
        overlayView.setBackground(buildOverlayBackground());
        overlayView.setElevation(dp(6));

        overlayCurrent = new TextView(this);
        overlayCurrent.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        overlayCurrent.setSingleLine(false);
        overlayCurrent.setMaxLines(2);
        overlayCurrent.setShadowLayer(dp(5), 0, dp(1), 0xB3000000);
        overlayCurrent.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        overlayNext = new TextView(this);
        overlayNext.setSingleLine(false);
        overlayNext.setMaxLines(2);
        overlayNext.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        overlayNext.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        overlayView.addView(overlayCurrent);
        overlayView.addView(overlayNext);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                overlayWindowFlags(), PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = overlayX;
        overlayParams.y = overlayY;
        overlayView.setOnTouchListener((v, event) -> handleOverlayTouch(event));
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Exception e) {
            overlayView = null;
            overlayCurrent = null;
            overlayNext = null;
            return;
        }
        updateOverlayAppearance();
        updateOverlayText();
        updateOverlayFlags();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
                // ignore
            }
        }
        overlayView = null;
        overlayCurrent = null;
        overlayNext = null;
        overlayParams = null;
    }

    private GradientDrawable buildOverlayBackground() {
        int alpha = Math.round(255f * overlayBgAlpha / 100f);
        GradientDrawable background = new GradientDrawable();
        background.setColor(withAlpha(0x0D1219, alpha));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), withAlpha(0xFFFFFF, Math.round(0x45 * alpha / 255f)));
        return background;
    }

    private int overlayWindowFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | (overlayLocked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
    }

    private void updateOverlayAppearance() {
        if (overlayCurrent == null || overlayNext == null) {
            return;
        }
        boolean hasBackdrop = overlayBgAlpha > 0;
        if (overlayView != null) {
            overlayView.setBackground(buildOverlayBackground());
            // 背景全透明时去掉阴影，避免文字周围出现发光、发虚的暗晕。
            overlayView.setElevation(hasBackdrop ? dp(6) : 0f);
        }
        overlayCurrent.setTextSize(TypedValue.COMPLEX_UNIT_SP, overlayFontSize);
        overlayNext.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12f, overlayFontSize * 0.78f));
        overlayCurrent.setTextColor(overlayColor);
        overlayNext.setTextColor(withAlpha(overlayColor, 0xA6));
        overlayCurrent.setShadowLayer(hasBackdrop ? dp(5) : 0,
                0, hasBackdrop ? dp(1) : 0, hasBackdrop ? 0xB3000000 : 0);
        overlayNext.setShadowLayer(hasBackdrop ? dp(4) : 0,
                0, hasBackdrop ? dp(1) : 0, hasBackdrop ? 0x99000000 : 0);
        if (overlayParams != null && overlayView != null) {
            overlayParams.flags = overlayWindowFlags();
            try {
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void updateOverlayFlags() {
        if (overlayParams == null || overlayView == null) {
            return;
        }
        overlayParams.flags = overlayWindowFlags();
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void updateOverlayText() {
        if (overlayCurrent == null || overlayNext == null) {
            return;
        }
        String current;
        String next;
        LocalTrack track = getCurrentTrack();
        if (track == null) {
            current = "DSH-MD · 桌面歌词";
            next = "在播放器中播放歌曲后显示歌词";
        } else if (lyricsLoading) {
            current = "歌词加载中…";
            next = "";
        } else if (lyricLines.isEmpty()) {
            current = "暂无歌词";
            next = "";
        } else {
            int index = lyricIndex;
            if (index < 0) {
                current = "♪";
                next = lyricLines.get(0).text;
            } else {
                current = lyricLines.get(index).text;
                next = index + 1 < lyricLines.size() ? lyricLines.get(index + 1).text : "";
            }
        }
        if (!current.equals(overlayCurrent.getText().toString())) {
            overlayCurrent.setText(current);
        }
        if (!next.equals(overlayNext.getText().toString())) {
            overlayNext.setText(next);
        }
        overlayNext.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        if (overlayParams != null && overlayView != null) {
            overlayParams.flags = overlayWindowFlags();
            try {
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private boolean handleOverlayTouch(MotionEvent event) {
        if (overlayParams == null || overlayView == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (overlayLocked) {
                    return false;
                }
                overlayDownX = event.getRawX();
                overlayDownY = event.getRawY();
                overlayStartX = overlayParams.x;
                overlayStartY = overlayParams.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (overlayLocked) {
                    return false;
                }
                int x = overlayStartX + (int) (event.getRawX() - overlayDownX);
                int y = overlayStartY + (int) (event.getRawY() - overlayDownY);
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                x = Math.max(0, Math.min(metrics.widthPixels - overlayView.getWidth(), x));
                y = Math.max(0, Math.min(metrics.heightPixels - overlayView.getHeight(), y));
                overlayParams.x = x;
                overlayParams.y = y;
                try {
                    windowManager.updateViewLayout(overlayView, overlayParams);
                } catch (Exception ignored) {
                    // ignore
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (overlayLocked) {
                    return false;
                }
                overlayX = overlayParams.x;
                overlayY = overlayParams.y;
                prefs.edit().putInt(KEY_OVERLAY_X, overlayX)
                        .putInt(KEY_OVERLAY_Y, overlayY).apply();
                return true;
            default:
                return false;
        }
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ---------- 其他 ---------- */

    private PlayMode loadPlayMode() {
        int saved = prefs.getInt(KEY_MODE, PlayMode.REPEAT_ALL.ordinal());
        PlayMode[] values = PlayMode.values();
        return values[Math.max(0, Math.min(values.length - 1, saved))];
    }

    private SortMode loadSortMode() {
        int saved = prefs.getInt(KEY_SORT_MODE, SortMode.ADDED_TIME.ordinal());
        SortMode[] values = SortMode.values();
        return values[Math.max(0, Math.min(values.length - 1, saved))];
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void toast(String message) {
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
