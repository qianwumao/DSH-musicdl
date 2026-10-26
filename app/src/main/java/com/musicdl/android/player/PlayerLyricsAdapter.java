package com.musicdl.android.player;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.musicdl.android.R;
import com.musicdl.android.util.LyricParser;

import java.util.List;

/** 全屏歌词列表适配器。 */
public class PlayerLyricsAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<LyricParser.Line> lines;
    private int currentIndex = -1;

    public PlayerLyricsAdapter(Context context, List<LyricParser.Line> lines) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.lines = lines;
    }

    public void setCurrentIndex(int index) {
        if (index != currentIndex) {
            currentIndex = index;
            notifyDataSetChanged();
        }
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    @Override
    public int getCount() {
        return lines.size();
    }

    @Override
    public Object getItem(int position) {
        return lines.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView text;
        if (convertView == null) {
            text = (TextView) inflater.inflate(R.layout.item_player_lyric, parent, false);
        } else {
            text = (TextView) convertView;
        }
        boolean active = position == currentIndex;
        text.setText(lines.get(position).text);
        text.setTextColor(active ? 0xFFFFFFFF : 0x66FFFFFF);
        text.setTextSize(active ? 19 : 15);
        text.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        text.setBackground(null);
        return text;
    }
}
