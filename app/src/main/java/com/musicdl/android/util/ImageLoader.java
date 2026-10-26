package com.musicdl.android.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import com.musicdl.android.R;
import com.musicdl.android.net.Http;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 封面图片加载：内存缓存 + 后台线程，避免列表滚动时重复下载。 */
public final class ImageLoader {

    private static final ImageLoader INSTANCE = new ImageLoader();

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> failed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ImageLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public static ImageLoader get() {
        return INSTANCE;
    }

    public void load(String url, ImageView view) {
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            view.setTag(R.id.tag_image_url, null);
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setTag(R.id.tag_image_url, url);
            view.setImageBitmap(cached);
            return;
        }
        if (failed.contains(url)) {
            view.setTag(R.id.tag_image_url, null);
            view.setImageDrawable(null);
            return;
        }
        view.setTag(R.id.tag_image_url, url);
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap != null) {
                cache.put(url, bitmap);
            } else {
                failed.add(url);
            }
            main.post(() -> {
                if (bitmap != null && url.equals(view.getTag(R.id.tag_image_url))) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap download(String url) {
        try {
            byte[] data = Http.getBytes(url, Http.headers());
            if (data.length == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 160) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception e) {
            return null;
        }
    }
}
