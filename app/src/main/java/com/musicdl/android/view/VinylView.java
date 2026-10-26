package com.musicdl.android.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.musicdl.android.R;

/** 黑胶唱片视图：唱片纹路、旋转的封面标签和唱臂。 */
public class VinylView extends View {

    private static final float DEGREES_PER_SECOND = 15f;
    private static final float ARM_REST_DEGREES = 66f;
    private static final float ARM_PLAY_DEGREES = 83.5f;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pivotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect coverSrc = new Rect();
    private final RectF coverDst = new RectF();
    private final Path coverClip = new Path();
    private final Path armPath = new Path();
    private final float density;

    private Bitmap cover;
    private Bitmap fallback;
    private float angle;
    private boolean playing;
    private long lastTick;
    private float armValue;
    private ValueAnimator armAnimator;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!playing) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (lastTick == 0) {
                lastTick = now;
            }
            float seconds = (now - lastTick) / 1000f;
            lastTick = now;
            angle = (angle + seconds * DEGREES_PER_SECOND) % 360f;
            invalidate();
            postOnAnimation(this);
        }
    };

    public VinylView(Context context) {
        this(context, null);
    }

    public VinylView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VinylView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        coverPaint.setFilterBitmap(true);
        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setStrokeWidth(dp(0.7f));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1f));
        ringPaint.setColor(0x40FFFFFF);
        armPaint.setStyle(Paint.Style.STROKE);
        armPaint.setStrokeCap(Paint.Cap.ROUND);
        armPaint.setStrokeWidth(dp(4f));
        armPaint.setColor(0xFFFFFFFF);
        pivotPaint.setColor(0xFFFFFFFF);
        holePaint.setColor(0xFF101114);
        shadowPaint.setColor(0x55000000);
        fallback = BitmapFactory.decodeResource(getResources(), R.drawable.app_logo);
        setClickable(true);
        setContentDescription("黑胶唱片，点击查看歌词");
    }

    public void setCover(Bitmap bitmap) {
        cover = bitmap;
        invalidate();
    }

    public void setPlaying(boolean value) {
        if (playing == value) {
            return;
        }
        playing = value;
        removeCallbacks(ticker);
        if (playing) {
            lastTick = 0;
            postOnAnimation(ticker);
        }
        animateArm(playing ? 1f : 0f);
        invalidate();
    }

    /** 唱针从唱片外摆到黑边上方（播放）或退回唱片外（暂停）。 */
    private void animateArm(float target) {
        if (armAnimator != null) {
            armAnimator.cancel();
        }
        armAnimator = ValueAnimator.ofFloat(armValue, target);
        armAnimator.setDuration(520);
        armAnimator.setInterpolator(new DecelerateInterpolator());
        armAnimator.addUpdateListener(animation -> {
            armValue = (float) animation.getAnimatedValue();
            invalidate();
        });
        armAnimator.start();
    }

    public boolean isPlaying() {
        return playing;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (playing) {
            lastTick = 0;
            removeCallbacks(ticker);
            postOnAnimation(ticker);
            animateArm(1f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        if (armAnimator != null) {
            armAnimator.cancel();
            armAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(width, height) / 2f * 0.846f;

        shadowPaint.setColor(0x4D000000);
        canvas.drawCircle(cx, cy + dp(6), radius * 1.004f, shadowPaint);

        discPaint.setShader(new RadialGradient(cx - radius * 0.18f, cy - radius * 0.2f,
                radius * 1.15f, new int[]{0xFF343434, 0xFF1A1B1C, 0xFF101112},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, discPaint);
        discPaint.setShader(null);

        for (int i = 0; i < 24; i++) {
            float grooveRadius = radius * 0.735f + (radius * 0.925f - radius * 0.735f) * i / 23f;
            groovePaint.setColor(i % 2 == 0 ? 0x16FFFFFF : 0x22000000);
            canvas.drawCircle(cx, cy, grooveRadius, groovePaint);
        }

        float labelRadius = radius * 0.715f;
        coverDst.set(cx - labelRadius, cy - labelRadius, cx + labelRadius, cy + labelRadius);
        Bitmap bitmap = cover;
        if (bitmap == null || bitmap.isRecycled()) {
            bitmap = fallback;
        }
        if (bitmap != null && !bitmap.isRecycled()) {
            int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
            int left = (bitmap.getWidth() - side) / 2;
            int top = (bitmap.getHeight() - side) / 2;
            coverSrc.set(left, top, left + side, top + side);
            canvas.save();
            canvas.rotate(angle, cx, cy);
            coverClip.reset();
            coverClip.addCircle(cx, cy, labelRadius, Path.Direction.CW);
            canvas.clipPath(coverClip);
            canvas.drawBitmap(bitmap, coverSrc, coverDst, coverPaint);
            canvas.restore();
        }

        ringPaint.setColor(0x40FFFFFF);
        canvas.drawCircle(cx, cy, labelRadius + dp(1), ringPaint);
        holePaint.setColor(0xFF111216);
        canvas.drawCircle(cx, cy, dp(3.5f), holePaint);

        drawTonearm(canvas, cx, cy, radius);
    }

    private void drawTonearm(Canvas canvas, float cx, float cy, float radius) {
        float progress = Math.max(0f, Math.min(1f, armValue));
        float pivotX = cx + radius * 0.62f;
        float pivotY = cy - radius * 0.94f;

        // 唱臂保持固定长度，只绕支点旋转：暂停时停在唱片外，播放时落到黑边上。
        float armLength = radius * 1.19f;
        float armAngle = ARM_REST_DEGREES
                + (ARM_PLAY_DEGREES - ARM_REST_DEGREES) * progress;
        float endRad = (float) Math.toRadians(armAngle);
        float controlRad = (float) Math.toRadians(armAngle - 4f);
        float endX = pivotX + (float) Math.cos(endRad) * armLength;
        float endY = pivotY + (float) Math.sin(endRad) * armLength;
        float controlX = pivotX + (float) Math.cos(controlRad) * armLength * 0.62f;
        float controlY = pivotY + (float) Math.sin(controlRad) * armLength * 0.62f;

        armPath.reset();
        armPath.moveTo(pivotX, pivotY);
        armPath.quadTo(controlX, controlY, endX, endY);
        armPaint.setStrokeWidth(dp(4f));
        armPaint.setColor(0xFFFFFFFF);
        canvas.drawPath(armPath, armPaint);

        pivotPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(pivotX, pivotY, dp(6.5f), pivotPaint);
        pivotPaint.setColor(0xFF3A3A3A);
        canvas.drawCircle(pivotX, pivotY, dp(2.4f), pivotPaint);

        float tangentX = endX - controlX;
        float tangentY = endY - controlY;
        float headAngle = (float) Math.toDegrees(Math.atan2(tangentY, tangentX));
        canvas.save();
        canvas.rotate(headAngle, endX, endY);
        RectF head = new RectF(endX - dp(9), endY - dp(4.5f),
                endX + dp(7), endY + dp(4.5f));
        Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headPaint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(head, dp(4), dp(4), headPaint);
        headPaint.setColor(0xFF2A2A2A);
        RectF tip = new RectF(endX + dp(3), endY - dp(2.2f),
                endX + dp(7), endY + dp(2.2f));
        canvas.drawRoundRect(tip, dp(1.5f), dp(1.5f), headPaint);
        canvas.restore();
    }

    private float dp(float value) {
        return value * density;
    }
}
