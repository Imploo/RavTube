package com.liskovsoft.smartyoutubetv2.common.rav;

import android.content.Context;

/**
 * RavTube: entry point for the app module, which can't see leanbackassistant directly.
 * The work happens in {@link com.liskovsoft.leanbackassistant.channels.RavContinueWatching}.
 */
public class RavContinueWatching {
    /** Refreshes "Continue watching" on the home screen a few seconds from now. */
    public static void syncSoon(Context context) {
        com.liskovsoft.leanbackassistant.channels.RavContinueWatching.syncSoon(context);
    }
}
