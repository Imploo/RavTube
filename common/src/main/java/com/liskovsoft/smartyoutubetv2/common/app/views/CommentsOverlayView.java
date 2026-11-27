package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.CommentsReceiver;

/**
 * A player view that shows comments itself (RavTube: glass cards over the video) instead of the dialog.
 */
public interface CommentsOverlayView {
    /** @return true when the view shows the comments; false falls back to the dialog */
    boolean showCommentsOverlay(CommentsReceiver receiver, String title);
}
