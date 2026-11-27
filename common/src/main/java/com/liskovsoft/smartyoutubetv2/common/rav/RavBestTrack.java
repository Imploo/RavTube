package com.liskovsoft.smartyoutubetv2.common.rav;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

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
 */
public class RavBestTrack {
    private static final String TAG = RavBestTrack.class.getSimpleName();
    private static Boolean sHdrDisplay;

    public interface Filter {
        boolean accept(MediaTrack track);
    }

    /** @return the best video track, or null to fall back to the normal selection */
    public static MediaTrack pick(Context context, MediaTrack[][] groups, Filter filter) {
        if (groups == null) {
            return null;
        }
        boolean hdrAllowed = isHdrDisplay(context);
        MediaTrack best = null;

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
                if (best == null || better(track, best, hdrAllowed)) {
                    best = track;
                }
            }
        }

        if (best != null) {
            Log.d(TAG, "Best video track: " + best.format);
        }
        return best;
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
