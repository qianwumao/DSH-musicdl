package com.musicdl.android;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.musicdl.android.model.Song;
import com.musicdl.android.util.ImageLoader;
import com.musicdl.android.util.Storage;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 搜索结果列表适配器：封面试听、选择状态与已存在标记。 */
public class SongAdapter extends BaseAdapter {

    public interface Listener {
        void onSongClick(Song song);

        void onPreviewClick(Song song);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Song> songs;
    private final Set<String> selected;
    private final Set<String> existing;
    private final Listener listener;

    private boolean skipExisting = true;
    private boolean searching;
    private String emptyTitle = "搜索你喜欢的歌曲";
    private String emptyDesc = "支持酷我、酷狗、网易云与 QQ 音乐";
    private String previewKey = "";
    private String previewState = "";

    public SongAdapter(Context context, List<Song> songs, Set<String> selected,
                       Set<String> existing, Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.songs = songs;
        this.selected = selected;
        this.existing = existing;
        this.listener = listener;
    }

    public void setSkipExisting(boolean skipExisting) {
        this.skipExisting = skipExisting;
    }

    public void setSearching(boolean searching) {
        this.searching = searching;
    }

    public void setEmptyState(String title, String desc) {
        this.emptyTitle = title;
        this.emptyDesc = desc;
    }

    public void setPreview(String key, String state) {
        this.previewKey = key == null ? "" : key;
        this.previewState = state == null ? "" : state;
        notifyDataSetChanged();
    }

    public boolean isExisting(Song song) {
        return skipExisting && existing.contains(Storage.normalize(song.name));
    }

    @Override
    public int getCount() {
        return songs.isEmpty() ? 1 : songs.size();
    }

    @Override
    public Object getItem(int position) {
        return songs.isEmpty() ? null : songs.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return songs.isEmpty() ? 1 : 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (songs.isEmpty()) {
            return emptyView(convertView, parent);
        }
        SongHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof SongHolder)) {
            convertView = inflater.inflate(R.layout.item_song, parent, false);
            holder = new SongHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (SongHolder) convertView.getTag();
        }
        bindSong(holder, songs.get(position));
        return convertView;
    }

    private View emptyView(View convertView, ViewGroup parent) {
        EmptyHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof EmptyHolder)) {
            convertView = inflater.inflate(R.layout.item_empty, parent, false);
            holder = new EmptyHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (EmptyHolder) convertView.getTag();
        }
        holder.title.setText(emptyTitle);
        holder.desc.setText(emptyDesc);
        return convertView;
    }

    private void bindSong(SongHolder holder, Song song) {
        boolean exists = isExisting(song);
        boolean selectedNow = !exists && selected.contains(song.key());
        String key = song.key();
        boolean previewing = key.equals(previewKey);
        boolean loading = previewing && "loading".equals(previewState);

        holder.row.setBackgroundResource(selectedNow
                ? R.drawable.bg_song_selected : R.drawable.bg_song);
        holder.row.setAlpha(exists ? 0.45f : 1f);
        holder.row.setOnClickListener(v -> listener.onSongClick(song));

        holder.title.setText(song.name == null || song.name.isEmpty() ? "未知歌曲" : song.name);
        String sub = join(song.singers, song.album);
        holder.sub.setText(sub);
        holder.sub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        bindTags(holder.tags, song);

        holder.duration.setText(song.duration > 0 ? formatDuration(song.duration) : "");

        holder.check.setVisibility(exists ? View.GONE : View.VISIBLE);
        holder.check.setBackgroundResource(selectedNow
                ? R.drawable.bg_check_on : R.drawable.bg_check_off);
        holder.checkIcon.setVisibility(selectedNow ? View.VISIBLE : View.GONE);
        holder.existing.setVisibility(exists ? View.VISIBLE : View.GONE);

        holder.cover.setBackgroundResource(previewing
                ? R.drawable.bg_cover_on : R.drawable.bg_cover);
        holder.badge.setBackgroundResource(previewing
                ? R.drawable.bg_play_badge_on : R.drawable.bg_play_badge_off);
        holder.badge.setOnClickListener(v -> listener.onPreviewClick(song));
        holder.playLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        holder.playIcon.setVisibility(loading ? View.GONE : View.VISIBLE);
        holder.playIcon.setImageResource(previewing && "playing".equals(previewState)
                ? R.drawable.ic_pause : R.drawable.ic_play);

        ImageLoader.get().load(song.cover, holder.coverImage);
    }

    private void bindTags(LinearLayout container, Song song) {
        container.removeAllViews();
        if (song.source != null && !song.source.isEmpty()) {
            addTag(container, UiSources.shortOf(song.source),
                    R.drawable.bg_tag_src, R.color.accent);
        }
        if (song.quality != null && !song.quality.isEmpty()) {
            addTag(container, song.quality, R.drawable.bg_tag, R.color.text_secondary);
        }
        if (song.sizeText != null && !song.sizeText.isEmpty()) {
            addTag(container, song.sizeText, R.drawable.bg_tag, R.color.text_secondary);
        }
        if (song.vip) {
            addTag(container, "VIP", R.drawable.bg_tag_vip, R.color.amber);
        }
        container.setVisibility(container.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addTag(LinearLayout container, String text, int background, int color) {
        TextView tag = new TextView(context);
        tag.setText(text);
        tag.setTextSize(10.5f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(context.getColor(color));
        tag.setBackgroundResource(background);
        int paddingH = dp(6);
        int paddingV = dp(1);
        tag.setPadding(paddingH, paddingV, paddingH, paddingV);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(5);
        container.addView(tag, params);
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static String join(String first, String second) {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isEmpty()) {
            sb.append(first);
        }
        if (second != null && !second.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(second);
        }
        return sb.toString();
    }

    static String formatDuration(int seconds) {
        int total = Math.max(0, seconds);
        return String.format(Locale.CHINA, "%d:%02d", total / 60, total % 60);
    }

    static class SongHolder {
        final View row;
        final FrameLayout cover;
        final ImageView coverImage;
        final FrameLayout badge;
        final ImageView playIcon;
        final ProgressBar playLoading;
        final TextView title;
        final TextView sub;
        final LinearLayout tags;
        final TextView duration;
        final FrameLayout check;
        final ImageView checkIcon;
        final TextView existing;

        SongHolder(View view) {
            row = view;
            cover = view.findViewById(R.id.song_cover);
            coverImage = view.findViewById(R.id.cover_image);
            badge = view.findViewById(R.id.play_badge);
            playIcon = view.findViewById(R.id.play_icon);
            playLoading = view.findViewById(R.id.play_loading);
            title = view.findViewById(R.id.song_title);
            sub = view.findViewById(R.id.song_sub);
            tags = view.findViewById(R.id.song_tags);
            duration = view.findViewById(R.id.song_duration);
            check = view.findViewById(R.id.song_check);
            checkIcon = view.findViewById(R.id.check_icon);
            existing = view.findViewById(R.id.song_existing);
        }
    }

    static class EmptyHolder {
        final TextView title;
        final TextView desc;

        EmptyHolder(View view) {
            title = view.findViewById(R.id.empty_title);
            desc = view.findViewById(R.id.empty_desc);
        }
    }
}
