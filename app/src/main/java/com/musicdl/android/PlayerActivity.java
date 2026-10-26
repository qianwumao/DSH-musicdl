package com.musicdl.android;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.musicdl.android.player.LocalTrack;
import com.musicdl.android.player.PlaybackService;
import com.musicdl.android.player.PlayerLyricsAdapter;
import com.musicdl.android.player.PlayerTrackAdapter;
import com.musicdl.android.util.LyricParser;
import com.musicdl.android.util.Storage;
import com.musicdl.android.util.WindowInsetsUtils;
import com.musicdl.android.view.VinylView;
import com.musicdl.android.view.WhaleProgressView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 本地播放器界面：控制前台播放服务、歌词与桌面歌词设置。 */
public class PlayerActivity extends Activity {

    private static final int REQUEST_OVERLAY = 2101;
    private static final int REQUEST_RUNTIME_PERMISSIONS = 2102;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> overlaySwatches = new ArrayList<>();
    private final List<Integer> overlaySwatchColors = new ArrayList<>();
    private final ExecutorService backdropPool = Executors.newSingleThreadExecutor();

    private PlaybackService service;
    private boolean bound;

    private ImageView backBtn;
    private ImageView refreshBtn;
    private TextView overlayBtn;
    private ImageView backdropView;
    private View backdropScrim;
    private int backdropToken;
    private VinylView vinylView;
    private View vinylStage;
    private View lyricsStage;
    private ImageView lyricsCover;
    private ImageView prevBtn;
    private ImageView nextBtn;
    private ImageView playlistBtn;
    private ImageView playBtn;
    private ImageView modeBtn;
    private TextView headerTitleText;
    private TextView folderText;
    private TextView titleText;
    private TextView artistText;
    private TextView formatText;
    private TextView qualityText;
    private TextView statusText;
    private TextView positionText;
    private TextView durationText;
    private WhaleProgressView progress;

    private int lastLibraryVersion = -1;
    private int lastTrackVersion = -1;
    private int lastCoverVersion = -1;
    private int lastLyricVersion = -1;
    private int lastMode = -1;
    private int lastStageLyricIndex = -1;
    private boolean userSeeking;
    private boolean lastNightMode;
    private int lastOrientation;
    private boolean lyricsMode;

    private Dialog playlistDialog;
    private PopupWindow sortPopup;
    private Dialog overlayDialog;
    private ListView lyricsList;
    private ListView playlistList;
    private TextView lyricsEmpty;
    private TextView playlistCount;
    private PlayerLyricsAdapter lyricsAdapter;
    private PlayerTrackAdapter playlistAdapter;

