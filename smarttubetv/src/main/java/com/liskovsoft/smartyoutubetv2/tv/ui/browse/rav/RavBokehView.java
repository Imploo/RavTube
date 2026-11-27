package com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Soft drifting bokeh balls over a dark gradient (port of RavLauncher's BokehView).
 * ~20 fps, no blur, pre-built shaders. Paused while the fragment is stopped.
 */
public class RavBokehView extends View {
    private static final int[] PALETTE = {
            0xFFFFB46B, // amber
            0xFFFF7A9C, // rose
            0xFF7FD6D0, // teal
            0xFFB59CFF, // lavender
            0xFF8FB8FF, // soft blue
    };

    private static class Ball {
        float x, y;
        final float r, vx, vy;
        final Paint paint;

        Ball(float x, float y, float r, float vx, float vy, Paint paint) {
            this.x = x; this.y = y; this.r = r; this.vx = vx; this.vy = vy; this.paint = paint;
        }
    }

    private final List<Ball> mBalls = new ArrayList<>();
    private final Paint mBgPaint = new Paint();
    private long mLastFrame;
    private boolean mRunning;

    public RavBokehView(Context context) {
        super(context);
    }

    public void setRunning(boolean running) {
        mRunning = running;
        mLastFrame = SystemClock.uptimeMillis();
        if (running) {
            postInvalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mBgPaint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFF080B18, 0xFF120F26, 0xFF1C0F24}, null, Shader.TileMode.CLAMP));
        mBalls.clear();
        Random rnd = new Random(7);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 16; i++) {
            float r = (40 + rnd.nextFloat() * 130) * d;
            int color = PALETTE[rnd.nextInt(PALETTE.length)];
            float a = 0.06f + rnd.nextFloat() * 0.14f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(0, 0, r,
                    new int[]{withAlpha(color, a), withAlpha(color, a * 0.85f), withAlpha(color, a * 0.45f), Color.TRANSPARENT},
                    new float[]{0f, 0.7f, 0.9f, 1f}, Shader.TileMode.CLAMP));
            float speed = (4 + rnd.nextFloat() * 10) * d; // px per second
            float angle = rnd.nextFloat() * 6.283f;
            mBalls.add(new Ball(rnd.nextFloat() * w, rnd.nextFloat() * h, r,
                    (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed, paint));
        }
    }

    private static int withAlpha(int c, float a) {
        return Color.argb((int) (a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        canvas.drawRect(0, 0, w, h, mBgPaint);
        long now = SystemClock.uptimeMillis();
        float dt = mRunning ? Math.min(200L, Math.max(0L, now - mLastFrame)) / 1000f : 0f;
        mLastFrame = now;
        for (Ball b : mBalls) {
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            // wrap around so balls drift in and out of view
            if (b.x < -b.r) b.x = w + b.r;
            if (b.x > w + b.r) b.x = -b.r;
            if (b.y < -b.r) b.y = h + b.r;
            if (b.y > h + b.r) b.y = -b.r;
            canvas.save();
            canvas.translate(b.x, b.y);
            canvas.drawCircle(0, 0, b.r, b.paint);
            canvas.restore();
        }
        if (mRunning) {
            postInvalidateDelayed(50);
        }
    }
}
