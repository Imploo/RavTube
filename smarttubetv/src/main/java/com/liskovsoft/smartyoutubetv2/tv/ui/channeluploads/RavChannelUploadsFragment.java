package com.liskovsoft.smartyoutubetv2.tv.ui.channeluploads;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavPageFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavPosterPanel;

/**
 * RavTube channel uploads (e.g. a channel picked in the Channels overview): a 3-column poster grid.
 */
public class RavChannelUploadsFragment extends RavPageFragment implements ChannelUploadsView {
    private static final String SELECTED_ITEM_INDEX = "SelectedItemIndex";
    private ChannelUploadsPresenter mPresenter;
    private boolean mIsFragmentCreated;
    private int mRestoredItemIndex = -1;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(null); // Real restore takes place in the presenter

        mRestoredItemIndex = savedInstanceState != null ? savedInstanceState.getInt(SELECTED_ITEM_INDEX, -1) : -1;
        mIsFragmentCreated = true;
        mPresenter = ChannelUploadsPresenter.instance(getContext());
        mPresenter.setView(this);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        showChannel(mPresenter.getChannel(), null);
        mPresenter.onViewInitialized();

        if (mRestoredItemIndex >= 0) {
            mPanel.selectIndex(mRestoredItemIndex);
            mRestoredItemIndex = -1;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SELECTED_ITEM_INDEX, mPanel != null ? mPanel.getPosition() : -1);
    }

    @Override
    protected VideoGroupPresenter getPagePresenter() {
        return mPresenter;
    }

    @Override
    protected int getPanelMode() {
        return RavPosterPanel.MODE_GRID;
    }

    @Override
    public void update(VideoGroup videoGroup) {
        showChannel(mPresenter.getChannel(), videoGroup != null ? videoGroup.getTitle() : null);
        if (mPanel != null) {
            mPanel.update(videoGroup);
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
            mPresenter.onViewResumed();
        }

        mIsFragmentCreated = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }

    public void onFinish() {
        mPresenter.onFinish();
    }
}
