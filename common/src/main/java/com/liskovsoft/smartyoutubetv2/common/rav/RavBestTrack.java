package com.liskovsoft.smartyoutubetv2.common.rav;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import com.google.android.exoplayer2.Format;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

/**
 * RavTube: always plays the best video stream the TV can handle, no choosing.
 *
 * The candidates are the tracks the player already kept (unsupported VP9/AV1 resolutions are filtered out
 * before this). Order of preference:
 *   1. HDR, when the display supports it (otherwise HDR tracks are skipped)
 *   2. resolution
 *   3. frame rate
 *   4. codec: AV1 > VP9 > AVC (best quality per bit)
 *   5. bitrate
 *
 * When playback keeps stalling, {@link #lowerQuality} caps the resolution one step below the current one
 * (see RavQualityGuard). The cap lasts 15 minutes, after that a new video tries the best again.
 */
public class RavBestTrack {
    private static final String TAG = RavBestTrack.class.getSimpleName();
    private static final long CAP_MS = 15 * 60 * 1000;
    private static final int MIN_CAP_HEIGHT = 360;
    private static Boolean sHdrDisplay;
    private static int sMaxHeight = Integer.MAX_VALUE;
    private static long sCapSetAtMs;
    private static int sPickedHeight;
    private static int sBestHeight;

    public interface Filter {
        boolean accept(MediaTrack track);
    }

    /** @return the best video track, or null to fall back to the normal selection */
    public static MediaTrack pick(Context context, MediaTrack[][] groups, Filter filter) {
        if (groups == null) {
            return null;
        }
        boolean hdrAllowed = isHdrDisplay(context);
        if (sMaxHeight != Integer.MAX_VALUE && System.currentTimeMillis() - sCapSetAtMs > CAP_MS) {
            resetCap();
        }
        MediaTrack best = null;   // within the cap
        MediaTrack top = null;    // without the cap
        MediaTrack lowest = null; // when even the smallest track is above the cap

        for (MediaTrack[] group : groups) {
            if (group == null) {
                continue;
            }
            for (MediaTrack track : group) {
                if (track == null || track.format == null || !filter.accept(track)) {
                    continue;
                }
                if (!hdrAllowed && isHdr(track)) {
                    continue;
                }
                if (top == null || better(track, top, hdrAllowed)) {
                    top = track;
                }
                if (lowest == null || height(track) < height(lowest)) {
                    lowest = track;
                }
                if (height(track) <= sMaxHeight && (best == null || better(track, best, hdrAllowed))) {
                    best = track;
                }
            }
        }

        if (best == null) {
            best = lowest;
        }
        if (best != null) {
            sPickedHeight = height(best);
            sBestHeight = height(top);
            Log.d(TAG, "Best video track: " + best.format + (sMaxHeight != Integer.MAX_VALUE ? " (capped at " + sMaxHeight + "p)" : ""));
        }
        return best;
    }

    /**
     * Caps the resolution one step below the given (current) format.
     * @return false when it can't go lower
     */
    public static boolean lowerQuality(Format current) {
        if (current == null) {
            return false;
        }
        int height = TrackSelectorUtil.getRealHeight(current);
        if (height <= MIN_CAP_HEIGHT) {
            return false;
        }
        sMaxHeight = height - 1;
        sCapSetAtMs = System.currentTimeMillis();
        Log.d(TAG, "Stalling at " + height + "p: capping below it");
        return true;
    }

    public static void resetCap() {
        sMaxHeight = Integer.MAX_VALUE;
        sCapSetAtMs = 0;
    }

    /** @return the resolution actually playing (e.g. 1080) when it's lower than the best available, else 0 */
    public static int getLoweredHeight() {
        return sMaxHeight != Integer.MAX_VALUE && sPickedHeight > 0 && sPickedHeight < sBestHeight ? sPickedHeight : 0;
    }

    private static int height(MediaTrack track) {
        return TrackSelectorUtil.getRealHeight(track.format);
    }

    private static boolean better(MediaTrack a, MediaTrack b, boolean hdrAllowed) {
        if (hdrAllowed && isHdr(a) != isHdr(b)) {
            return isHdr(a);
        }
        int heightA = TrackSelectorUtil.getRealHeight(a.format);
        int heightB = TrackSelectorUtil.getRealHeight(b.format);
        if (heightA != heightB) {
            return heightA > heightB;
        }
        float fpsA = Math.round(a.format.frameRate);
        float fpsB = Math.round(b.format.frameRate);
        if (fpsA != fpsB) {
            return fpsA > fpsB;
        }
        int codecA = codecRank(a);
        int codecB = codecRank(b);
        if (codecA != codecB) {
            return codecA > codecB;
        }
        return a.format.bitrate > b.format.bitrate;
    }

    private static boolean isHdr(MediaTrack track) {
        return TrackSelectorUtil.isHdrFormat(track.format.id, track.format.codecs);
    }

    private static int codecRank(MediaTrack track) {
        if (track.isAV1Codec()) {
            return 3;
        }
        if (track.isVP9Codec()) {
            return 2;
        }
        String codecs = track.format.codecs;
        return codecs != null && codecs.toLowerCase().contains("avc") ? 1 : 0;
    }

    private static boolean isHdrDisplay(Context context) {
        if (sHdrDisplay == null) {
            boolean hdr = false;
            if (Build.VERSION.SDK_INT >= 24 && context != null) {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
                Display.HdrCapabilities caps = display != null ? display.getHdrCapabilities() : null;
                hdr = caps != null && caps.getSupportedHdrTypes() != null && caps.getSupportedHdrTypes().length > 0;
            }
            sHdrDisplay = hdr;
            Log.d(TAG, "HDR display: " + hdr);
        }
        return sHdrDisplay;
    }
}
