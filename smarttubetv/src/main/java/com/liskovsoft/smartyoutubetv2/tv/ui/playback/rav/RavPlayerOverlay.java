package com.liskovsoft.smartyoutubetv2.tv.ui.playback.rav;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavGlide;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavStyle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavStyle.*;

/**
 * RavTube player controls, drawn over the video. One layer slides up in steps:
 * hidden → controls (buttons, seek bar, title) → up next (the next videos).<br/>
 * Without controls: left seeks back 30 s, right forward 15 s, each press shows a short blip.<br/>
 * All keys arrive through {@link #dispatchKey(KeyEvent)}; focus is drawn by this view itself, Android focus
 * is not used, so the old Leanback controls underneath never react.
 */
public class RavPlayerOverlay extends FrameLayout {
    public static final int SEEK_BACK_MS = 30_000;
    public static final int SEEK_FORWARD_MS = 15_000;

    private static final int MODE_HIDDEN = 0;
    private static final int MODE_CONTROLS = 1;
    private static final int MODE_UP_NEXT = 2;
    private static final int FOCUS_SEEK = 0;
    private static final int FOCUS_BUTTONS = 1;
    public static final long AUTO_HIDE_MS = 5_000;

    private static final int BTN_PREV = 0;
    private static final int BTN_NEXT = 1;
    private static final int BTN_COMMENTS = 2;
    private static final int BTN_CHANNEL = 3;
    private static final int BTN_CC = 4;
    private static final int BTN_QUALITY = 5; // only visible while the quality is lowered
    private static final int WARNING_YELLOW = 0xFFFFC83D;
    private static final float SCRIM_MAX = 0.96f;

    /** What the overlay needs from the player. Implemented by PlaybackFragment. */
    public interface Host {
        long getPositionMs();
        long getDurationMs();
        boolean isPlaying();
        void seekTo(long positionMs);
        /** @param quiet true: pausing must not bring up any controls */
        void togglePlay(boolean quiet);
        void previous();
        void next();
        void openComments();
        void openChannel();
        void toggleSubtitles();
        boolean isSubtitlesOn();
        /** @return the lowered resolution (e.g. 1080) while a slow connection keeps it down, else 0 */
        int getLoweredQualityHeight();
        /** Try the best quality again. */
        void retryBestQuality();
        List<Video> getUpNext();
        void openSuggestion(Video video);
        void longPressSuggestion(Video video);
        void onUpNextEnd(Video last);
        void onShownChanged(boolean shown);
        /** A key was used while the controls are visible: restart any hide timers. */
        void onUserActivity();
    }

    private Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mMode = MODE_HIDDEN;
    private int mFocus = FOCUS_SEEK;
    private int mBtn = BTN_NEXT;
    private int mCard;
    private Video mNextVideo;
    private final List<Video> mUpNext = new ArrayList<>();
    private final Set<Integer> mConsumedKeys = new HashSet<>();
    private boolean mOkLongHandled;

