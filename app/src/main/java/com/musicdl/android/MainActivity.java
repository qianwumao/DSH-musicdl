package com.musicdl.android;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.musicdl.android.model.Song;
import com.musicdl.android.source.MusicSource;
import com.musicdl.android.source.Sources;
import com.musicdl.android.util.Downloader;
import com.musicdl.android.util.ImageLoader;
import com.musicdl.android.util.LyricParser;
import com.musicdl.android.util.PreviewPlayer;
import com.musicdl.android.util.Storage;
import com.musicdl.android.util.WindowInsetsUtils;
import com.musicdl.android.view.WhaleProgressView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** DSH-MD 主界面：原生 Android View 实现，不依赖 WebView。 */
public class MainActivity extends Activity {

    private static final int REQUEST_STORAGE = 1001;
    private static final int REQUEST_FOLDER = 1002;
    private static final int REQUEST_NOTIFICATION = 1003;
    private static final int MIN_LIMIT = 5;
    private static final int MAX_LIMIT = 50;
    private static final int LIMIT_STEP = 5;
    private static final String PREFS = "musicdl_ui";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Song> songs = new ArrayList<>();
    private final Map<String, List<Song>> bySource = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> existing = new HashSet<>();
    private final List<String> enabledSources = new ArrayList<>(Arrays.asList("kuwo", "kugou"));
    private final List<String> errors = new ArrayList<>();
    private final List<DownloadAdapter.Item> downloadItems = new ArrayList<>();
    private final SearchManager searchManager = new SearchManager();
    private final ExecutorService metaPool = Executors.newSingleThreadExecutor();

    private ListView songList;
    private View header;
    private EditText keywordInput;
    private View searchBtn;
    private ImageView searchIcon;
    private ProgressBar searchLoading;
    private LinearLayout chipsContainer;
    private ImageView limitMinus;
    private ImageView limitPlus;
    private TextView limitValue;
    private TextView toggleSkip;
    private TextView toggleAuto;
    private View folderButton;
    private TextView folderPath;
    private TextView folderReset;
    private TextView resultCount;

    private View dock;
    private TextView selectedCount;
    private TextView totalCount;
    private TextView toggleAll;
    private View downloadButton;
    private TextView downloadLabel;

    private View previewBar;
    private ImageView previewCover;
    private TextView previewTitle;
    private TextView previewSub;
    private TextView previewLyric;
    private WhaleProgressView previewProgress;
    private ImageView previewToggle;
    private String previewCoverUrl = "";
    private final List<LyricParser.Line> lyricLines = new ArrayList<>();
    private final ExecutorService lyricPool = Executors.newSingleThreadExecutor();
    private boolean lyricLoading;
    private int lyricRequest;

    private SongAdapter adapter;
    private DownloadAdapter downloadAdapter;
    private Dialog downloadDialog;
    private TextView sheetSummary;
    private TextView sheetCancel;
    private WhaleProgressView sheetProgress;

    private PreviewPlayer previewPlayer;
    private Downloader downloader;
    private List<Song> pendingDownloadSongs;

    private String keyword = "";
    private String previewKey = "";
    private String previewState = "";
    private Song previewSong;
    private int previewPosition;
    private int previewDuration;
    private int limit = 10;
    private boolean skipExisting = true;
    private boolean autoDownload;
    private boolean searching;
    private boolean searched;
    private boolean downloading;
    private double overallPercent;
    private int statsFinished;
    private int statsSuccess;
    private int statsSkipped;
    private int statsFailed;
    private boolean lastNightMode;

