package com.musicdl.android.util;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Android 15+ 强制边到边时，为根布局补上系统栏安全间距。 */
public final class WindowInsetsUtils {

    private WindowInsetsUtils() {
    }

    public static void applyEdgeToEdge(Activity activity, View root) {
        applyEdgeToEdge(activity, root, root);
    }

    public static void applyEdgeToEdge(Activity activity, View root, View content) {
        if (root == null || content == null || Build.VERSION.SDK_INT < 35) {
            return;
        }
        Api35.apply(activity, root, content);
    }

    @android.annotation.TargetApi(35)
    private static final class Api35 {
        static void apply(Activity activity, View root, View content) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
    }
}
