package com.musicdl.android.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.RemoteViews;

import com.musicdl.android.PlayerActivity;
import com.musicdl.android.R;

/** 桌面播放器组件。 */
public class PlayerWidgetProvider extends AppWidgetProvider {

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, PlayerWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        if (ids.length == 0) {
            return;
        }
        RemoteViews views = buildViews(context);
        for (int id : ids) {
            manager.updateAppWidget(id, views);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        RemoteViews views = buildViews(context);
        for (int id : appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            updateAll(context);
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_player);
        views.setTextViewText(R.id.widget_title, PlayerWidgetState.title);
        views.setTextViewText(R.id.widget_artist, PlayerWidgetState.artist);
        views.setImageViewResource(R.id.widget_toggle,
                PlayerWidgetState.playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play);
        Bitmap cover = PlayerWidgetState.cover;
        if (cover != null) {
            views.setImageViewBitmap(R.id.widget_cover, roundedCover(cover, 160));
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.app_logo);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent openIntent = new Intent(context, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 30, openIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_cover, open);
        views.setOnClickPendingIntent(R.id.widget_title, open);

        views.setOnClickPendingIntent(R.id.widget_prev,
                action(context, PlaybackService.ACTION_PREVIOUS, 31, flags));
        views.setOnClickPendingIntent(R.id.widget_toggle,
                action(context, PlaybackService.ACTION_TOGGLE, 32, flags));
        views.setOnClickPendingIntent(R.id.widget_next,
                action(context, PlaybackService.ACTION_NEXT, 33, flags));
        return views;
    }

    private static PendingIntent action(Context context, String action, int requestCode, int flags) {
        Intent intent = new Intent(context, PlaybackService.class).setAction(action);
        return PendingIntent.getForegroundService(context, requestCode, intent, flags);
    }

    private static Bitmap roundedCover(Bitmap source, int size) {
        int width = source.getWidth();
        int height = source.getHeight();
        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;
        Bitmap square = Bitmap.createBitmap(source, left, top, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = size * 0.16f;
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        if (scaled != square) {
            scaled.recycle();
        }
        if (square != source) {
            square.recycle();
        }
        return output;
    }
}
