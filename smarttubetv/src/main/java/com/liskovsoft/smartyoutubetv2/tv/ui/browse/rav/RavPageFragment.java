package com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavStyle.*;

/**
 * A RavTube page with the overview's look: bokeh background, a header (avatar in the tile column, title,
 * subtitle, clock) and the poster panel at the same place as in the overview. Used for channel pages.
 */
public abstract class RavPageFragment extends Fragment {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RavBokehView mBokeh;
    private ImageView mAvatar;
    private TextView mTitle;
    private TextView mSubtitle;
    private TextView mClock;
    private ProgressBar mProgress;
    protected RavPosterPanel mPanel;
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mClock != null) {
                mClock.setText(mTimeFmt.format(new Date()));
            }
            mHandler.postDelayed(this, 10_000);
        }
    };

    /** The presenter that receives poster events. */
    protected abstract VideoGroupPresenter getPagePresenter();

    /** Rows or grid. */
    protected abstract int getPanelMode();

    private int dp(float v) {
        return RavStyle.dp(requireContext(), v);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = inflater.getContext();
        FrameLayout root = new FrameLayout(ctx);
        root.setClipChildren(true);

        mBokeh = new RavBokehView(ctx);
        root.addView(mBokeh, new FrameLayout.LayoutParams(-1, -1));

        // channel avatar where the overview has its tiles
        mAvatar = new ImageView(ctx);
        mAvatar.setBackground(oval(TILE_BG));
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(64), dp(64));
        avatarLp.leftMargin = dp(28 + (124 - 64) / 2f);
        avatarLp.topMargin = dp((HEADER_H - 64) / 2f);
        root.addView(mAvatar, avatarLp);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        mTitle = new TextView(ctx);
        font(mTitle);
        mTitle.setTextSize(30);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 21) {
            mTitle.setLetterSpacing(0.01f);
        }
        titles.addView(mTitle);
        mSubtitle = new TextView(ctx);
        mSubtitle.setTextSize(12);
        mSubtitle.setTextColor(0x99FFFFFF);
        mSubtitle.setSingleLine(true);
        mSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        mSubtitle.setVisibility(View.GONE);
        titles.addView(mSubtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        mClock = new TextView(ctx);
        font(mClock);
        mClock.setTextSize(19);
        mClock.setTextColor(0xB3FFFFFF);
        header.addView(mClock, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(-1, dp(HEADER_H));
        headerLp.leftMargin = dp(208);
        headerLp.rightMargin = dp(48);
        root.addView(header, headerLp);

        mPanel = new RavPosterPanel(ctx, new PanelCallback());
        mPanel.setMode(getPanelMode());
        mPanel.setFocusOnContentPending(true);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -1);
        panelLp.leftMargin = dp(208);
        panelLp.topMargin = dp(HEADER_H);
        panelLp.rightMargin = dp(42);
        root.addView(mPanel, panelLp);

        mProgress = new ProgressBar(ctx);
        mProgress.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= 21) {
            mProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xCCFFFFFF));
        }
        mProgress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
        progressLp.leftMargin = dp(104);
        root.addView(mProgress, progressLp);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        mBokeh.setRunning(true);
        mTick.run();
    }

    @Override
    public void onStop() {
        mBokeh.setRunning(false);
        mHandler.removeCallbacks(mTick);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    /** Header: the channel's name, subscribers and avatar. */
    protected void showChannel(Video channel, String fallbackTitle) {
        if (mTitle == null) {
            return;
        }
        String name = channel != null ? (channel.getAuthor() != null ? channel.getAuthor() : channel.getTitle()) : null;
        mTitle.setText(name != null ? name : fallbackTitle != null ? fallbackTitle : "");
        String subs = channel != null ? channel.subscriberCount : null;
        mSubtitle.setText(subs);
        mSubtitle.setVisibility(TextUtils.isEmpty(subs) ? View.GONE : View.VISIBLE);
        String avatar = channel != null ? channel.getCardImageUrl() : null;
        if (avatar != null) {
            RavPosterPanel.loadImage(mAvatar, avatar, dp(64), dp(64), true);
        }
    }

    protected void setProgressVisible(boolean show) {
        mHandler.post(() -> {
            if (mProgress != null) {
                mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    private final class PanelCallback implements RavPosterPanel.Callback {
        @Override
        public void onVideoItemSelected(Video item) {
            getPagePresenter().onVideoItemSelected(item);
        }

        @Override
        public void onVideoItemClicked(Video item) {
            getPagePresenter().onVideoItemClicked(item);
        }

        @Override
        public void onVideoItemLongClicked(Video item) {
            getPagePresenter().onVideoItemLongClicked(item);
        }

        @Override
        public void onScrollEnd(Video item) {
            getPagePresenter().onScrollEnd(item);
        }

        @Override
        public boolean hasPendingActions() {
            return getPagePresenter().hasPendingActions();
        }

        @Override
        public void onExitLeft() {
            // NOP: nothing left of the posters on this page
        }

        @Override
        public void onExitUp() {
            // NOP
        }

        @Override
        public boolean onBack() {
            return false; // the activity closes the page
        }
    }
}
