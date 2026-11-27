package com.liskovsoft.smartyoutubetv2.tv.ui.channel;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.misc.CrashRestorer;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavPageFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavPosterPanel;

/**
 * RavTube channel page: the channel's sections as poster rows, in the overview's style.
 */
public class RavChannelFragment extends RavPageFragment implements ChannelView {
    private ChannelPresenter mChannelPresenter;
    private boolean mIsFragmentCreated;
    private CrashRestorer mCrashRestorer;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(null); // Real restore takes place in the presenter

        if (getContext() == null) {
            return;
        }

        mCrashRestorer = new CrashRestorer(getContext(), savedInstanceState);
        mIsFragmentCreated = true;
        mChannelPresenter = ChannelPresenter.instance(getContext());
        mChannelPresenter.setView(this);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        updateHeader();
        mChannelPresenter.onViewInitialized();

        // Restore state after crash
        mCrashRestorer.restoreHeader((idx, video) -> setPosition(idx));
        mCrashRestorer.restorePlayback();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mCrashRestorer.persistHeaderIndex(outState, mPanel != null ? mPanel.getPosition() : 0);
    }

    @Override
    protected VideoGroupPresenter getPagePresenter() {
        return mChannelPresenter;
    }

    @Override
    protected int getPanelMode() {
        return RavPosterPanel.MODE_ROWS;
    }

    private void updateHeader() {
        String id = mChannelPresenter.getChannelId();
        showChannel(mChannelPresenter.getChannel(), Helpers.startsWith(id, "@") ? id : null);
    }

    @Override
    public void update(VideoGroup videoGroup) {
        updateHeader();
        if (mPanel != null) {
            mPanel.update(videoGroup);
        }
    }

    @Override
    public void setPosition(int index) {
        if (mPanel != null) {
            mPanel.selectIndex(index);
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        setProgressVisible(show);
    }

    @Override
    public void clear() {
        if (mPanel != null) {
            mPanel.clear();
            mPanel.setFocusOnContentPending(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!mIsFragmentCreated) {
            mChannelPresenter.onViewResumed();
        }

        mIsFragmentCreated = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mChannelPresenter.onViewDestroyed();
    }

    public void onFinish() {
        mChannelPresenter.onFinish();
    }
}
