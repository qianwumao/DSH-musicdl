package com.musicdl.android.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.musicdl.android.R;

/** 进度条：小鲸鱼跟随进度头部从左跑到右。 */
public class WhaleProgressView extends View {

    public interface OnSeekListener {
        void onSeekStart();

        void onSeekChanged(float percent);

        void onSeekEnd(float percent);
    }

    private static Bitmap whale;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whalePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF fillRect = new RectF();
    private final RectF whaleRect = new RectF();
    private final float density;
    private final float barHeight;
    private float percent;
    private int trackColor;
    private int fillStartColor;
    private int fillEndColor;
    private OnSeekListener seekListener;
    private boolean dragging;

    public WhaleProgressView(Context context) {
        this(context, null);
    }

    public WhaleProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WhaleProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        barHeight = dp(3);
        trackColor = context.getColor(R.color.surface2);
        fillStartColor = context.getColor(R.color.accent);
        fillEndColor = context.getColor(R.color.accent2);
        trackPaint.setColor(trackColor);
        if (whale == null) {
            whale = BitmapFactory.decodeResource(context.getResources(),
                    R.drawable.whale_runner);
        }
    }

    /** 自定义进度条轨道与填充颜色。 */
    public void setBarColors(int track, int fillStart, int fillEnd) {
        trackColor = track;
        fillStartColor = fillStart;
        fillEndColor = fillEnd;
        trackPaint.setColor(trackColor);
        updateFillShader();
        invalidate();
    }

    private void updateFillShader() {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        fillPaint.setShader(new LinearGradient(0, 0, width, 0,
                fillStartColor, fillEndColor, Shader.TileMode.CLAMP));
    }

    /** 设置 0 到 100 的进度百分比。 */
    public void setPercent(float value) {
        float next = Math.max(0f, Math.min(100f, value));
        if (Math.abs(next - percent) < 0.05f) {
            return;
        }
        percent = next;
        invalidate();
    }

    public float getPercent() {
        return percent;
    }

    /** 进度条轨道相对视图左右边缘的内缩量，用于让起止时间对齐轨道两端。 */
    public float getTrackInset() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return 0f;
        }
        float whaleHeight = Math.min(dp(22), h - dp(1));
        if (whaleHeight <= 0) {
            return 0f;
        }
        float ratio = whale == null ? 1f : (float) whale.getWidth() / whale.getHeight();
        float whaleWidth = whaleHeight * ratio;
        float maxWhaleWidth = w * 0.42f;
        if (whaleWidth > maxWhaleWidth) {
            whaleWidth = maxWhaleWidth;
        }
        return whaleWidth / 2f + dp(0.5f);
    }

    public void setOnSeekListener(OnSeekListener listener) {
        seekListener = listener;
        setClickable(listener != null);
        if (listener == null) {
            dragging = false;
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (seekListener == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                seekListener.onSeekStart();
                updatePercentFromX(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updatePercentFromX(event.getX());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    updatePercentFromX(event.getX());
                    dragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    seekListener.onSeekEnd(percent);
                    performClick();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    seekListener.onSeekEnd(percent);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void updatePercentFromX(float x) {
        if (getWidth() <= 0) {
            return;
        }
        float next = Math.max(0f, Math.min(100f, x * 100f / getWidth()));
        if (Math.abs(next - percent) > 0.01f) {
            percent = next;
            invalidate();
            seekListener.onSeekChanged(percent);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fillPaint.setShader(new LinearGradient(0, 0, Math.max(1, w), 0,
                fillStartColor, fillEndColor, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float whaleHeight = Math.min(dp(22), h - dp(1));
        if (whaleHeight <= 0) {
            return;
        }
        float ratio = whale == null ? 1f : (float) whale.getWidth() / whale.getHeight();
        float whaleWidth = whaleHeight * ratio;
        float maxWhaleWidth = w * 0.42f;
        if (whaleWidth > maxWhaleWidth) {
            whaleWidth = maxWhaleWidth;
            whaleHeight = whaleWidth / ratio;
        }

        float inset = whaleWidth / 2f + dp(0.5f);
        float left = inset;
        float right = w - inset;
        if (right <= left) {
            return;
        }

        float barTop = (h - barHeight) / 2f;
        float radius = barHeight / 2f;
        trackRect.set(left, barTop, right, barTop + barHeight);
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        float head = left + (right - left) * percent / 100f;
        if (head > left) {
            float fillRight = Math.max(head, left + barHeight);
            if (fillRight > right) {
                fillRight = right;
            }
            fillRect.set(left, barTop, fillRight, barTop + barHeight);
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint);
        }

        if (whale != null) {
            float whaleLeft = (w - whaleWidth) * percent / 100f;
            float whaleTop = (h - whaleHeight) / 2f;
            whaleRect.set(whaleLeft, whaleTop, whaleLeft + whaleWidth, whaleTop + whaleHeight);
            canvas.drawBitmap(whale, null, whaleRect, whalePaint);
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