    private Switch overlayEnabledSwitch;
    private Switch overlayLockSwitch;
    private TextView overlayPermissionText;
    private TextView overlayPreview;
    private TextView overlaySizeValue;
    private TextView overlayAlphaValue;
    private boolean updatingSwitches;
    private boolean pendingOverlayEnable;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (service != null) {
                refreshFromService(false);
                main.postDelayed(this, 400);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlaybackService.LocalBinder local = (PlaybackService.LocalBinder) binder;
            service = local.getService();
            bound = true;
            service.scanIfNeeded();
            service.setOverlayListener(() -> runOnUiThread(PlayerActivity.this::refreshOverlayDialog));
            if (lyricsAdapter == null) {
                lyricsAdapter = new PlayerLyricsAdapter(PlayerActivity.this, service.getLyricLines());
                lyricsList.setAdapter(lyricsAdapter);
                lyricsList.setOnItemClickListener((parent, view, position, id) -> {
                    if (position >= 0 && position < service.getLyricLines().size()) {
                        service.seekTo(service.getLyricLines().get(position).timeMs);
                    }
                });
            }
            refreshFromService(true);
            startPolling();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastNightMode = isNightMode();
        lastOrientation = getResources().getConfiguration().orientation;
        getWindow().setStatusBarColor(0xFF23262C);
        getWindow().setNavigationBarColor(0xFF181A1F);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        applySystemBarIcons();
        setContentView(R.layout.activity_player);
        WindowInsetsUtils.applyEdgeToEdge(this, findViewById(R.id.player_root),
                findViewById(R.id.player_content));
        bindViews();
        applyResponsiveLayout();
        applyPlayerColors(0xFF23262C);
        if (Build.VERSION.SDK_INT < 35) {
            int navId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (navId > 0) {
                int navHeight = getResources().getDimensionPixelSize(navId);
                View content = findViewById(R.id.player_content);
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), content.getPaddingBottom() + navHeight);
            }
        }
        setupListeners();
        requestRuntimePermissions();
        startPlaybackService();
        showStatus("正在连接播放服务…");
        folderText.setText(Storage.folderLabel(this));
    }

    private void bindViews() {
        backBtn = findViewById(R.id.player_back);
        refreshBtn = findViewById(R.id.player_refresh);
        overlayBtn = findViewById(R.id.player_overlay);
        backdropView = findViewById(R.id.player_bg);
        backdropScrim = findViewById(R.id.player_bg_scrim);
        vinylView = findViewById(R.id.player_vinyl);
        vinylStage = findViewById(R.id.vinyl_stage);
        lyricsStage = findViewById(R.id.lyrics_stage);
        lyricsCover = findViewById(R.id.lyrics_cover);
        prevBtn = findViewById(R.id.player_prev);
        nextBtn = findViewById(R.id.player_next);
        playlistBtn = findViewById(R.id.player_playlist);
        playBtn = findViewById(R.id.player_toggle);
        modeBtn = findViewById(R.id.player_mode);
        headerTitleText = findViewById(R.id.player_header_title);
        folderText = findViewById(R.id.player_folder);
        titleText = findViewById(R.id.player_title);
        artistText = findViewById(R.id.player_artist);
        formatText = findViewById(R.id.player_format);
        qualityText = findViewById(R.id.player_quality);
        statusText = findViewById(R.id.player_status);
        positionText = findViewById(R.id.player_position);
        durationText = findViewById(R.id.player_duration);
        progress = findViewById(R.id.player_progress);
        lyricsList = findViewById(R.id.player_lyrics_list);
        lyricsEmpty = findViewById(R.id.player_lyrics_empty);
        lyricsList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        progress.setBarColors(0x33FFFFFF, 0xFFFFFFFF, 0xD9FFFFFF);
        progress.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                alignTimeRowToProgress();
            }
        });
    }

    /** 让起始与结束时间贴齐进度条轨道两端，不超出进度条。 */
    private void alignTimeRowToProgress() {
        View timeRow = findViewById(R.id.player_time_row);
        if (timeRow == null) {
            return;
        }
        ViewGroup.LayoutParams timeParams = timeRow.getLayoutParams();
        ViewGroup.LayoutParams barParams = progress.getLayoutParams();
        if (!(timeParams instanceof ViewGroup.MarginLayoutParams)
                || !(barParams instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams barMargins = (ViewGroup.MarginLayoutParams) barParams;
        ViewGroup.MarginLayoutParams timeMargins = (ViewGroup.MarginLayoutParams) timeParams;
        int inset = Math.round(progress.getTrackInset());
        timeMargins.setMarginStart(barMargins.getMarginStart() + inset);
        timeMargins.setMarginEnd(barMargins.getMarginEnd() + inset);
        timeRow.setLayoutParams(timeMargins);
    }

    /** 按屏幕尺寸调整播放器：唱片约占屏幕高度三分之一，大屏上按钮与文字同步放大。 */
    private void applyResponsiveLayout() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        DisplayMetrics real = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        float widthDp = real.widthPixels / real.density;
        float heightDp = real.heightPixels / real.density;

        // 按整块屏幕高度计算，让唱片直径约为屏幕高度的三分之一
        int vinylHeight = Math.round(real.heightPixels / 3f / 0.846f);
        ViewGroup.LayoutParams vinylParams = vinylView.getLayoutParams();
        vinylParams.height = vinylHeight;
        vinylView.setLayoutParams(vinylParams);

        // 平板等超宽屏限制内容宽度，避免控件被拉得过散
        int maxContentWidth = Math.round(560 * metrics.density);
        View content = findViewById(R.id.player_content);
        if (metrics.widthPixels > maxContentWidth) {
            ViewGroup.LayoutParams contentParams = content.getLayoutParams();
            contentParams.width = maxContentWidth;
            content.setLayoutParams(contentParams);
        }

        // 进度条左右各留 5%，整体缩短 10%
        int contentWidth = Math.min(metrics.widthPixels, maxContentWidth);
        int sideInset = Math.round(contentWidth * 0.05f);
        ViewGroup.MarginLayoutParams progressMargins =
                (ViewGroup.MarginLayoutParams) progress.getLayoutParams();
        progressMargins.setMarginStart(sideInset);
        progressMargins.setMarginEnd(sideInset);
        progress.setLayoutParams(progressMargins);

        // 底部留白随屏幕高度变化，让播放控制区上移，不贴着屏幕底部
        View bottomSpace = findViewById(R.id.player_bottom_space);
        if (bottomSpace != null) {
            ViewGroup.LayoutParams spaceParams = bottomSpace.getLayoutParams();
            spaceParams.height = Math.round(real.heightPixels * 0.05f);
            bottomSpace.setLayoutParams(spaceParams);
        }

        float scale = Math.max(1f, Math.min(1.5f,
                Math.max(widthDp / 400f, heightDp / 800f)));
        if (scale <= 1.01f) {
            return;
        }
        View header = findViewById(R.id.player_header);
        if (header != null) {
            ViewGroup.LayoutParams headerParams = header.getLayoutParams();
            headerParams.height = Math.round(headerParams.height * scale);
            header.setLayoutParams(headerParams);
        }
        scaleIcon(backBtn, scale);
        scaleIcon(overlayBtn, scale);
        scaleIcon(refreshBtn, scale);
        scaleIcon(modeBtn, scale);
        scaleIcon(prevBtn, scale);
        scaleIcon(playBtn, scale);
        scaleIcon(nextBtn, scale);
        scaleIcon(playlistBtn, scale);
        scaleIcon(lyricsCover, scale);
        View lyricsHeader = (View) lyricsCover.getParent();
        if (lyricsHeader != null) {
            ViewGroup.LayoutParams lyricsHeaderParams = lyricsHeader.getLayoutParams();
            lyricsHeaderParams.height = Math.round(lyricsHeaderParams.height * scale);
            lyricsHeader.setLayoutParams(lyricsHeaderParams);
        }

        scaleText(overlayBtn, scale);
        scaleText(headerTitleText, scale);
        scaleText(folderText, scale);
        scaleText(titleText, scale);
        scaleText(artistText, scale);
        scaleText(formatText, scale);
        scaleText(qualityText, scale);
        scaleText(positionText, scale);
        scaleText(durationText, scale);

        // 高分辨率/大屏手机：歌名与歌手名右移 10dp，进度条再缩短约 5%
        int highResInset = Math.round(contentWidth * 0.075f);
        progressMargins.setMarginStart(highResInset);
        progressMargins.setMarginEnd(highResInset);
        progress.setLayoutParams(progressMargins);
        addStartMargin(titleText, dp(10));
        addStartMargin(artistText, dp(10));

        ViewGroup.LayoutParams progressParams = progress.getLayoutParams();
        progressParams.height = Math.round(progressParams.height * scale);
        progress.setLayoutParams(progressParams);
    }

    private static void addStartMargin(View view, int px) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMarginStart(margins.getMarginStart() + px);
            view.setLayoutParams(params);
        }
    }

    private static void scaleIcon(View view, float scale) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width > 0) {
            params.width = Math.round(params.width * scale);
        }
        if (params.height > 0) {
            params.height = Math.round(params.height * scale);
        }
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMarginStart(Math.round(margins.getMarginStart() * scale));
            margins.setMarginEnd(Math.round(margins.getMarginEnd() * scale));
        }
        view.setLayoutParams(params);
        view.setPadding(Math.round(view.getPaddingLeft() * scale),
                Math.round(view.getPaddingTop() * scale),
                Math.round(view.getPaddingRight() * scale),
                Math.round(view.getPaddingBottom() * scale));
    }

    private static void scaleText(TextView view, float scale) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getTextSize() * scale);
    }

    private void setupListeners() {
        backBtn.setOnClickListener(v -> finish());
        refreshBtn.setOnClickListener(v -> {
            if (service != null) {
                service.refreshLibrary();
                showStatus("正在读取本地歌曲…");
                lastLibraryVersion = -1;
                lastTrackVersion = -1;
            }
        });
        overlayBtn.setOnClickListener(v -> showOverlaySettings());
        playBtn.setOnClickListener(v -> {
            if (service != null) {
                service.togglePlay();
                refreshFromService(false);
            }
        });
        prevBtn.setOnClickListener(v -> {
            if (service != null) {
                service.previous();
            }
        });
        nextBtn.setOnClickListener(v -> {
            if (service != null) {
                service.next();
            }
        });
        playlistBtn.setOnClickListener(v -> showPlaylistDialog());
        vinylView.setOnClickListener(v -> {
            if (service != null && !service.getTracks().isEmpty()) {
                setLyricsMode(true);
            }
        });
        lyricsCover.setOnClickListener(v -> setLyricsMode(false));
        modeBtn.setOnClickListener(v -> {
            if (service != null) {
                service.cyclePlayMode();
                refreshFromService(false);
            }
        });
        progress.setOnSeekListener(new WhaleProgressView.OnSeekListener() {
            @Override
            public void onSeekStart() {
                userSeeking = true;
            }

            @Override
            public void onSeekChanged(float percent) {
                if (service != null) {
                    int dur = service.getDuration();
                    if (dur > 0) {
                        positionText.setText(formatTime((long) (dur * percent / 100f)));
                    }
                }
            }

            @Override
            public void onSeekEnd(float percent) {
                if (service != null) {
                    service.seekTo((int) (service.getDuration() * percent / 100f));
                }
                userSeeking = false;
            }
        });
    }

    private void startPlaybackService() {
        Intent intent = new Intent(this, PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            try {
                startService(intent);
            } catch (Exception ignored) {
                toast("无法启动播放服务");
            }
        }
    }

    private void requestRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) {
                missing.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_RUNTIME_PERMISSIONS);
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS && service != null) {
            if (service.getTracks().isEmpty()) {
                service.refreshLibrary();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        stopPolling();
        if (vinylView != null) {
            vinylView.setPlaying(false);
        }
        if (bound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
                // ignore
            }
            bound = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        if (service != null) {
            service.setOverlayListener(null);
        }
        if (playlistDialog != null) {
            playlistDialog.dismiss();
            playlistDialog = null;
        }
        if (overlayDialog != null) {
            overlayDialog.dismiss();
            overlayDialog = null;
        }
        backdropPool.shutdownNow();
        super.onDestroy();
    }

    private void startPolling() {
        main.removeCallbacks(poll);
        main.post(poll);
    }

    private void stopPolling() {
        main.removeCallbacks(poll);
    }

    /* ---------- 服务状态刷新 ---------- */

    private void refreshFromService(boolean force) {
        if (service == null) {
            return;
        }
        List<LocalTrack> tracks = service.getTracks();
        LocalTrack track = service.getCurrentTrack();
        int libraryVersion = service.getLibraryVersion();
        int trackVersion = service.getTrackVersion();
        int coverVersion = service.getCoverVersion();
        int lyricVersion = service.getLyricVersion();
        int mode = service.getPlayMode().ordinal();

        if (force || libraryVersion != lastLibraryVersion) {
            lastLibraryVersion = libraryVersion;
            String label = Storage.folderLabel(this);
            if (track == null) {
                headerTitleText.setText("本地播放器");
                folderText.setText(tracks.isEmpty() ? label : label + " · " + tracks.size() + " 首");
            }
            if (playlistAdapter != null) {
                playlistAdapter.notifyDataSetChanged();
                playlistAdapter.setCurrentIndex(service.getCurrentIndex());
            }
            if (playlistDialog != null && playlistDialog.isShowing()) {
                playlistCount.setText(tracks.size() + " 首");
            }
        }
        if (force || trackVersion != lastTrackVersion) {
            lastTrackVersion = trackVersion;
            lastCoverVersion = -1;
            lastLyricVersion = -1;
            lastStageLyricIndex = -1;
            if (track != null) {
                String title = TextUtils.isEmpty(track.title) ? track.fileName : track.title;
                headerTitleText.setText(title);
                titleText.setText(title);
                String sub = track.subtitle();
                artistText.setText(TextUtils.isEmpty(sub) ? track.fileName : sub);
                folderText.setText(TextUtils.isEmpty(sub) ? Storage.folderLabel(this) : sub);
                updateFormat(track);
                durationText.setText(track.durationMs > 0 ? formatTime(track.durationMs) : "--:--");
                positionText.setText("0:00");
                progress.setPercent(0f);
                vinylView.setCover(null);
                lyricsCover.setImageResource(R.drawable.app_logo);
            } else {
                headerTitleText.setText("本地播放器");
                titleText.setText("");
                artistText.setText("");
                formatText.setVisibility(View.GONE);
            }
            updateStageLyricsState();
            if (playlistAdapter != null) {
                playlistAdapter.setCurrentIndex(service.getCurrentIndex());
            }
        }
        if (force || coverVersion != lastCoverVersion) {
            lastCoverVersion = coverVersion;
            Bitmap bitmap = service.getCurrentCover();
            vinylView.setCover(bitmap);
            if (bitmap != null) {
                lyricsCover.setImageBitmap(bitmap);
            } else {
                lyricsCover.setImageResource(R.drawable.app_logo);
            }
            updateBackdrop(bitmap);
        }
        if (force || lyricVersion != lastLyricVersion) {
            lastLyricVersion = lyricVersion;
            lastStageLyricIndex = -1;
            if (lyricsAdapter != null) {
                lyricsAdapter.notifyDataSetChanged();
            }
            updateStageLyricsState();
        }
        if (force || mode != lastMode) {
            lastMode = mode;
            updateModeButton(service.getPlayMode());
        }

        if (tracks.isEmpty() || (service.isScanning() && track == null)) {
            if (tracks.isEmpty()) {
                showStatus(service.isScanning() ? "正在读取本地歌曲…" : "下载目录暂无歌曲");
            } else {
                showStatus("正在读取本地歌曲…");
            }
        } else {
            hideStatus();
        }

        boolean playing = service.isPlaying();
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playBtn.setContentDescription(playing ? "暂停" : "播放");
        vinylView.setPlaying(playing);
        int duration = service.getDuration();
        if (duration > 0) {
            durationText.setText(formatTime(duration));
            int position = service.getPosition();
            if (!userSeeking && !progress.isDragging()) {
                progress.setPercent(position * 100f / duration);
                positionText.setText(formatTime(position));
            }
        }
        qualityText.setText(service.getCurrentTrack() == null
                ? "本地音乐" : service.getPlayMode().label);

        if (lyricsAdapter != null) {
            int index = service.getLyricIndex();
            lyricsAdapter.setCurrentIndex(index);
            if (lyricsMode && index >= 0 && index != lastStageLyricIndex) {
                lastStageLyricIndex = index;
                scrollStageLyrics(index, true);
            }
        }
        if (lyricsMode) {
            updateStageLyricsState();
        }
        if (playlistDialog != null && playlistDialog.isShowing()) {
            playlistAdapter.setCurrentIndex(service.getCurrentIndex());
        }
        if (overlayDialog != null && overlayDialog.isShowing()) {
            refreshOverlayDialog();
        }
    }

    private void updateFormat(LocalTrack track) {
        String name = track.fileName == null ? "" : track.fileName;
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.CHINA) : "";
        if (ext.isEmpty()) {
            formatText.setVisibility(View.GONE);
            return;
        }
        formatText.setText(ext);
        formatText.setVisibility(View.VISIBLE);
    }

    private void updateModeButton(PlaybackService.PlayMode mode) {
        modeBtn.setImageResource(mode.icon);
        modeBtn.setContentDescription(mode.label);
    }

    private void showStatus(String message) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        vinylStage.setVisibility(View.GONE);
        lyricsStage.setVisibility(View.GONE);
        progress.setEnabled(false);
        progress.setClickable(false);
        playBtn.setEnabled(false);
        prevBtn.setEnabled(false);
        nextBtn.setEnabled(false);
        playlistBtn.setEnabled(false);
        modeBtn.setEnabled(false);
        playlistBtn.setAlpha(0.4f);
        modeBtn.setAlpha(0.4f);
        vinylView.setEnabled(false);
        lyricsCover.setEnabled(false);
    }

    private void hideStatus() {
        statusText.setVisibility(View.GONE);
        progress.setEnabled(true);
        progress.setClickable(true);
        playBtn.setEnabled(true);
        prevBtn.setEnabled(true);
        nextBtn.setEnabled(true);
        playlistBtn.setEnabled(true);
        modeBtn.setEnabled(true);
        playlistBtn.setAlpha(1f);
        modeBtn.setAlpha(1f);
        vinylView.setEnabled(true);
        lyricsCover.setEnabled(true);
        applyModeUi();
    }

    private void setLyricsMode(boolean enable) {
        if (lyricsMode == enable) {
            return;
        }
        lyricsMode = enable;
        applyModeUi();
        if (enable) {
            updateStageLyricsState();
            if (lyricsAdapter != null && service != null) {
                int index = service.getLyricIndex();
                lyricsAdapter.setCurrentIndex(index);
                lastStageLyricIndex = index;
                if (index >= 0) {
                    final int target = index;
                    lyricsList.post(() -> scrollStageLyrics(target, false));
                }
            }
        }
    }

    private void applyModeUi() {
        if (statusText.getVisibility() == View.VISIBLE) {
            return;
        }
        vinylStage.setVisibility(lyricsMode ? View.GONE : View.VISIBLE);
        lyricsStage.setVisibility(lyricsMode ? View.VISIBLE : View.GONE);
    }

    private void updateStageLyricsState() {
        if (lyricsEmpty == null || lyricsList == null || service == null) {
            return;
        }
        if (service.isLyricsLoading()) {
            lyricsEmpty.setText("歌词加载中…");
            lyricsEmpty.setVisibility(View.VISIBLE);
            lyricsList.setVisibility(View.GONE);
        } else if (service.getLyricLines().isEmpty()) {
            lyricsEmpty.setText("暂无歌词");
            lyricsEmpty.setVisibility(View.VISIBLE);
            lyricsList.setVisibility(View.GONE);
        } else {
            lyricsEmpty.setVisibility(View.GONE);
            lyricsList.setVisibility(View.VISIBLE);
        }
    }

    private void scrollStageLyrics(int index, boolean smooth) {
        if (lyricsList == null || index < 0 || lyricsStage.getVisibility() != View.VISIBLE) {
            return;
        }
        if (lyricsList.getHeight() <= 0) {
            final int target = index;
            lyricsList.post(() -> {
                if (lyricsList.getHeight() > 0) {
                    scrollStageLyrics(target, smooth);
                }
            });
            return;
        }
        int offset = Math.max(0, (lyricsList.getHeight() - dp(68)) / 2);
        if (smooth) {
            lyricsList.smoothScrollToPositionFromTop(index, offset, 260);
        } else {
            lyricsList.setSelectionFromTop(index, offset);
        }
    }

    /* ---------- 封面取色背景 ---------- */

    private void applyPlayerColors(int base) {
        int top = scaleColor(base, 0.72f);
        int middle = scaleColor(base, 0.92f);
        int bottom = scaleColor(base, 0.52f);
        if (backdropScrim != null) {
            backdropScrim.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{withAlphaColor(top, 0xF0),
                            withAlphaColor(middle, 0xEE),
                            withAlphaColor(bottom, 0xF7)}));
        }
        getWindow().setStatusBarColor(top);
        getWindow().setNavigationBarColor(bottom);
        applySystemBarIcons();
        View root = findViewById(R.id.player_root);
        if (root != null) {
            root.setBackgroundColor(bottom);
        }
    }

    private void updateBackdrop(final Bitmap cover) {
        if (backdropView == null || backdropPool.isShutdown()) {
            return;
        }
        final int token = ++backdropToken;
        try {
            backdropPool.execute(() -> {
                Bitmap blurred = null;
                int base = 0xFF23262C;
                try {
                    Bitmap source = cover;
                    if (source == null || source.isRecycled()) {
                        source = BitmapFactory.decodeResource(getResources(), R.drawable.app_logo);
                    }
                    if (source == null) {
                        return;
                    }
                    blurred = makeSmallCover(source, 56);
                    blurBitmap(blurred);
                    base = stageColor(blurred);
                } catch (Throwable ignored) {
                    // 背景失败时保持纯色背景
                }
                final Bitmap image = blurred;
                final int color = base;
                runOnUiThread(() -> {
                    if (token != backdropToken || isFinishing() || backdropView == null) {
                        return;
                    }
                    if (image != null) {
                        backdropView.setImageBitmap(image);
                        backdropView.animate().cancel();
                        backdropView.setAlpha(0f);
                        backdropView.animate().alpha(0.16f).setDuration(520).start();
                    }
                    applyPlayerColors(color);
                });
            });
        } catch (RuntimeException ignored) {
            // Activity 已销毁
        }
    }

    private static int stageColor(Bitmap bitmap) {
        int color = averageColor(bitmap);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.06f) {
            hsv[1] = 0f;
        } else {
            hsv[1] = Math.min(0.68f, hsv[1] * 1.12f);
        }
        hsv[2] = Math.max(0.14f, Math.min(0.34f, hsv[2] * 0.55f));
        return Color.HSVToColor(hsv);
    }

    private static int scaleColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * factor));
        return Color.HSVToColor(hsv);
    }

    private static int withAlphaColor(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static Bitmap makeSmallCover(Bitmap source, int size) {
        Bitmap small = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int width = source.getWidth();
        int height = source.getHeight();
        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;
        canvas.drawBitmap(source, new Rect(left, top, left + side, top + side),
                new RectF(0, 0, size, size), paint);
        return small;
    }

    private static void blurBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] temp = new int[pixels.length];
        int radius = 6;
        blurPass(pixels, temp, width, height, radius, true);
        blurPass(temp, pixels, width, height, radius, false);
        blurPass(pixels, temp, width, height, radius, true);
        blurPass(temp, pixels, width, height, radius, false);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static void blurPass(int[] source, int[] target, int width, int height,
                                 int radius, boolean horizontal) {
        int lineCount = horizontal ? height : width;
        int lineLength = horizontal ? width : height;
        for (int line = 0; line < lineCount; line++) {
            int red = 0;
            int green = 0;
            int blue = 0;
            int count = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int pos = clamp(offset, 0, lineLength - 1);
                int index = horizontal ? line * width + pos : pos * width + line;
                int color = source[index];
                red += (color >> 16) & 0xFF;
                green += (color >> 8) & 0xFF;
                blue += color & 0xFF;
                count++;
            }
            for (int i = 0; i < lineLength; i++) {
                int out = horizontal ? line * width + i : i * width + line;
                target[out] = Color.argb(0xFF, red / count, green / count, blue / count);
                int removePos = clamp(i - radius, 0, lineLength - 1);
                int addPos = clamp(i + radius + 1, 0, lineLength - 1);
                int removeIndex = horizontal
                        ? line * width + removePos : removePos * width + line;
                int addIndex = horizontal ? line * width + addPos : addPos * width + line;
                int remove = source[removeIndex];
                int add = source[addIndex];
                red += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
                green += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
                blue += (add & 0xFF) - (remove & 0xFF);
            }
        }
    }

    private static int averageColor(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        for (int color : pixels) {
            if (((color >>> 24) & 0xFF) < 64) {
                continue;
            }
            red += (color >> 16) & 0xFF;
            green += (color >> 8) & 0xFF;
            blue += color & 0xFF;
            count++;
        }
        if (count == 0) {
            return 0xFF2B3440;
        }
        int color = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.08f);
        hsv[2] = Math.max(0.22f, Math.min(0.68f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /* ---------- 播放列表弹窗 ---------- */

    private void showPlaylistDialog() {
        if (service == null || service.getTracks().isEmpty()) {
            toast("下载目录暂无歌曲");
            return;
        }
        ensurePlaylistDialog();
        playlistCount.setText(service.getTracks().size() + " 首");
        playlistAdapter.notifyDataSetChanged();
        playlistAdapter.setCurrentIndex(service.getCurrentIndex());
        playlistDialog.show();
        Window window = playlistDialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (metrics.heightPixels * 0.72f));
            window.setGravity(Gravity.BOTTOM);
        }
        if (service.getCurrentIndex() >= 0) {
            final int target = service.getCurrentIndex();
            playlistList.post(() -> playlistList.setSelection(target));
        }
    }

    private void ensurePlaylistDialog() {
        if (playlistDialog != null) {
            return;
        }
        Dialog dialog = createBottomDialog();
        View content = getLayoutInflater().inflate(R.layout.dialog_player_playlist, null);
        applyDialogBottomInset(content);
        dialog.setContentView(content);
        playlistCount = content.findViewById(R.id.playlist_count);
        playlistList = content.findViewById(R.id.playlist_list);
        ImageView close = content.findViewById(R.id.playlist_close);
        ImageView sort = content.findViewById(R.id.playlist_sort);
        playlistAdapter = new PlayerTrackAdapter(this, service.getTracks());
        playlistList.setAdapter(playlistAdapter);
        close.setOnClickListener(v -> dialog.dismiss());
        sort.setOnClickListener(this::showSortMenu);
        dialog.setOnDismissListener(d -> dismissSortPopup());
        playlistList.setOnItemClickListener((parent, view, position, id) -> {
            service.selectTrack(position, true);
            dialog.dismiss();
        });
        playlistDialog = dialog;
    }

    /** 排序下拉菜单，样式与播放器一致：深色半透明、白字、选中项高亮。 */
    private void showSortMenu(View anchor) {
        if (service == null) {
            return;
        }
        dismissSortPopup();
        View content = getLayoutInflater().inflate(R.layout.popup_player_sort, null);
        LinearLayout options = content.findViewById(R.id.sort_options);
        PlaybackService.SortMode current = service.getSortMode();
        for (PlaybackService.SortMode mode : PlaybackService.SortMode.values()) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_player_sort_option, options, false);
            TextView label = row.findViewById(R.id.sort_option_title);
            ImageView check = row.findViewById(R.id.sort_option_check);
            boolean selected = mode == current;
            label.setText(mode.label);
            row.setSelected(selected);
            check.setVisibility(selected ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> {
                applySortMode(mode);
                dismissSortPopup();
            });
            options.addView(row);
        }
        PopupWindow popup = new PopupWindow(content, dp(176),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(getDrawable(R.drawable.bg_player_sort_menu));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        popup.showAsDropDown(anchor, anchor.getWidth() - dp(176), dp(6));
        sortPopup = popup;
    }

    private void dismissSortPopup() {
        if (sortPopup != null) {
            sortPopup.dismiss();
            sortPopup = null;
        }
    }

    private void applySortMode(PlaybackService.SortMode mode) {
        if (service == null) {
            return;
        }
        service.setSortMode(mode);
        if (playlistAdapter != null) {
            playlistAdapter.notifyDataSetChanged();
            playlistAdapter.setCurrentIndex(service.getCurrentIndex());
        }
        int current = service.getCurrentIndex();
        if (playlistList != null && current >= 0) {
            playlistList.setSelection(current);
        }
    }

    private void applyDialogBottomInset(View content) {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }
        int bottom = 0;
        View decor = getWindow().getDecorView();
        if (decor != null) {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                bottom = insets.getSystemWindowInsetBottom();
            }
        }
        if (bottom <= 0) {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                bottom = getResources().getDimensionPixelSize(id);
            }
        }
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), content.getPaddingBottom() + bottom);
    }

    private Dialog createBottomDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    /* ---------- 桌面歌词设置 ---------- */

    private void showOverlaySettings() {
        if (service == null) {
            toast("播放器尚未就绪");
            return;
        }
        ensureOverlayDialog();
        overlayDialog.show();
        Window window = overlayDialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (metrics.heightPixels * 0.46f));
            window.setGravity(Gravity.BOTTOM);
        }
        refreshOverlayDialog();
        service.suspendOverlay();
    }

    private void ensureOverlayDialog() {
        if (overlayDialog != null) {
            return;
        }
        Dialog dialog = createBottomDialog();
        View content = getLayoutInflater().inflate(R.layout.dialog_overlay_settings, null);
        applyDialogBottomInset(content);
        dialog.setContentView(content);
        dialog.setOnDismissListener(d -> {
            if (service != null) {
                service.resumeOverlay();
            }
        });
        overlayEnabledSwitch = content.findViewById(R.id.overlay_enabled);
        overlayLockSwitch = content.findViewById(R.id.overlay_lock);
        overlayPermissionText = content.findViewById(R.id.overlay_permission);
        overlayPreview = content.findViewById(R.id.overlay_preview);
        overlaySizeValue = content.findViewById(R.id.overlay_size_value);
        overlayAlphaValue = content.findViewById(R.id.overlay_alpha_value);
        ImageView close = content.findViewById(R.id.overlay_close);
        LinearLayout colors = content.findViewById(R.id.overlay_colors);
        TextView minus = content.findViewById(R.id.overlay_size_minus);
        TextView plus = content.findViewById(R.id.overlay_size_plus);
        TextView alphaMinus = content.findViewById(R.id.overlay_alpha_minus);
        TextView alphaPlus = content.findViewById(R.id.overlay_alpha_plus);

        buildOverlaySwatches(colors);
        close.setOnClickListener(v -> dialog.dismiss());
        overlayPermissionText.setOnClickListener(v -> {
            pendingOverlayEnable = true;
            requestOverlayPermission();
        });
        overlaySizeValue.setText(((int) service.getOverlayFontSize()) + "sp");
        overlayEnabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitches) {
                return;
            }
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    pendingOverlayEnable = true;
                    setUpdatingSwitch(overlayEnabledSwitch, false);
                    requestOverlayPermission();
                    return;
                }
                boolean ok = service.setOverlayEnabled(true);
                if (!ok) {
                    setUpdatingSwitch(overlayEnabledSwitch, false);
                    toast("需要悬浮窗权限");
                }
            } else {
                service.setOverlayEnabled(false);
            }
            updateOverlayPermissionVisibility();
        });
        overlayLockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingSwitches) {
                service.setOverlayLocked(checked);
            }
        });
        minus.setOnClickListener(v -> changeOverlaySize(-1));
        plus.setOnClickListener(v -> changeOverlaySize(1));
        alphaMinus.setOnClickListener(v -> changeOverlayAlpha(-10));
        alphaPlus.setOnClickListener(v -> changeOverlayAlpha(10));
        overlayDialog = dialog;
    }

    private void buildOverlaySwatches(LinearLayout container) {
        container.removeAllViews();
        overlaySwatches.clear();
        overlaySwatchColors.clear();
        int[] colors = {0xFFFFFFFF, 0xFF4C9AFF, 0xFF34D399, 0xFFFBBF24, 0xFFF472B6};
        for (int color : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            params.rightMargin = dp(10);
            container.addView(swatch, params);
            overlaySwatches.add(swatch);
            overlaySwatchColors.add(color);
            swatch.setOnClickListener(v -> {
                if (service != null) {
                    service.setOverlayColor(color);
                    refreshOverlaySwatches();
                    updateOverlayPreview();
                }
            });
        }
        refreshOverlaySwatches();
    }

    private void refreshOverlaySwatches() {
        if (service == null) {
            return;
        }
        int selected = service.getOverlayColor();
        for (int i = 0; i < overlaySwatches.size(); i++) {
            View swatch = overlaySwatches.get(i);
            int color = overlaySwatchColors.get(i);
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(color);
            drawable.setStroke(dp(selected == color ? 3 : 1),
                    selected == color ? getColor(R.color.text_primary) : 0x33FFFFFF);
            swatch.setBackground(drawable);
        }
    }

    private void refreshOverlayDialog() {
        if (overlayDialog == null || !overlayDialog.isShowing() || service == null) {
            return;
        }
        updatingSwitches = true;
        overlayEnabledSwitch.setChecked(service.isOverlayEnabled());
        overlayLockSwitch.setChecked(service.isOverlayLocked());
        updatingSwitches = false;
        overlayAlphaValue.setText(service.getOverlayBgAlpha() + "%");
        overlaySizeValue.setText(((int) service.getOverlayFontSize()) + "sp");
        refreshOverlaySwatches();
        updateOverlayPreview();
        updateOverlayPermissionVisibility();
    }

    private void updateOverlayPreview() {
        if (service == null || overlayPreview == null) {
            return;
        }
        overlayPreview.setTextColor(service.getOverlayColor());
        overlayPreview.setTextSize(service.getOverlayFontSize());
        int alpha = Math.round(255f * service.getOverlayBgAlpha() / 100f);
        GradientDrawable background = new GradientDrawable();
        background.setColor((alpha << 24) | 0x0D1219);
        background.setCornerRadius(dp(12));
        overlayPreview.setBackground(background);
    }

    private void updateOverlayPermissionVisibility() {
        if (overlayPermissionText == null) {
            return;
        }
        overlayPermissionText.setVisibility(Settings.canDrawOverlays(this)
                ? View.GONE : View.VISIBLE);
    }

    private void changeOverlaySize(int delta) {
        if (service == null) {
            return;
        }
        service.setOverlayFontSize(service.getOverlayFontSize() + delta);
        overlaySizeValue.setText(((int) service.getOverlayFontSize()) + "sp");
        updateOverlayPreview();
    }

    private void changeOverlayAlpha(int delta) {
        if (service == null) {
            return;
        }
        service.setOverlayBgAlpha(service.getOverlayBgAlpha() + delta);
        overlayAlphaValue.setText(service.getOverlayBgAlpha() + "%");
        updateOverlayPreview();
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_OVERLAY);
        } catch (Exception e) {
            toast("无法打开悬浮窗权限设置");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OVERLAY) {
            return;
        }
        boolean granted = Settings.canDrawOverlays(this);
        if (granted && pendingOverlayEnable && service != null) {
            service.setOverlayEnabled(true);
            pendingOverlayEnable = false;
            refreshOverlayDialog();
        } else if (!granted) {
            pendingOverlayEnable = false;
            toast("未授予悬浮窗权限");
        }
        updateOverlayPermissionVisibility();
    }

    private void setUpdatingSwitch(Switch toggle, boolean checked) {
        updatingSwitches = true;
        toggle.setChecked(checked);
        updatingSwitches = false;
    }

    /* ---------- 生命周期与系统栏 ---------- */

    @Override
    public void onBackPressed() {
        if (lyricsMode) {
            setLyricsMode(false);
            return;
        }
        if (playlistDialog != null && playlistDialog.isShowing()) {
            playlistDialog.dismiss();
            return;
        }
        if (overlayDialog != null && overlayDialog.isShowing()) {
            overlayDialog.dismiss();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isNightMode() != lastNightMode
                || newConfig.orientation != lastOrientation) {
            recreate();
            return;
        }
        applySystemBarIcons();
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applySystemBarIcons() {
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        decor.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(0, mask);
            }
        }
    }

    private String formatTime(long millis) {
        int total = (int) Math.max(0, millis / 1000);
        return String.format(Locale.CHINA, "%d:%02d", total / 60, total % 60);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