    private final Runnable closeSheetRunnable = () -> {
        if (downloadDialog != null && downloadDialog.isShowing()) {
            downloadDialog.dismiss();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastNightMode = isNightMode();
        getWindow().setStatusBarColor(getColor(R.color.app_bg));
        getWindow().setNavigationBarColor(getColor(R.color.app_bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        applySystemBarIcons();
        setContentView(R.layout.activity_main);
        WindowInsetsUtils.applyEdgeToEdge(this, findViewById(R.id.main_root));

        songList = findViewById(R.id.song_list);
        initHeader();
        initList();
        initPreview();
        initDock();

        loadPrefs();
        buildChips();
        applyPrefsToViews();
        refreshFolder();
        refreshLimit();
        updateResultCount();
        updateSearchUi();
        updateDock();
        updateOverlayPositions();
        reloadExisting();
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION);
        }
    }

    /* ---------- 初始化 ---------- */

    private void initHeader() {
        header = getLayoutInflater().inflate(R.layout.layout_header, songList, false);
        keywordInput = header.findViewById(R.id.keyword_input);
        searchBtn = header.findViewById(R.id.search_btn);
        searchIcon = header.findViewById(R.id.search_icon);
        searchLoading = header.findViewById(R.id.search_loading);
        chipsContainer = header.findViewById(R.id.source_chips);
        limitMinus = header.findViewById(R.id.limit_minus);
        limitPlus = header.findViewById(R.id.limit_plus);
        limitValue = header.findViewById(R.id.limit_value);
        toggleSkip = header.findViewById(R.id.toggle_skip);
        toggleAuto = header.findViewById(R.id.toggle_auto);
        folderButton = header.findViewById(R.id.folder_btn);
        folderPath = header.findViewById(R.id.folder_path);
        folderReset = header.findViewById(R.id.folder_reset);
        resultCount = header.findViewById(R.id.result_count);

        searchBtn.setOnClickListener(v -> startSearch());
        keywordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch();
                return true;
            }
            return false;
        });
        limitMinus.setOnClickListener(v -> changeLimit(-LIMIT_STEP));
        limitPlus.setOnClickListener(v -> changeLimit(LIMIT_STEP));
        toggleSkip.setOnClickListener(v -> {
            skipExisting = !skipExisting;
            adapter.setSkipExisting(skipExisting);
            pruneSelectedExisting();
            adapter.notifyDataSetChanged();
            updateDock();
            savePrefs();
        });
        toggleAuto.setOnClickListener(v -> {
            autoDownload = !autoDownload;
            savePrefs();
            applyPrefsToViews();
        });
        folderButton.setOnClickListener(v -> openFolderPicker());
        folderReset.setOnClickListener(v -> {
            Storage.setCustomTree(this, null);
            refreshFolder();
            reloadExisting();
            toast("已恢复默认保存目录");
        });

        TextView playerMode = header.findViewById(R.id.player_mode_btn);
        playerMode.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(previewKey)) {
                clearPreview(true);
            }
            startActivity(new Intent(this, PlayerActivity.class));
        });
    }

    private void initList() {
        songList.addHeaderView(header, null, false);
        songList.setVerticalScrollBarEnabled(false);
        adapter = new SongAdapter(this, songs, selected, existing, new SongAdapter.Listener() {
            @Override
            public void onSongClick(Song song) {
                if (adapter.isExisting(song)) {
                    toast("该歌曲已在保存目录中");
                    return;
                }
                if (selected.contains(song.key())) {
                    selected.remove(song.key());
                } else {
                    selected.add(song.key());
                }
                adapter.notifyDataSetChanged();
                updateDock();
            }

            @Override
            public void onPreviewClick(Song song) {
                togglePreview(song);
            }
        });
        songList.setAdapter(adapter);
    }

    private void initPreview() {
        previewBar = findViewById(R.id.preview_bar);
        previewCover = findViewById(R.id.preview_cover);
        previewTitle = findViewById(R.id.preview_title);
        previewSub = findViewById(R.id.preview_sub);
        previewLyric = findViewById(R.id.preview_lyric);
        previewLyric.setSelected(true);
        previewProgress = findViewById(R.id.preview_progress);
        previewToggle = findViewById(R.id.preview_toggle);
        ImageView close = findViewById(R.id.preview_close);

        previewToggle.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(previewKey) && !"loading".equals(previewState)
                    && previewPlayer != null) {
                previewPlayer.toggle();
            }
        });
        close.setOnClickListener(v -> clearPreview(true));
    }

    private void initDock() {
        dock = findViewById(R.id.dock);
        selectedCount = findViewById(R.id.selected_count);
        totalCount = findViewById(R.id.total_count);
        toggleAll = findViewById(R.id.toggle_all);
        downloadButton = findViewById(R.id.download_btn);
        downloadLabel = findViewById(R.id.download_label);

        toggleAll.setOnClickListener(v -> toggleAllSongs());
        downloadButton.setOnClickListener(v -> {
            if (downloading) {
                showDownloadSheet();
            } else {
                startDownload();
            }
        });
    }

    /* ---------- 偏好与选项 ---------- */

    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString("sources", "kuwo,kugou");
        List<String> loaded = new ArrayList<>();
        if (raw != null) {
            for (String id : raw.split(",")) {
                String trimmed = id.trim();
                if (knownSource(trimmed) && !loaded.contains(trimmed)) {
                    loaded.add(trimmed);
                }
            }
        }
        if (!loaded.isEmpty()) {
            enabledSources.clear();
            enabledSources.addAll(loaded);
            sortSources(enabledSources);
        }
        limit = clamp(prefs.getInt("limit", 10), MIN_LIMIT, MAX_LIMIT);
        skipExisting = prefs.getBoolean("skip_existing", true);
        autoDownload = prefs.getBoolean("auto_download", false);
    }

    private void savePrefs() {
        StringBuilder sources = new StringBuilder();
        for (String id : enabledSources) {
            if (sources.length() > 0) {
                sources.append(',');
            }
            sources.append(id);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("sources", sources.toString())
                .putInt("limit", limit)
                .putBoolean("skip_existing", skipExisting)
                .putBoolean("auto_download", autoDownload)
                .apply();
    }

    private void applyPrefsToViews() {
        toggleSkip.setSelected(skipExisting);
        toggleAuto.setSelected(autoDownload);
        adapter.setSkipExisting(skipExisting);
    }

    private void buildChips() {
        chipsContainer.removeAllViews();
        for (int i = 0; i < UiSources.IDS.length; i++) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setClickable(true);
            chip.setFocusable(false);

            ImageView icon = new ImageView(this);
            icon.setImageResource(UiSources.ICONS[i]);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(16), dp(16));
            iconParams.rightMargin = dp(4);
            chip.addView(icon, iconParams);

            TextView label = new TextView(this);
            label.setText(UiSources.SHORTS[i]);
            label.setTextSize(12);
            label.setMaxLines(1);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(getColor(R.color.text_secondary));
            chip.addView(label);

            chip.setTag(R.id.tag_chip_label, label);
            final String id = UiSources.IDS[i];
            chip.setTag(R.id.tag_chip_source, id);
            chip.setOnClickListener(v -> toggleSource(id));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(36), 1f);
            chipsContainer.addView(chip, params);
            if (i < UiSources.IDS.length - 1) {
                chipsContainer.addView(new View(this),
                        new LinearLayout.LayoutParams(dp(6), 1));
            }
        }
        refreshChips();
    }

    private void refreshChips() {
        for (int i = 0; i < chipsContainer.getChildCount(); i++) {
            View child = chipsContainer.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            Object sourceTag = child.getTag(R.id.tag_chip_source);
            if (!(sourceTag instanceof String)) {
                continue;
            }
            String id = (String) sourceTag;
            boolean on = enabledSources.contains(id);
            child.setSelected(on);
            TextView label = (TextView) child.getTag(R.id.tag_chip_label);
            label.setTextColor(on ? getColor(R.color.white) : getColor(R.color.text_secondary));
        }
    }

    private void toggleSource(String id) {
        if (enabledSources.contains(id)) {
            if (enabledSources.size() == 1) {
                toast("至少保留一个音乐源");
                return;
            }
            enabledSources.remove(id);
        } else {
            enabledSources.add(id);
            sortSources(enabledSources);
        }
        refreshChips();
        savePrefs();
    }

    private void changeLimit(int delta) {
        int next = clamp(limit + delta, MIN_LIMIT, MAX_LIMIT);
        if (next == limit) {
            return;
        }
        limit = next;
        refreshLimit();
        savePrefs();
    }

    private void refreshLimit() {
        limitValue.setText(String.valueOf(limit));
        limitMinus.setEnabled(limit > MIN_LIMIT);
        limitMinus.setAlpha(limit > MIN_LIMIT ? 1f : 0.35f);
        limitPlus.setEnabled(limit < MAX_LIMIT);
        limitPlus.setAlpha(limit < MAX_LIMIT ? 1f : 0.35f);
    }

    private void refreshFolder() {
        folderPath.setText(Storage.folderLabel(this));
        folderReset.setVisibility(Storage.hasCustomFolder(this) ? View.VISIBLE : View.GONE);
    }

    /* ---------- 搜索 ---------- */

    private void startSearch() {
        if (searching) {
            return;
        }
        String value = keywordInput.getText().toString().trim();
        if (value.isEmpty()) {
            toast("请输入关键词");
            keywordInput.requestFocus();
            return;
        }
        if (enabledSources.isEmpty()) {
            toast("请至少选择一个音乐源");
            return;
        }
        if (!TextUtils.isEmpty(previewKey)) {
            clearPreview(true);
        }

        keyword = value;
        searched = false;
        errors.clear();
        bySource.clear();
        songs.clear();
        selected.clear();
        searching = true;

        adapter.setSearching(true);
        adapter.setEmptyState("正在搜索…", "正在从多个音乐源获取结果");
        adapter.notifyDataSetChanged();
        resultCount.setText("搜索中");
        updateDock();
        updateSearchUi();
        hideKeyboard();
        reloadExisting();

        searchManager.search(keyword, new ArrayList<>(enabledSources), limit,
                new SearchManager.Callback() {
                    @Override
                    public void onSourceResults(String sourceId, List<Song> result) {
                        if (!searching) {
                            return;
                        }
                        bySource.put(sourceId, result == null ? new ArrayList<>() : result);
                        rebuildSongs();
                    }

                    @Override
                    public void onSourceError(String sourceId, String message) {
                        if (!searching) {
                            return;
                        }
                        String shortName = UiSources.shortOf(sourceId);
                        errors.add(shortName + "：" + message);
                        toast(shortName + " 搜索失败");
                    }

                    @Override
                    public void onFinished() {
                        finishSearch();
                    }
                });
    }

    private void rebuildSongs() {
        songs.clear();
        for (String id : UiSources.IDS) {
            List<Song> list = bySource.get(id);
            if (list != null) {
                songs.addAll(list);
            }
        }
        adapter.notifyDataSetChanged();
        updateResultCount();
        updateDock();
    }

    private void finishSearch() {
        searching = false;
        searched = true;
        adapter.setSearching(false);
        updateSearchUi();

        if (songs.isEmpty()) {
            if (!errors.isEmpty()) {
                adapter.setEmptyState("搜索失败", errors.get(0));
                toast("搜索失败：" + errors.get(0));
            } else {
                adapter.setEmptyState("没有找到相关歌曲", "换个关键词，或勾选更多音乐源再试试");
                toast("没有找到相关歌曲");
            }
            adapter.notifyDataSetChanged();
        } else {
            toast("找到 " + songs.size() + " 首歌曲");
            if (autoDownload) {
                autoSelectAndDownload();
            }
        }
        updateResultCount();
        updateDock();
    }

    private void autoSelectAndDownload() {
        List<Song> selectable = selectableSongs();
        if (selectable.isEmpty()) {
            toast("所有歌曲都已下载过");
            return;
        }
        selected.clear();
        for (Song song : selectable) {
            selected.add(song.key());
        }
        adapter.notifyDataSetChanged();
        updateDock();
        startDownload();
    }

    private void updateSearchUi() {
        searchBtn.setClickable(!searching);
        searchBtn.setAlpha(searching ? 0.55f : 1f);
        searchIcon.setVisibility(searching ? View.GONE : View.VISIBLE);
        searchLoading.setVisibility(searching ? View.VISIBLE : View.GONE);
    }

    private void updateResultCount() {
        if (!songs.isEmpty()) {
            resultCount.setText(songs.size() + " 首");
        } else if (searching) {
            resultCount.setText("搜索中");
        } else {
            resultCount.setText("");
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(keywordInput.getWindowToken(), 0);
        }
        keywordInput.clearFocus();
    }

    /* ---------- 结果列表与选择 ---------- */

    private List<Song> selectableSongs() {
        List<Song> result = new ArrayList<>();
        for (Song song : songs) {
            if (!adapter.isExisting(song)) {
                result.add(song);
            }
        }
        return result;
    }

    private void toggleAllSongs() {
        List<Song> selectable = selectableSongs();
        boolean allSelected = !selectable.isEmpty();
        for (Song song : selectable) {
            if (!selected.contains(song.key())) {
                allSelected = false;
                break;
            }
        }
        selected.clear();
        if (!allSelected) {
            for (Song song : selectable) {
                selected.add(song.key());
            }
        }
        adapter.notifyDataSetChanged();
        updateDock();
    }

    private void pruneSelectedExisting() {
        if (!skipExisting) {
            return;
        }
        for (Song song : songs) {
            if (selected.contains(song.key())
                    && existing.contains(Storage.normalize(song.name))) {
                selected.remove(song.key());
            }
        }
    }

    private void updateDock() {
        boolean hasResults = !songs.isEmpty();
        dock.setVisibility(hasResults ? View.VISIBLE : View.GONE);
        if (hasResults) {
            selectedCount.setText(selected.isEmpty() ? "未选择" : "已选 " + selected.size() + " 首");
            totalCount.setText("共 " + songs.size() + " 首");

            List<Song> selectable = selectableSongs();
            boolean allSelected = !selectable.isEmpty();
            for (Song song : selectable) {
                if (!selected.contains(song.key())) {
                    allSelected = false;
                    break;
                }
            }
            toggleAll.setText(allSelected ? "取消全选" : "全选");

            if (downloading) {
                downloadLabel.setText("下载中 " + Math.round(overallPercent) + "%");
            } else {
                downloadLabel.setText("下载");
            }
            boolean enabled = downloading || (!searching && !selected.isEmpty());
            downloadButton.setEnabled(enabled);
        }
        updateOverlayPositions();
    }

    private void updateOverlayPositions() {
        boolean hasDock = dock.getVisibility() == View.VISIBLE;
        boolean hasPreview = previewBar.getVisibility() == View.VISIBLE;
        int bottom;
        if (hasPreview && hasDock) {
            bottom = 220;
        } else if (hasPreview) {
            bottom = 136;
        } else if (hasDock) {
            bottom = 118;
        } else {
            bottom = 16;
        }
        songList.setPadding(dp(16), 0, dp(16), dp(bottom));
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) previewBar.getLayoutParams();
        int margin = hasDock ? 86 : 12;
        if (params.bottomMargin != dp(margin)) {
            params.bottomMargin = dp(margin);
            previewBar.setLayoutParams(params);
        }
    }

    /* ---------- 试听 ---------- */

    private PreviewPlayer previewPlayer() {
        if (previewPlayer == null) {
            previewPlayer = new PreviewPlayer(this, new PreviewPlayer.Listener() {
                @Override
                public void onState(String key, String state, int position, int duration) {
                    runOnUiThread(() -> handlePreviewState(key, state, position, duration));
                }

                @Override
                public void onMessage(String message) {
                    runOnUiThread(() -> toast(message));
                }
            });
        }
        return previewPlayer;
    }

    private void togglePreview(Song song) {
        if (song.key().equals(previewKey)) {
            if ("loading".equals(previewState)) {
                return;
            }
            if (previewPlayer != null) {
                previewPlayer.toggle();
            }
            return;
        }
        previewSong = song;
        previewKey = song.key();
        previewState = "loading";
        previewPosition = 0;
        previewDuration = 0;
        previewCoverUrl = "";
        loadLyrics(song);
        updatePreviewUi();
        previewPlayer().play(song);
    }

    private void loadLyrics(Song song) {
        final int request = ++lyricRequest;
        lyricLoading = true;
        lyricLines.clear();
        setLyricText("歌词加载中…", false);
        final String key = song.key();
        lyricPool.execute(() -> {
            String raw = null;
            try {
                MusicSource source = Sources.get(song.source);
                if (source != null) {
                    raw = source.lyric(song);
                }
            } catch (Exception ignored) {
                // 歌词获取失败时显示暂无歌词
            }
            List<LyricParser.Line> parsed = LyricParser.parse(raw);
            main.post(() -> {
                if (request != lyricRequest || isFinishing() || isDestroyed()) {
                    return;
                }
                if (!key.equals(previewKey)) {
                    return;
                }
                lyricLoading = false;
                lyricLines.clear();
                lyricLines.addAll(parsed);
                if (parsed.isEmpty()) {
                    setLyricText("暂无歌词", false);
                } else {
                    updateLyricLine(previewPosition);
                }
            });
        });
    }

    private void updateLyricLine(int positionMs) {
        if (previewLyric == null || lyricLoading || previewKey.isEmpty()) {
            return;
        }
        if (lyricLines.isEmpty()) {
            setLyricText("暂无歌词", false);
            return;
        }
        String line = LyricParser.lineAt(lyricLines, positionMs);
        setLyricText(line == null ? "♪" : line, true);
    }

    private void setLyricText(String text, boolean active) {
        if (previewLyric == null) {
            return;
        }
        previewLyric.setTextColor(getColor(active ? R.color.accent : R.color.text_tertiary));
        if (!text.equals(previewLyric.getText().toString())) {
            previewLyric.setText(text);
        }
    }

    private void handlePreviewState(String key, String state, int position, int duration) {
        if ("stopped".equals(state)) {
            if (key.equals(previewKey)) {
                clearPreview(false);
            }
            return;
        }
        if (!key.equals(previewKey)) {
            return;
        }
        if ("error".equals(state)) {
            toast("播放失败");
            clearPreview(false);
            return;
        }
        if ("ended".equals(state)) {
            toast("播放结束");
            clearPreview(false);
            return;
        }
        previewState = state;
        previewPosition = position;
        previewDuration = duration;
        updatePreviewUi();
        if ("playing".equals(state) || "paused".equals(state)) {
            updateLyricLine(position);
        }
    }

    private void clearPreview(boolean stopPlayer) {
        lyricRequest++;
        lyricLoading = false;
        lyricLines.clear();
        if (previewLyric != null) {
            previewLyric.setText("");
        }
        previewKey = "";
        previewSong = null;
        previewState = "";
        previewPosition = 0;
        previewDuration = 0;
        previewCoverUrl = "";
        if (stopPlayer && previewPlayer != null) {
            previewPlayer.stop();
        }
        updatePreviewUi();
    }

    private void updatePreviewUi() {
        boolean active = !TextUtils.isEmpty(previewKey);
        previewBar.setVisibility(active ? View.VISIBLE : View.GONE);
        adapter.setPreview(previewKey, previewState);
        if (!active) {
            previewCover.setImageDrawable(null);
            previewTitle.setText("");
            previewSub.setText("");
            previewLyric.setText("");
            previewProgress.setPercent(0f);
            updateOverlayPositions();
            return;
        }

        Song song = previewSong;
        previewTitle.setText(song == null || TextUtils.isEmpty(song.name) ? "未知歌曲" : song.name);
        boolean loading = "loading".equals(previewState);
        String singers = song == null || TextUtils.isEmpty(song.singers) ? "" : song.singers;

        if (loading) {
            previewSub.setText(singers.isEmpty() ? "正在加载" : singers + " · 正在加载");
            previewProgress.setPercent(0f);
            previewToggle.setEnabled(false);
            previewToggle.setAlpha(0.4f);
            previewToggle.setImageResource(R.drawable.ic_play);
        } else {
            int seconds = previewDuration > 0 ? Math.round(previewDuration / 1000f) : 0;
            previewSub.setText(singers.isEmpty()
                    ? SongAdapter.formatDuration(seconds)
                    : singers + " · " + SongAdapter.formatDuration(seconds));
            float progress = previewDuration > 0
                    ? (float) previewPosition * 100f / previewDuration : 0f;
            previewProgress.setPercent(Math.max(0f, Math.min(100f, progress)));
            previewToggle.setEnabled(true);
            previewToggle.setAlpha(1f);
            previewToggle.setImageResource("playing".equals(previewState)
                    ? R.drawable.ic_pause : R.drawable.ic_play);
        }

        String cover = song == null || song.cover == null ? "" : song.cover;
        if (!cover.equals(previewCoverUrl)) {
            previewCoverUrl = cover;
            ImageLoader.get().load(cover, previewCover);
        }
        updateOverlayPositions();
    }

    /* ---------- 下载 ---------- */

    private void startDownload() {
        if (downloading) {
            showDownloadSheet();
            return;
        }
        final List<Song> items = new ArrayList<>();
        for (Song song : songs) {
            if (selected.contains(song.key())) {
                items.add(song);
            }
        }
        if (items.isEmpty()) {
            toast("请先选择歌曲");
            return;
        }

        main.removeCallbacks(closeSheetRunnable);
        downloadItems.clear();
        for (Song song : items) {
            downloadItems.add(new DownloadAdapter.Item(song));
        }
        downloading = true;
        overallPercent = 0;
        statsFinished = 0;
        statsSuccess = 0;
        statsSkipped = 0;
        statsFailed = 0;

        ensureDownloadDialog();
        downloadAdapter.setItems(downloadItems);
        sheetSummary.setText("0 / " + items.size());
        sheetProgress.setPercent(0f);
        sheetCancel.setEnabled(true);
        sheetCancel.setAlpha(1f);
        showDownloadSheet();
        updateDock();

        if (Storage.needsLegacyPermission()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadSongs = items;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        } else {
            startDownloader(items);
        }
    }

    private void startDownloader(List<Song> items) {
        if (downloader != null) {
            downloader.cancel();
        }
        downloader = new Downloader(this, items, skipExisting, new Downloader.Callback() {
            @Override
            public void onStatus(int index, String status) {
                postUi(() -> downloadAdapter.setStatus(index, status));
            }

            @Override
            public void onProgress(int index, int percent) {
                postUi(() -> {
                    downloadAdapter.setProgress(index, percent);
                    updateOverallProgress();
                });
            }

            @Override
            public void onResult(int index, boolean ok, boolean skipped, String message) {
                postUi(() -> handleDownloadResult(index, ok, skipped, message));
            }

            @Override
            public void onFinished(boolean cancelled, int success, int skipped, int failed) {
                postUi(() -> handleDownloadFinished(cancelled, success, skipped, failed));
            }
        });
        downloader.start();
    }

    private void handleDownloadResult(int index, boolean ok, boolean skipped, String message) {
        statsFinished++;
        if (skipped) {
            statsSkipped++;
        } else if (ok) {
            statsSuccess++;
        } else {
            statsFailed++;
        }
        downloadAdapter.setResult(index, ok, skipped, message);
        sheetSummary.setText(statsFinished + " / " + downloadItems.size());
        updateOverallProgress();
    }

    private void updateOverallProgress() {
        double total = 0;
        for (DownloadAdapter.Item item : downloadItems) {
            total += item.percent;
        }
        overallPercent = downloadItems.isEmpty() ? 0 : total / downloadItems.size();
        sheetProgress.setPercent((float) overallPercent);
        if (downloading) {
            updateDock();
        }
    }

    private void handleDownloadFinished(boolean cancelled, int success, int skipped, int failed) {
        downloading = false;
        pendingDownloadSongs = null;
        sheetCancel.setEnabled(false);
        sheetCancel.setAlpha(0.5f);

        if (success + skipped + failed == 0) {
            sheetSummary.setText(cancelled ? "已取消" : "未开始下载");
        } else {
            sheetSummary.setText((cancelled ? "已取消 · " : "完成 · ")
                    + "成功 " + success + " · 跳过 " + skipped + " · 失败 " + failed);
        }

        if (!cancelled) {
            if (failed > 0) {
                toast("下载完成，" + failed + " 首失败");
            } else if (success > 0) {
                toast("已保存 " + success + " 首歌曲");
            } else if (skipped > 0) {
                toast("所选歌曲都已存在");
            }
        }
        reloadExisting();
        updateDock();
        if (!cancelled && failed == 0 && !downloadItems.isEmpty()) {
            main.removeCallbacks(closeSheetRunnable);
            main.postDelayed(closeSheetRunnable, 1800);
        }
    }

    private void ensureDownloadDialog() {
        if (downloadDialog != null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.dialog_download, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.setCanceledOnTouchOutside(true);

        sheetSummary = content.findViewById(R.id.sheet_summary);
        sheetCancel = content.findViewById(R.id.sheet_cancel);
        sheetProgress = content.findViewById(R.id.sheet_progress);
        ListView dlList = content.findViewById(R.id.dl_list);
        ImageView sheetClose = content.findViewById(R.id.sheet_close);

        downloadAdapter = new DownloadAdapter(this);
        dlList.setAdapter(downloadAdapter);

        sheetClose.setOnClickListener(v -> dialog.dismiss());
        sheetCancel.setOnClickListener(v -> {
            if (downloader != null) {
                downloader.cancel();
            }
            sheetCancel.setEnabled(false);
            sheetCancel.setAlpha(0.5f);
            sheetSummary.setText("正在取消…");
        });
        downloadDialog = dialog;
    }

    private void showDownloadSheet() {
        ensureDownloadDialog();
        if (downloadDialog.isShowing()) {
            return;
        }
        downloadDialog.show();
        Window window = downloadDialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int maxHeight = (int) (metrics.heightPixels * 0.78f);
            int rows = Math.max(1, downloadItems.size());
            int desired = dp(165) + rows * dp(80);
            int height = Math.max(dp(245), Math.min(maxHeight, desired));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
            window.setGravity(Gravity.BOTTOM);
        }
    }

    private void postUi(Runnable action) {
        main.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }

    /* ---------- 已存在歌曲 ---------- */

    private void reloadExisting() {
        if (metaPool.isShutdown()) {
            return;
        }
        metaPool.execute(() -> {
            Set<String> names;
            try {
                names = Storage.existingNames(this);
            } catch (Exception e) {
                names = new HashSet<>();
            }
            Set<String> loaded = names;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                existing.clear();
                existing.addAll(loaded);
                pruneSelectedExisting();
                adapter.notifyDataSetChanged();
                updateDock();
            });
        });
    }

    /* ---------- 保存目录 ---------- */

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            Uri initial = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Music");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        } catch (Exception ignored) {
            // 某些系统不支持初始位置
        }
        try {
            startActivityForResult(intent, REQUEST_FOLDER);
        } catch (Exception e) {
            toast("系统没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri tree = data.getData();
        if (tree == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Storage.setCustomTree(this, tree);
            refreshFolder();
            reloadExisting();
            toast("保存目录已更新");
        } catch (Exception e) {
            toast("无法获取该目录的写入权限");
        }
    }

    /* ---------- 权限 ---------- */

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        List<Song> pending = pendingDownloadSongs;
        pendingDownloadSongs = null;
        if (granted && pending != null) {
            startDownloader(pending);
        } else {
            toast("需要存储权限才能保存歌曲");
            handleDownloadFinished(false, 0, 0, 0);
        }
    }

    /* ---------- 生命周期与返回键 ---------- */

    @Override
    public void onBackPressed() {
        if (!TextUtils.isEmpty(previewKey)) {
            clearPreview(true);
            return;
        }
        if (!selected.isEmpty()) {
            selected.clear();
            adapter.notifyDataSetChanged();
            updateDock();
            toast("已取消选择");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        if (!TextUtils.isEmpty(previewKey) || previewPlayer != null) {
            clearPreview(true);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(closeSheetRunnable);
        searchManager.cancel();
        metaPool.shutdownNow();
        lyricPool.shutdownNow();
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        if (downloadDialog != null) {
            downloadDialog.dismiss();
            downloadDialog = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isNightMode() != lastNightMode) {
            recreate();
            return;
        }
        applySystemBarIcons();
    }

    /* ---------- 系统栏与工具 ---------- */

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applySystemBarIcons() {
        boolean night = isNightMode();
        getWindow().setStatusBarColor(getColor(R.color.app_bg));
        getWindow().setNavigationBarColor(getColor(R.color.app_bg));
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (night) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
        }
    }

    private GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void toast(String message) {
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean knownSource(String id) {
        for (String known : UiSources.IDS) {
            if (known.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static void sortSources(List<String> sources) {
        sources.sort((a, b) -> indexOfSource(a) - indexOfSource(b));
    }

    private static int indexOfSource(String id) {
        for (int i = 0; i < UiSources.IDS.length; i++) {
            if (UiSources.IDS[i].equals(id)) {
                return i;
            }
        }
        return UiSources.IDS.length;
    }
}
