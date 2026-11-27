package com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav;

import android.view.Choreographer;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Scroll motion that follows a moving target: a critically damped spring (port of RavLauncher's Glide).
 * A new target while still moving bends the motion toward it and keeps its speed, so fast repeated presses
 * give one continuous glide.<br/>
 * Works on a ScrollView (absolute scrollTo) or on a RecyclerView (relative scrollBy, position tracked here).
 */
public class RavGlide implements Choreographer.FrameCallback {
    private static final float OMEGA = 22f; // settles in roughly 300 ms

    private final View mView;
    private final boolean mHorizontal;
    private float mPos;
    private float mVel;
    private float mTarget;
    private long mLast;
    private boolean mRunning;
    /** RecyclerView only: the offset we have applied so far. */
    private int mApplied;

    public RavGlide(View view, boolean horizontal) {
        mView = view;
        mHorizontal = horizontal;
    }

    private int current() {
        if (mView instanceof RecyclerView) {
            return mApplied;
        }
        return mHorizontal ? mView.getScrollX() : mView.getScrollY();
    }

    private void apply(int v) {
        if (mView instanceof RecyclerView) {
            int delta = v - mApplied;
            mApplied = v;
            if (delta != 0) {
                if (mHorizontal) {
                    mView.scrollBy(delta, 0);
                } else {
                    mView.scrollBy(0, delta);
                }
            }
        } else if (mHorizontal) {
            mView.scrollTo(v, mView.getScrollY());
        } else {
            mView.scrollTo(mView.getScrollX(), v);
        }
    }

    public int getTarget() {
        return mRunning ? Math.round(mTarget) : current();
    }

    public void to(int value) {
        mTarget = value;
        if (!mRunning) {
            mPos = current();
            mVel = 0;
            mLast = 0;
            mRunning = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void jump(int value) {
        stop();
        apply(value);
    }

    /** RecyclerView only: the view was reset to [value] by other means (e.g. a new adapter). */
    public void reset(int value) {
        stop();
        mApplied = value;
    }

    public void stop() {
        if (mRunning) {
            Choreographer.getInstance().removeFrameCallback(this);
        }
        mRunning = false;
        mVel = 0;
    }

    @Override
    public void doFrame(long now) {
        if (!mRunning) {
            return;
        }
        float dt = mLast == 0 ? 1f / 60f : Math.max(0.001f, Math.min(0.05f, (now - mLast) / 1e9f));
        mLast = now;
        int steps = 4;
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float acc = OMEGA * OMEGA * (mTarget - mPos) - 2f * OMEGA * mVel;
            mVel += acc * h;
            mPos += mVel * h;
        }
        apply(Math.round(mPos));
        if (Math.abs(mTarget - mPos) < 0.5f && Math.abs(mVel) < 5f) {
            apply(Math.round(mTarget));
            mRunning = false;
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}
