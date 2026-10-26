package com.musicdl.android.player;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.musicdl.android.R;

import java.util.List;
import java.util.Locale;

/** 本地播放列表适配器。 */
public class PlayerTrackAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<LocalTrack> tracks;
    private int currentIndex = -1;

    public PlayerTrackAdapter(Context context, List<LocalTrack> tracks) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.tracks = tracks;
    }

    public void setCurrentIndex(int index) {
        if (index != currentIndex) {
            currentIndex = index;
            notifyDataSetChanged();
        }
    }

    @Override
    public int getCount() {
        return tracks.size();
    }

    @Override
    public Object getItem(int position) {
        return tracks.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_player_track, parent, false);
            holder = new Holder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        LocalTrack track = tracks.get(position);
        boolean active = position == currentIndex;
        convertView.setBackgroundResource(active
                ? R.drawable.bg_song_selected : R.drawable.bg_song_normal);
        holder.title.setText(track.title == null || track.title.isEmpty()
                ? track.fileName : track.title);
        holder.title.setTextColor(context.getColor(
                active ? R.color.accent : R.color.text_primary));
        String sub = track.subtitle();
        holder.sub.setText(sub.isEmpty() ? track.fileName : sub);
        holder.duration.setText(formatDuration(track.durationMs));
        holder.state.setVisibility(active ? View.VISIBLE : View.GONE);
        return convertView;
    }

    static String formatDuration(long millis) {
        int total = (int) Math.max(0, millis / 1000);
        return String.format(Locale.CHINA, "%d:%02d", total / 60, total % 60);
    }

    static class Holder {
        final TextView title;
        final TextView sub;
        final TextView state;
        final TextView duration;

        Holder(View view) {
            title = view.findViewById(R.id.track_title);
            sub = view.findViewById(R.id.track_sub);
            state = view.findViewById(R.id.track_state);
            duration = view.findViewById(R.id.track_duration);
        }
    }
}
