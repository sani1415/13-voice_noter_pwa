package com.voicenoter.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Small animated waveform used while transcription is in progress. */
public class WaveBarsView extends View {
    private static final int BAR_COUNT = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] levels = new float[BAR_COUNT];
    private final float[] targets = new float[BAR_COUNT];
    private ValueAnimator animator;
    private int barColor = 0xFFFFFFFF;

    public WaveBarsView(Context context) {
        super(context);
        init();
    }

    public WaveBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveBarsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < BAR_COUNT; i++) {
            levels[i] = 0.35f;
            targets[i] = 0.55f;
        }
    }

    public void setBarColor(int color) {
        barColor = color;
        invalidate();
    }

    public void startAnimating() {
        stopAnimating();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(180);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            for (int i = 0; i < BAR_COUNT; i++) {
                float diff = targets[i] - levels[i];
                levels[i] += diff * 0.35f;
                if (Math.abs(diff) < 0.04f) {
                    targets[i] = 0.25f + (float) (Math.random() * 0.7f);
                }
            }
            invalidate();
        });
        animator.start();
        setVisibility(VISIBLE);
    }

    public void stopAnimating() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimating();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(barColor);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float gap = w * 0.08f;
        float barW = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        float radius = barW / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barH = Math.max(barW, h * levels[i]);
            float left = i * (barW + gap);
            float top = (h - barH) / 2f;
            rect.set(left, top, left + barW, top + barH);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