    // views
    private final LinearLayout mScrim;
    private final TextView mClock;
    private final FrameLayout mLayer;
    private final List<PillButton> mButtons = new ArrayList<>();
    private final SeekBarView mSeek;
    private final TextView mTimeCur;
    private final TextView mTimeLeft;
    private final TextView mTitle;
    private final TextView mMeta;
    private final TextView mHintDown;
    private final View mUpNextSection;
    private final UpNextScroll mUpNextScroll;
    private final RavGlide mUpNextGlide;
    private final LinearLayout mUpNextRow;
    private final List<CardViews> mCards = new ArrayList<>();
    private FrameLayout mPlayMark;
    private final ImageView mPlayMarkIcon;
    private final View mBlipBack;
    private final View mBlipForward;
    private View mMini;
    private final View mMiniBar;
    private final TextView mMiniCur;
    private final TextView mMiniDur;
    private final ImageView mChannelAvatar;

    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private long mLastInputMs;
    private final Runnable mAutoHide = () -> {
        if (mMode != MODE_HIDDEN) {
            setMode(MODE_HIDDEN);
        }
    };
    private boolean mSeekFeedback;
    private final Runnable mHideSeekFeedback = () -> {
        mSeekFeedback = false;
        mPlayMark.animate().alpha(0f).setDuration(200).start();
        mMini.animate().alpha(0f).setDuration(250).start();
    };
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateTimes();
            mClock.setText(mTimeFmt.format(new Date()));
            updatePlayMark();
            mHandler.postDelayed(this, 250);
        }
    };

    public RavPlayerOverlay(Context context, Host host) {
        super(context);
        mHost = host;
        setClipChildren(false);

        // scrims
        // one scrim for both steps: a fade followed by a solid tail, sliding up with the control layer
        int fadeH = (int) (dp(540) * 0.9f);
        mScrim = new LinearLayout(context);
        mScrim.setOrientation(LinearLayout.VERTICAL);
        View fade = new View(context);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, scrimRamp(SCRIM_MAX)));
        mScrim.addView(fade, new LinearLayout.LayoutParams(-1, fadeH));
        View solid = new View(context);
        solid.setBackgroundColor((int) (SCRIM_MAX * 255) << 24);
        mScrim.addView(solid, new LinearLayout.LayoutParams(-1, dp(540)));
        mScrim.setAlpha(0f);
        LayoutParams scrimLp = new LayoutParams(-1, fadeH + dp(540), Gravity.TOP);
        scrimLp.topMargin = dp(540) - fadeH;
        addView(mScrim, scrimLp);


        mClock = new TextView(context);
        font(mClock);
        mClock.setTextSize(19);
        mClock.setTextColor(0xB3FFFFFF);
        mClock.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        mClock.setAlpha(0f);
        LayoutParams clockLp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clockLp.setMargins(0, dp(38), dp(56), 0);
        addView(mClock, clockLp);

        // play / pause mark and the seek blips, centred
        mPlayMark = new FrameLayout(context);
        GradientDrawable markBg = oval(0x800C0C18);
        markBg.setStroke(Math.max(1, dp(0.75f)), 0x33FFFFFF);
        mPlayMark.setBackground(markBg);
        mPlayMarkIcon = new ImageView(context);
        mPlayMark.addView(mPlayMarkIcon, new LayoutParams(dp(26), dp(26), Gravity.CENTER));
        mPlayMark.setAlpha(0f);
        addView(mPlayMark, new LayoutParams(dp(64), dp(64), Gravity.CENTER));

        mBlipBack = makeBlip(context, R.drawable.rav_ic_rewind, "30");
        LayoutParams backLp = new LayoutParams(dp(56), dp(56), Gravity.CENTER);
        backLp.rightMargin = dp(152); // centre 76 dp left of the play mark's centre
        addView(mBlipBack, backLp);
        mBlipForward = makeBlip(context, R.drawable.rav_ic_forward, "15");
        LayoutParams fwdLp = new LayoutParams(dp(56), dp(56), Gravity.CENTER);
        fwdLp.leftMargin = dp(152);
        addView(mBlipForward, fwdLp);

        // hairline progress while seeking without controls
        LinearLayout mini = new LinearLayout(context);
        mini.setOrientation(LinearLayout.VERTICAL);
        FrameLayout miniTrack = new FrameLayout(context);
        miniTrack.setBackground(rect(0x38FFFFFF, dp(2)));
        mMiniBar = new View(context);
        mMiniBar.setBackground(rect(AMBER, dp(2)));
        miniTrack.addView(mMiniBar, new LayoutParams(0, -1));
        mini.addView(miniTrack, new LinearLayout.LayoutParams(-1, dp(3)));
        LinearLayout miniTimes = new LinearLayout(context);
        mMiniCur = smallLabel(context);
        mMiniDur = smallLabel(context);
        mMiniDur.setGravity(Gravity.END);
        miniTimes.addView(mMiniCur, new LinearLayout.LayoutParams(0, -2, 1f));
        miniTimes.addView(mMiniDur, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams miniTimesLp = new LinearLayout.LayoutParams(-1, -2);
        miniTimesLp.topMargin = dp(8);
        mini.addView(miniTimes, miniTimesLp);
        mMini = mini;
        mMini.setAlpha(0f);
        LayoutParams miniLp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        miniLp.setMargins(dp(56), 0, dp(56), dp(28));
        addView(mMini, miniLp);

        // the control layer: buttons, seek bar, title, up next
        mLayer = new FrameLayout(context);
        mLayer.setClipChildren(false);
        mLayer.setClipToPadding(false);
        mLayer.setAlpha(0f);
        mLayer.setTranslationY(dp(560));
        LayoutParams layerLp = new LayoutParams(-1, dp(360), Gravity.TOP);
        layerLp.leftMargin = dp(56);
        layerLp.rightMargin = dp(56);
        addView(mLayer, layerLp);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        addButton(buttons, new PillButton(context, R.drawable.rav_ic_prev, context.getString(R.string.rav_previous)), 0);
        addButton(buttons, new PillButton(context, R.drawable.rav_ic_next, context.getString(R.string.rav_next)), dp(10));
        addButton(buttons, new PillButton(context, R.drawable.rav_ic_comments, context.getString(R.string.rav_comments)), dp(24));
        PillButton channel = new PillButton(context, 0, context.getString(R.string.rav_channel));
        mChannelAvatar = channel.icon;
        addButton(buttons, channel, dp(10));
        addButton(buttons, new PillButton(context, R.drawable.rav_ic_cc, context.getString(R.string.rav_subtitles)), dp(10));
        PillButton quality = new PillButton(context, R.drawable.rav_ic_warning, "");
        quality.setIdleInk(WARNING_YELLOW);
        quality.setVisibility(View.GONE);
        addButton(buttons, quality, dp(10));
        mLayer.addView(buttons, new LayoutParams(-2, dp(36)));

        mSeek = new SeekBarView(context);
        LayoutParams seekLp = new LayoutParams(-1, dp(20));
        seekLp.topMargin = dp(56);
        mLayer.addView(mSeek, seekLp);

        LinearLayout times = new LinearLayout(context);
        mTimeCur = smallLabel(context);
        mTimeLeft = smallLabel(context);
        mTimeLeft.setGravity(Gravity.END);
        times.addView(mTimeCur, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(mTimeLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        LayoutParams timesLp = new LayoutParams(-1, -2);
        timesLp.topMargin = dp(84);
        mLayer.addView(times, timesLp);

        mTitle = new TextView(context);
        font(mTitle);
        mTitle.setTextSize(22);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams titleLp = new LayoutParams(-1, -2);
        titleLp.topMargin = dp(106);
        mLayer.addView(mTitle, titleLp);

        mMeta = new TextView(context);
        mMeta.setTextSize(11);
        mMeta.setTextColor(0x99FFFFFF);
        mMeta.setSingleLine(true);
        mMeta.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams metaLp = new LayoutParams(-1, -2);
        metaLp.topMargin = dp(140);
        metaLp.rightMargin = dp(150);
        mLayer.addView(mMeta, metaLp);

        mHintDown = new TextView(context);
        sansMedium(mHintDown);
        mHintDown.setTextSize(10);
        mHintDown.setTextColor(0x73FFFFFF);
        mHintDown.setText(context.getString(R.string.rav_up_next_hint));
        LayoutParams hintLp = new LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        hintLp.topMargin = dp(141);
        mLayer.addView(mHintDown, hintLp);

        // up next: the same posters as the overview
        FrameLayout upNext = new FrameLayout(context);
        upNext.setClipChildren(false);
        TextView header = new TextView(context);
        sansMedium(header);
        header.setTextSize(8.5f);
        if (Build.VERSION.SDK_INT >= 21) {
            header.setLetterSpacing(0.1f);
        }
        header.setTextColor(0x66FFFFFF);
        header.setIncludeFontPadding(false);
        header.setGravity(Gravity.BOTTOM);
        header.setText(context.getString(R.string.rav_up_next).toUpperCase(Locale.getDefault()));
        header.setPadding(dp(EDGE + 2), 0, 0, (int) (dp(CARD_H) * (CARD_ZOOM - 1f) / 2f) + dp(1));
        mUpNextRow = new LinearLayout(context);
        mUpNextRow.setOrientation(LinearLayout.HORIZONTAL);
        mUpNextRow.setClipChildren(false);
        mUpNextRow.setClipToPadding(false);
        mUpNextRow.setPadding(dp(EDGE), dp(GUTTER / 2f), dp(GUTTER), dp(GUTTER / 2f));
        mUpNextScroll = new UpNextScroll(context);
        mUpNextScroll.setHorizontalScrollBarEnabled(false);
        mUpNextScroll.setClipChildren(false);
        mUpNextScroll.setClipToPadding(false);
        mUpNextScroll.setFocusable(false);
        mUpNextScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mUpNextScroll.addView(mUpNextRow, new LayoutParams(-2, -1));
        mUpNextGlide = new RavGlide(mUpNextScroll, true);
        upNext.addView(mUpNextScroll, new LayoutParams(-1, dp(ROW_H)));
        upNext.addView(header, new LayoutParams(-1, dp(GUTTER / 2f), Gravity.TOP));
        mUpNextSection = upNext;
        LayoutParams upLp = new LayoutParams(-1, dp(ROW_H));
        upLp.topMargin = dp(196);
        upLp.leftMargin = -dp(EDGE);
        upLp.rightMargin = -dp(56); // run to the screen edge, the next poster peeks in
        mLayer.addView(upNext, upLp);

        render();
    }

    // ------------------------------------------------------------------ public API

    /** Milliseconds since the last key used by the controls. */
    public long getIdleMs() {
        return System.currentTimeMillis() - mLastInputMs;
    }

    /** The lowered-quality notice changed: update the buttons. */
    public void refreshQualityNotice() {
        mHandler.post(this::render);
    }

    public boolean isShownOverlay() {
        return mMode != MODE_HIDDEN;
    }

    public boolean isControlsShown() {
        return mMode == MODE_CONTROLS;
    }

    public boolean isUpNextShown() {
        return mMode == MODE_UP_NEXT;
    }

    /** Shows the controls. Already visible (controls or up next): nothing changes, the user keeps their place. */
    public void showControls() {
        if (mMode == MODE_HIDDEN) {
            mFocus = FOCUS_SEEK;
            setMode(MODE_CONTROLS);
        }
    }

    public void showUpNext() {
        if (!mUpNext.isEmpty()) {
            mCard = 0;
            setMode(MODE_UP_NEXT);
        } else {
            showControls();
        }
    }

    public void focusSeek() {
        mFocus = FOCUS_SEEK;
        render();
    }

    public void hide() {
        if (mMode != MODE_HIDDEN) {
            setMode(MODE_HIDDEN);
        }
    }

    public void setVideo(Video video, CharSequence meta) {
        if (video == null) {
            return;
        }
        mTitle.setText(video.getTitle());
        mMeta.setText(meta != null ? meta : video.getAuthor());
        mButtons.get(BTN_CHANNEL).setLabel(!TextUtils.isEmpty(video.getAuthor()) ? video.getAuthor() : getContext().getString(R.string.rav_channel));
        updateTimes();
    }

    public void setTitle(String title) {
        if (title != null) {
            mTitle.setText(title);
        }
    }

    public void setChannelIcon(String url) {
        try {
            if (url == null) {
                mChannelAvatar.setImageDrawable(null);
                mChannelAvatar.setBackground(oval(0x55FFFFFF));
                return;
            }
            mChannelAvatar.setBackground(null);
            Glide.with(getContext()).load(url).apply(RequestOptions.circleCropTransform().override(dp(22), dp(22))).into(mChannelAvatar);
        } catch (IllegalArgumentException e) {
            // destroyed activity
        }
    }

    public void setNextVideo(Video next) {
        mNextVideo = next;
        refreshUpNext();
    }

    /** Re-reads the up-next list from the host. */
    public void refreshUpNext() {
        List<Video> videos = mHost.getUpNext();
        mUpNext.clear();
        if (videos != null) {
            mUpNext.addAll(videos);
        }
        rebuildCards();
        if (mMode == MODE_UP_NEXT && mUpNext.isEmpty()) {
            setMode(MODE_CONTROLS);
        }
        render();
    }

    public void refreshButtons() {
        render();
    }

    // ------------------------------------------------------------------ keys

    /** Handles the remote's D-pad, OK and Back. Returns true when the key was used here. */
    public boolean dispatchKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (!isOurKey(code)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean ours = mConsumedKeys.remove(code);
            if (ours && isOk(code) && mMode == MODE_UP_NEXT && !mOkLongHandled) {
                openCard();
            }
            return ours;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return mConsumedKeys.contains(code);
        }
        boolean handled = handleKeyDown(code, event);
        if (handled) {
            mLastInputMs = System.currentTimeMillis();
            mConsumedKeys.add(code);
            restartAutoHide();
            if (mMode != MODE_HIDDEN) {
                mHost.onUserActivity();
            }
        }
        return handled;
    }

    private static boolean isPlayPause(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    }

    private static boolean isOurKey(int code) {
        return isPlayPause(code) || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || isOk(code) || code == KeyEvent.KEYCODE_BACK;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean handleKeyDown(int code, KeyEvent event) {
        boolean ok = isOk(code);
        boolean first = event.getRepeatCount() == 0;

        // play and pause buttons behave like OK: each press toggles, whatever the current state
        if (isPlayPause(code)) {
            if (first) {
                togglePlay(true);
            }
            return true;
        }

        // some remotes send OK / play twice within a moment; the second press would undo the first
        if (ok && first && System.currentTimeMillis() - mLastToggleMs < DOUBLE_PRESS_MS) {
            return true;
        }

        if (mMode == MODE_HIDDEN) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekBy(-SEEK_BACK_MS);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekBy(SEEK_FORWARD_MS);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showControls();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    return false; // leave the player
            }
            if (ok && first) {
                togglePlay(true); // pause / play without any controls
            }
            return ok;
        }

        if (mMode == MODE_CONTROLS && mFocus == FOCUS_SEEK) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekBy(-SEEK_BACK_MS);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekBy(SEEK_FORWARD_MS);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    mFocus = FOCUS_BUTTONS;
                    render();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showUpNext();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (first) {
                        setMode(MODE_HIDDEN);
                    }
                    return true;
            }
            if (ok && first) {
                togglePlay(false);
                render();
            }
            return ok;
        }

        if (mMode == MODE_CONTROLS) { // buttons
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    mBtn = Math.max(0, mBtn - 1);
                    render();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (mBtn + 1 < mButtons.size() && mButtons.get(mBtn + 1).getVisibility() == View.VISIBLE) {
                        mBtn++;
                    }
                    render();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (first) {
                        setMode(MODE_HIDDEN); // up from the buttons closes the controls
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    mFocus = FOCUS_SEEK;
                    render();
                    return true;
            }
            if (ok && first) {
                pressButton(mBtn);
            }
            return ok;
        }

        // up next
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mCard = Math.max(0, mCard - 1);
                render();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mCard = Math.min(mUpNext.size() - 1, mCard + 1);
                if (mCard > mUpNext.size() - 4 && !mUpNext.isEmpty()) {
                    mHost.onUpNextEnd(mUpNext.get(mUpNext.size() - 1));
                }
                render();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BACK:
                mFocus = FOCUS_SEEK;
                setMode(MODE_CONTROLS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
        }
        if (ok) {
            if (first) {
                mOkLongHandled = false;
            } else if (!mOkLongHandled) {
                mOkLongHandled = true;
                if (mCard < mUpNext.size()) {
                    mHost.longPressSuggestion(mUpNext.get(mCard));
                }
            }
            return true;
        }
        return false;
    }

    private void openCard() {
        if (mCard >= 0 && mCard < mUpNext.size()) {
            Video video = mUpNext.get(mCard);
            mFocus = FOCUS_SEEK;
            setMode(MODE_CONTROLS);
            mHost.openSuggestion(video);
        }
    }

    private void pressButton(int index) {
        switch (index) {
            case BTN_PREV:
                mHost.previous();
                break;
            case BTN_NEXT:
                mHost.next();
                break;
            case BTN_COMMENTS:
                mHost.openComments();
                break;
            case BTN_CHANNEL:
                mHost.openChannel();
                break;
            case BTN_CC:
                mHost.toggleSubtitles();
                mHandler.postDelayed(this::render, 300);
                break;
            case BTN_QUALITY:
                mHost.retryBestQuality();
                mBtn = BTN_CC;
                break;
        }
        render();
    }

    // ------------------------------------------------------------------ seeking

    private void seekBy(long deltaMs) {
        long duration = mHost.getDurationMs();
        long pos = mHost.getPositionMs() + deltaMs;
        if (duration > 0) {
            pos = Math.min(pos, duration - 1000);
        }
        mHost.seekTo(Math.max(0, pos));
        updateTimes();

        // one blip per press: restart its animation every time, no adding up
        View blip = deltaMs < 0 ? mBlipBack : mBlipForward;
        View other = deltaMs < 0 ? mBlipForward : mBlipBack;
        other.animate().cancel();
        other.setAlpha(0f);
        blip.animate().cancel();
        blip.setAlpha(0f);
        blip.setScaleX(0.75f);
        blip.setScaleY(0.75f);
        blip.animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction(() ->
                blip.animate().scaleX(1f).scaleY(1f).setDuration(240).withEndAction(() ->
                        blip.animate().alpha(0f).setDuration(260).start()).start()).start();

        updatePlayMark();
        mPlayMark.animate().cancel();
        mPlayMark.setAlpha(1f);
        if (mMode == MODE_HIDDEN) {
            mMini.animate().cancel();
            mMini.setAlpha(1f);
        }
        mSeekFeedback = true;
        mHandler.removeCallbacks(mHideSeekFeedback);
        mHandler.postDelayed(mHideSeekFeedback, 800);
    }

    /** Toggles play / pause and shows the new state in the middle for 2 s. */
    private static final long DOUBLE_PRESS_MS = 350;
    private long mLastToggleMs;

    private void togglePlay(boolean quiet) {
        long now = System.currentTimeMillis();
        if (now - mLastToggleMs < DOUBLE_PRESS_MS) {
            return; // a doubled signal from the remote: one press, one toggle
        }
        mLastToggleMs = now;
        boolean willPlay = !mHost.isPlaying();
        mHost.togglePlay(quiet);
        showPlayState(willPlay);
    }

    /** Shows the play or pause mark in the middle for 2 s (also for play / pause from the media session). */
    public void showPlayState(boolean willPlay) {
        mPlayMarkIcon.setImageResource(willPlay ? R.drawable.rav_ic_play : R.drawable.rav_ic_pause);
        mPlayMarkUntil = System.currentTimeMillis() + 2000;
        mPlayMark.animate().cancel();
        mPlayMark.setAlpha(1f);
        mPlayMark.setScaleX(0.9f);
        mPlayMark.setScaleY(0.9f);
        mPlayMark.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        mHandler.removeCallbacks(mHidePlayMark);
        mHandler.postDelayed(mHidePlayMark, 2000);
    }

    private long mPlayMarkUntil;
    private final Runnable mHidePlayMark = () -> updatePlayMark();

    private void updatePlayMark() {
        if (System.currentTimeMillis() < mPlayMarkUntil) {
            return; // showing the play / pause state after a press
        }
        boolean playing = mHost.isPlaying();
        mPlayMarkIcon.setImageResource(playing ? R.drawable.rav_ic_play : R.drawable.rav_ic_pause);
        if (!mSeekFeedback && mPlayMark.getAlpha() != 0f) {
            mPlayMark.animate().alpha(0f).setDuration(200).start(); // only shown next to the seek blips
        }
    }

    private void updateTimes() {
        long pos = Math.max(0, mHost.getPositionMs());
        long dur = Math.max(0, mHost.getDurationMs());
        float f = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
        mSeek.setProgress(f);
        mTimeCur.setText(format(pos));
        mTimeLeft.setText(dur > 0 ? "-" + format(dur - pos) : "");
        mMiniCur.setText(format(pos));
        mMiniDur.setText(dur > 0 ? format(dur) : "");
        View track = (View) mMiniBar.getParent();
        if (track.getWidth() > 0) {
            mMiniBar.getLayoutParams().width = (int) (track.getWidth() * f);
            mMiniBar.requestLayout();
        }
    }

    private static String format(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = s % 3600 / 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Smoothstep alpha ramp: no hard edge at the top and no kink anywhere along the fade. */
    private static int[] scrimRamp(float max) {
        int n = 24;
        int[] colors = new int[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            float a = max * t * t * (3f - 2f * t);
            colors[i] = Math.round(a * 255) << 24;
        }
        return colors;
    }

    // ------------------------------------------------------------------ modes + rendering

    private void setMode(int mode) {
        boolean wasShown = mMode != MODE_HIDDEN;
        mMode = mode;
        boolean shown = mode != MODE_HIDDEN;

        float layerY = mode == MODE_HIDDEN ? dp(560) : mode == MODE_CONTROLS ? dp(344) : dp(170);
        mLayer.animate().translationY(layerY).alpha(shown ? 1f : 0f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        mScrim.animate().translationY(layerY - dp(344)).alpha(shown ? 1f : 0f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        mClock.animate().alpha(shown ? 1f : 0f).setDuration(300).start();
        mHintDown.animate().alpha(mode == MODE_UP_NEXT ? 0f : 1f).setDuration(200).start();
        if (shown) {
            mMini.setAlpha(0f);
        }

        mHandler.removeCallbacks(mTick);
        if (shown) {
            mTick.run();
        }

        render();
        restartAutoHide();

        if (wasShown != shown) {
            mHost.onShownChanged(shown);
        }
    }

    private void restartAutoHide() {
        mHandler.removeCallbacks(mAutoHide);
        if (mMode != MODE_HIDDEN) {
            mHandler.postDelayed(mAutoHide, AUTO_HIDE_MS);
        }
    }

    private void render() {
        for (int i = 0; i < mButtons.size(); i++) {
            mButtons.get(i).setFocused(mMode == MODE_CONTROLS && mFocus == FOCUS_BUTTONS && mBtn == i);
        }
        mButtons.get(BTN_CC).setOn(mHost.isSubtitlesOn());
        int lowered = mHost.getLoweredQualityHeight();
        PillButton quality = mButtons.get(BTN_QUALITY);
        quality.setVisibility(lowered > 0 ? View.VISIBLE : View.GONE);
        if (lowered > 0) {
            quality.setLabel(getContext().getString(R.string.rav_quality_lowered, lowered));
        } else if (mBtn == BTN_QUALITY) {
            mBtn = BTN_CC;
        }
        mSeek.setFocused(mMode == MODE_CONTROLS && mFocus == FOCUS_SEEK);

        for (int i = 0; i < mCards.size(); i++) {
            mCards.get(i).setFocused(mMode == MODE_UP_NEXT && mCard == i);
        }
        if (mMode == MODE_UP_NEXT) {
            slideUpNext();
        }
        mUpNextSection.setVisibility(mUpNext.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        mHintDown.setVisibility(mUpNext.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        updatePlayMark();
    }

    /** Keep the selection in the second column; the next posters stay in view. */
    private void slideUpNext() {
        int slot = dp(CARD_W + GUTTER);
        int first = Math.max(0, mCard - 1);
        int max = Math.max(0, mUpNextRow.getWidth() - mUpNextScroll.getWidth());
        mUpNextGlide.to(Math.min(first * slot, max));
    }

    // ------------------------------------------------------------------ building blocks

    private int dp(float v) {
        return RavStyle.dp(getContext(), v);
    }

    private TextView smallLabel(Context context) {
        TextView tv = new TextView(context);
        sansMedium(tv);
        tv.setTextSize(11);
        tv.setTextColor(0xB3FFFFFF);
        return tv;
    }

    private View makeBlip(Context context, int icon, String text) {
        FrameLayout blip = new FrameLayout(context);
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(icon);
        blip.addView(arrow, new LayoutParams(-1, -1));
        TextView label = new TextView(context);
        sansMedium(label);
        label.setTextSize(11);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setText(text);
        label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        blip.addView(label, new LayoutParams(-1, -1));
        blip.setAlpha(0f);
        return blip;
    }

    private void addButton(LinearLayout row, PillButton button, int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.leftMargin = leftMargin;
        row.addView(button, lp);
        mButtons.add(button);
    }

    /** Round glass button; with focus it turns white and shows its name. */
    private final class PillButton extends LinearLayout {
        final ImageView icon;
        final TextView label;
        private final GradientDrawable mBg = rect(0x22FFFFFF, dp(18));
        private final View mDot;
        private boolean mFocused;
        private int mIdleInk = Color.WHITE;

        PillButton(Context context, int iconRes, String text) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), 0, dp(8), 0);
            setMinimumWidth(dp(36));
            setBackground(mBg);
            setAlpha(0.8f);
            icon = new ImageView(context);
            int size = iconRes != 0 ? dp(20) : dp(22);
            if (iconRes != 0) {
                icon.setImageResource(iconRes);
            } else {
                icon.setBackground(oval(0x55FFFFFF));
            }
            addView(icon, new LayoutParams(size, size));
            label = new TextView(context);
            sansMedium(label);
            label.setTextSize(12);
            label.setText(text);
            label.setSingleLine(true);
            label.setVisibility(GONE);
            LayoutParams labelLp = new LayoutParams(-2, -2);
            labelLp.leftMargin = dp(8);
            labelLp.rightMargin = dp(6);
            addView(label, labelLp);
            mDot = new View(context);
            mDot.setBackground(oval(AMBER));
            mDot.setVisibility(GONE);
            LayoutParams dotLp = new LayoutParams(dp(7), dp(7));
            dotLp.gravity = Gravity.TOP;
            dotLp.leftMargin = -dp(5);
            dotLp.topMargin = dp(3);
            addView(mDot, dotLp);
        }

        void setLabel(String text) {
            label.setText(text);
        }

        /** Icon colour without focus (white by default). */
        void setIdleInk(int color) {
            mIdleInk = color;
            icon.setColorFilter(color);
        }

        void setOn(boolean on) {
            mDot.setVisibility(on ? VISIBLE : GONE);
        }

        void setFocused(boolean focused) {
            if (mFocused == focused) {
                return;
            }
            mFocused = focused;
            mBg.setColor(focused ? 0xFFFFFFFF : 0x22FFFFFF);
            if (icon.getDrawable() != null && icon != mChannelAvatar) {
                icon.setColorFilter(focused ? 0xFF101020 : mIdleInk);
            }
            label.setTextColor(focused ? 0xFF101020 : Color.WHITE);
            label.setVisibility(focused ? VISIBLE : GONE);
            setAlpha(focused ? 1f : 0.8f);
        }
    }

    /** Thin track that grows a little and shows a knob when it has focus. */
    private final class SeekBarView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mProgress;
        private boolean mFocused;

        SeekBarView(Context context) {
            super(context);
        }

        void setProgress(float f) {
            mProgress = f;
            invalidate();
        }

        void setFocused(boolean focused) {
            mFocused = focused;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float cy = getHeight() / 2f;
            float h = dp(mFocused ? 6 : 4);
            float r = h / 2f;
            mPaint.setColor(0x38FFFFFF);
            mRect.set(0, cy - r, w, cy + r);
            canvas.drawRoundRect(mRect, r, r, mPaint);
            mPaint.setColor(AMBER);
            mRect.set(0, cy - r, w * mProgress, cy + r);
            canvas.drawRoundRect(mRect, r, r, mPaint);
            if (mFocused) {
                float x = w * mProgress;
                mPaint.setColor(0x59FFB46B);
                canvas.drawCircle(x, cy, dp(11), mPaint);
                mPaint.setColor(Color.WHITE);
                canvas.drawCircle(x, cy, dp(7), mPaint);
            }
        }
    }

    // ------------------------------------------------------------------ up next cards

    private void rebuildCards() {
        mUpNextRow.removeAllViews();
        mCards.clear();
        for (Video video : mUpNext) {
            CardViews card = new CardViews(getContext(), video, isNext(video));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H));
            lp.rightMargin = dp(GUTTER);
            mUpNextRow.addView(card.root, lp);
            mCards.add(card);
        }
        mCard = Math.max(0, Math.min(mCard, mCards.size() - 1));
    }

    private boolean isNext(Video video) {
        if (mNextVideo == null || video == null) {
            return false;
        }
        return video == mNextVideo || (video.videoId != null && video.videoId.equals(mNextVideo.videoId));
    }

    private final class CardViews {
        final FrameLayout root;
        final ImageView img;
        final TextView title;
        float wash = 1f;
        ValueAnimator anim;
        boolean focused;

        CardViews(Context ctx, Video video, boolean next) {
            root = new FrameLayout(ctx);
            root.setAlpha(0.9f);
            root.setBackground(rect(TILE_BG, dp(8)));
            rounded(root, dp(8));

            img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setColorFilter(washFilter(1f));
            root.addView(img, new LayoutParams(-1, -1));

            View sheen = new View(ctx);
            sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x24FFFFFF, 0x00FFFFFF}));
            root.addView(sheen, new LayoutParams(-1, (int) (dp(CARD_H) * 0.5f), Gravity.TOP));
            View shade = new View(ctx);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.TRANSPARENT, 0x8C000000, 0xD9000000, 0xF2000000}));
            root.addView(shade, new LayoutParams(-1, (int) (dp(CARD_H) * 0.62f), Gravity.BOTTOM));

            LinearLayout labels = new LinearLayout(ctx);
            labels.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(ctx);
            font(title);
            title.setTextSize(12);
            title.setTextColor(0xD9FFFFFF);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setShadowLayer(dp(4), 0, dp(1), 0xFF000000);
            title.setText(video.getTitle());
            labels.addView(title);
            TextView meta = new TextView(ctx);
            meta.setTextSize(8.5f);
            meta.setTextColor(0x8CFFFFFF);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            CharSequence second = video.getSecondTitle();
            meta.setText(second != null ? second : video.getAuthor());
            labels.addView(meta);
            LayoutParams labelsLp = new LayoutParams(-1, -2, Gravity.BOTTOM);
            labelsLp.setMargins(dp(12), 0, dp(12), dp(10));
            root.addView(labels, labelsLp);

            String badgeText = video.isLive ? "LIVE" : video.badge;
            if (!TextUtils.isEmpty(badgeText)) {
                TextView badge = new TextView(ctx);
                sansMedium(badge);
                badge.setTextSize(9);
                badge.setTextColor(0xE6FFFFFF);
                badge.setPadding(dp(5), dp(1), dp(5), dp(1));
                badge.setBackground(rect(video.isLive ? LIVE_RED : 0x99000000, dp(3)));
                badge.setText(badgeText);
                LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                lp.setMargins(0, dp(6), dp(6), 0);
                root.addView(badge, lp);
            }
            if (next) {
                TextView badge = new TextView(ctx);
                sansMedium(badge);
                badge.setTextSize(9);
                badge.setTextColor(0xFF101020);
                badge.setPadding(dp(5), dp(1), dp(5), dp(1));
                badge.setBackground(rect(AMBER, dp(3)));
                badge.setText(ctx.getString(R.string.rav_next));
                LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                lp.setMargins(dp(6), dp(6), 0, 0);
                root.addView(badge, lp);
            }
            if (Build.VERSION.SDK_INT >= 23) {
                root.setForeground(glassEdge(ctx));
            }
            try {
                Glide.with(ctx).load(video.getCardImageUrl())
                        .apply(RequestOptions.centerCropTransform().override(dp(CARD_W), dp(CARD_H))).into(img);
            } catch (IllegalArgumentException e) {
                // destroyed activity
            }
        }

        void setFocused(boolean has) {
            if (focused == has) {
                return;
            }
            focused = has;
            root.animate().scaleX(has ? CARD_ZOOM : 1f).scaleY(has ? CARD_ZOOM : 1f).setDuration(160).start();
            if (Build.VERSION.SDK_INT >= 21) {
                root.setTranslationZ(has ? 1f : 0f);
            }
            title.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            if (anim != null) {
                anim.cancel();
            }
            anim = ValueAnimator.ofFloat(wash, has ? 0f : 1f);
            anim.setDuration(200);
            anim.addUpdateListener(a -> {
                wash = (float) a.getAnimatedValue();
                img.setColorFilter(washFilter(wash));
                root.setAlpha(1f - 0.1f * wash);
            });
            anim.start();
        }
    }

    /** HorizontalScrollView that never scrolls by itself; we glide it. */
    private static final class UpNextScroll extends HorizontalScrollView {
        UpNextScroll(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            return 0;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
