package com.liskovsoft.smartyoutubetv2.tv.ui.playback.rav;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.rav.RavBestTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * RavTube: steps the video quality down when playback keeps stalling on a slow connection.
 *
 * Only stalls during playback count; loading a new video and seeking don't. One stall of 3 s, or two
 * stalls within a minute, cap the resolution one step below the current one ({@link RavBestTrack}).
 */
public class RavQualityGuard implements Player.EventListener {
    private static final String TAG = RavQualityGuard.class.getSimpleName();
    private static final long LONG_STALL_MS = 3_000;
    private static final long STALL_WINDOW_MS = 60_000;
    private static final int STALLS_IN_WINDOW = 2;
    private static final long GRACE_MS = 3_000;         // after a seek or a new video
    private static final long SWITCH_GRACE_MS = 8_000;  // after a track change: a higher quality buffers anew

    public interface Callback {
        @Nullable Format getCurrentVideoFormat();
        /** Select the video track again (RavBestTrack applies the new cap). */
        void reselectVideo();
        /** The quality notice changed (see {@link RavBestTrack#getLoweredHeight()}). */
        void onQualityNoticeChanged();
    }

    private final Callback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Long> mStalls = new ArrayList<>();
    private boolean mPlaying; // reached READY at least once for the current video
    private long mGraceUntilMs;
    private long mStallStartMs;
    private final Runnable mLongStall = this::lower;

    public RavQualityGuard(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void onTimelineChanged(Timeline timeline, @Nullable Object manifest, int reason) {
        if (reason != Player.TIMELINE_CHANGE_REASON_DYNAMIC) {
            reset(); // a new video
        }
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        startGrace(SWITCH_GRACE_MS);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        startGrace();
    }

    @Override
    public void onSeekProcessed() {
        startGrace();
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int state) {
        long now = System.currentTimeMillis();
        switch (state) {
            case Player.STATE_READY:
                if (mStallStartMs > 0) {
                    mHandler.removeCallbacks(mLongStall);
                    mStallStartMs = 0;
                    mStalls.add(now);
                    while (!mStalls.isEmpty() && now - mStalls.get(0) > STALL_WINDOW_MS) {
                        mStalls.remove(0);
                    }
                    if (mStalls.size() >= STALLS_IN_WINDOW) {
                        lower();
                    }
                }
                mPlaying = true;
                break;
            case Player.STATE_BUFFERING:
                if (mPlaying && playWhenReady && now > mGraceUntilMs && mStallStartMs == 0) {
                    mStallStartMs = now;
                    mHandler.postDelayed(mLongStall, LONG_STALL_MS);
                }
                break;
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
                reset();
                break;
        }
    }

    public void release() {
        mHandler.removeCallbacksAndMessages(null);
    }


    private void lower() {
        mHandler.removeCallbacks(mLongStall);
        mStallStartMs = 0;
        mStalls.clear();
        if (RavBestTrack.lowerQuality(mCallback.getCurrentVideoFormat())) {
            Log.d(TAG, "Playback keeps stalling: lowering the quality");
            startGrace(SWITCH_GRACE_MS);
            mCallback.reselectVideo();
            mCallback.onQualityNoticeChanged();
        }
    }

    private void reset() {
        mHandler.removeCallbacks(mLongStall);
        mPlaying = false;
        mStallStartMs = 0;
        mStalls.clear();
        startGrace();
    }

    /** The track is about to change on request (e.g. "try the best again"): don't count the buffering. */
    public void onReselect() {
        mHandler.removeCallbacks(mLongStall);
        mStallStartMs = 0;
        mStalls.clear();
        startGrace(SWITCH_GRACE_MS);
    }

    private void startGrace() {
        startGrace(GRACE_MS);
    }

    private void startGrace(long ms) {
        mGraceUntilMs = Math.max(mGraceUntilMs, System.currentTimeMillis() + ms);
    }
}
