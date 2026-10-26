package com.musicdl.android;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.musicdl.android.model.Song;
import com.musicdl.android.util.ImageLoader;
import com.musicdl.android.view.WhaleProgressView;

import java.util.ArrayList;
import java.util.List;

/** 下载面板列表适配器。 */
public class DownloadAdapter extends BaseAdapter {

    public static class Item {
        public final Song song;
        public String status = "等待中";
        public String stateText = "排队";
        public int percent;
        public int state; // 0 等待/下载中，1 完成，2 跳过，3 失败

        public Item(Song song) {
            this.song = song;
        }
    }

    private final Context context;
    private final LayoutInflater inflater;
    private List<Item> items = new ArrayList<>();

    public DownloadAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<Item> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    public void setStatus(int index, String text) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        Item item = items.get(index);
        if (item.state == 0) {
            item.status = text;
            item.stateText = "下载中";
            notifyDataSetChanged();
        }
    }

    public void setProgress(int index, int percent) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        Item item = items.get(index);
        if (percent >= 0) {
            item.percent = Math.min(100, percent);
            if (item.state == 0) {
                item.status = "正在下载 " + item.percent + "%";
                item.stateText = "下载中";
            }
            notifyDataSetChanged();
        }
    }

    public void setResult(int index, boolean ok, boolean skipped, String message) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        Item item = items.get(index);
        item.percent = 100;
        item.status = message == null || message.isEmpty()
                ? (ok ? "已保存" : "下载失败") : message;
        if (skipped) {
            item.state = 2;
            item.stateText = "跳过";
        } else if (ok) {
            item.state = 1;
            item.stateText = "完成";
        } else {
            item.state = 3;
            item.stateText = "失败";
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_download, parent, false);
            holder = new Holder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        Item item = items.get(position);
        Song song = item.song;
        holder.name.setText(song.name == null || song.name.isEmpty() ? "未知歌曲" : song.name);
        holder.status.setText(item.status);
        holder.progress.setPercent(item.percent);
        holder.state.setText(item.stateText);

        int statusColor;
        int stateColor;
        switch (item.state) {
            case 1:
                statusColor = context.getColor(R.color.text_secondary);
                stateColor = context.getColor(R.color.green);
                break;
            case 2:
                statusColor = context.getColor(R.color.text_secondary);
                stateColor = context.getColor(R.color.text_tertiary);
                break;
            case 3:
                statusColor = context.getColor(R.color.red);
                stateColor = context.getColor(R.color.red);
                break;
            default:
                statusColor = context.getColor(R.color.text_secondary);
                stateColor = context.getColor(R.color.text_tertiary);
                break;
        }
        holder.status.setTextColor(statusColor);
        holder.state.setTextColor(stateColor);

        ImageLoader.get().load(song.cover, holder.cover);
        return convertView;
    }

    static class Holder {
        final ImageView cover;
        final TextView name;
        final TextView status;
        final WhaleProgressView progress;
        final TextView state;

        Holder(View view) {
            cover = view.findViewById(R.id.dl_cover);
            name = view.findViewById(R.id.dl_name);
            status = view.findViewById(R.id.dl_status);
            progress = view.findViewById(R.id.dl_progress);
            state = view.findViewById(R.id.dl_state);
        }
    }
}
