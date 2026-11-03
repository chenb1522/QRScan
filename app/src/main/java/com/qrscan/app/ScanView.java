package com.qrscan.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 取景遮罩层：暗色挖孔 + 四角标 + 扫描线 + 提示语。 */
public class ScanView extends View {

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path maskPath = new Path();
    private final RectF frame = new RectF();

    private ValueAnimator animator;
    private float progress;
    private boolean lineVisible = true;
    private String hint = "";
    private int lastPadTop = -1;
    private int lastPadBottom = -1;
    private int lastWidth = -1;
    private int lastHeight = -1;

    private final float density;

    public ScanView(Context c) {
        this(c, null);
    }

    public ScanView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;

        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(0xB3000000);

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setColor(Color.WHITE);
        cornerPaint.setStrokeWidth(dp(3.2f));
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setStrokeJoin(Paint.Join.ROUND);

        linePaint.setStyle(Paint.Style.FILL);

        glowPaint.setStyle(Paint.Style.FILL);

        hintPaint.setColor(0xCCFFFFFF);
        hintPaint.setTextSize(dp(12.5f));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hintPaint.setAntiAlias(true);

        hint = c.getString(R.string.hint);
    }

    public void setHint(String text) {
        hint = text == null ? "" : text;
        invalidate();
    }

    public RectF getFrameRect() {
        return new RectF(frame);
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutFrame(w, h);
    }

    private void layoutFrame(int w, int h) {
        float top = dp(126) + getPaddingTop();
        float bottom = h - dp(176) - getPaddingBottom();
        float availH = Math.max(dp(180), bottom - top);
        float size = Math.min(w * 0.74f, availH * 0.94f);
        size = Math.max(size, dp(150));
        if (size > w * 0.92f) {
            size = w * 0.92f;
        }
        float left = (w - size) / 2f;
        float fTop = top + (availH - size) / 2f;
        frame.set(left, fTop, left + size, fTop + size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (frame.isEmpty() || lastPadTop != getPaddingTop()
                || lastPadBottom != getPaddingBottom() || lastWidth != w || lastHeight != h) {
            lastPadTop = getPaddingTop();
            lastPadBottom = getPaddingBottom();
            lastWidth = w;
            lastHeight = h;
            layoutFrame(w, h);
        }

        // 挖孔遮罩
        maskPath.reset();
        maskPath.setFillType(Path.FillType.EVEN_ODD);
        maskPath.addRect(0, 0, w, h, Path.Direction.CW);
        float r = dp(20);
        maskPath.addRoundRect(frame, r, r, Path.Direction.CW);
        canvas.drawPath(maskPath, maskPaint);

        // 四角标
        float armLen = Math.min(frame.width(), frame.height()) * 0.16f;
        armLen = Math.max(armLen, dp(20));
        drawCorner(canvas, frame.left, frame.top, 1, 1, armLen);
        drawCorner(canvas, frame.right, frame.top, -1, 1, armLen);
        drawCorner(canvas, frame.left, frame.bottom, 1, -1, armLen);
        drawCorner(canvas, frame.right, frame.bottom, -1, -1, armLen);

        // 扫描线
        if (lineVisible) {
            float y = frame.top + frame.height() * progress;
            float bandH = dp(2.6f);
            linePaint.setShader(new LinearGradient(frame.left, 0, frame.right, 0,
                    new int[]{0x004ADE80, 0xFF4ADE80, 0x004ADE80},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(frame.left + dp(2), y - bandH / 2f,
                    frame.right - dp(2), y + bandH / 2f, linePaint);

            glowPaint.setShader(new LinearGradient(0, y - dp(22), 0, y + dp(22),
                    new int[]{0x004ADE80, 0x334ADE80, 0x004ADE80},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(frame.left + dp(2), y - dp(22),
                    frame.right - dp(2), y + dp(22), glowPaint);
        }

        // 提示语
        if (hint != null && hint.length() > 0) {
            canvas.drawText(hint, frame.centerX(), frame.bottom + dp(34), hintPaint);
        }
    }

    private void drawCorner(Canvas c, float x, float y, int dx, int dy, float len) {
        float rr = dp(20);
        Path p = new Path();
        p.moveTo(x + dx * (rr + len), y);
        p.lineTo(x + dx * rr, y);
        p.quadTo(x, y, x, y + dy * rr);
        p.lineTo(x, y + dy * (rr + len));
        c.drawPath(p, cornerPaint);
    }

    public void startScanLine() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        lineVisible = true;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2400);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                progress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    public void stopScanLine() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        lineVisible = false;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopScanLine();
        super.onDetachedFromWindow();
    }
}
