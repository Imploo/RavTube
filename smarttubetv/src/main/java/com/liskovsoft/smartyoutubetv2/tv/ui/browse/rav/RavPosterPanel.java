package com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.CrashRestorer;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.dialog.ErrorDialogFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.Section;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.SettingsSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.VideoSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.settings.SettingsGridFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.MultiVideoGridFragment;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import static com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavStyle.*;

/**
 * The RavTube poster panel: rows of posters or a 3-column grid, with the RavLauncher spacing, glide and
 * focus behaviour. Used by the overview, the channel page and the channel uploads page.<br/>
 * It is fed with {@link VideoGroup} updates exactly like the old Leanback fragments were.
 */
public class RavPosterPanel extends RecyclerView {
    public static final int MODE_NONE = 0;
    public static final int MODE_ROWS = 1;
    public static final int MODE_GRID = 2;
    /** A row of channels on top, the chosen channel's videos as a grid below (the Channels section). */
    public static final int MODE_CHANNELS = 3;
    private static final int TYPE_ROW = 1;
    private static final int TYPE_GRID = 2;
    private static final int GRID_COLS = 3;
    private static final int RESTORE_MAX_SIZE = 200;

    /** What the panel tells its screen. */
    public interface Callback extends VideoGroupPresenter {
        /** Left from the first poster of a row. */
        void onExitLeft();
        /** Up from the first row. */
        void onExitUp();
        /** Back while a poster has focus. Return true when handled. */
        boolean onBack();
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Callback mPresenter;
    private int mMode = MODE_NONE;
    private final RavGlide mPanelGlide;
    private final PanelAdapter mPanelAdapter;
    private final RecyclerView mPanel = this;

    // data
    private final List<RowData> mRows = new ArrayList<>();
    private final Map<Integer, VideoGroupObjectAdapter> mRowAdapters = new HashMap<>();
    private VideoGroupObjectAdapter mGridAdapter;
    private int mGridRowCount;

    // focus
    private int mFocusRow;
    private int mFocusCol;
    private boolean mFocusOnContentPending;
    private int mPendingIndex = -1;
    private Video mPendingVideo;

    public RavPosterPanel(Context context, Callback callback) {
        super(context);
        mPresenter = callback;
        setLayoutManager(new RavLayoutManager(context, LinearLayoutManager.VERTICAL, dp(ROW_H) * 2));
        setItemAnimator(null);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(0, dp(16), 0, 0);
        // Focusable itself, so a poster that is rebound while it has focus hands focus to the panel and not to
        // whatever Android finds nearby (a section tile would then switch the section).
        setFocusable(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= 26) {
            setDefaultFocusHighlightEnabled(false);
        }
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setOnFocusChangeListener((v, has) -> {
            if (has && getFocusedChild() == null) {
                mHandler.post(this::focusContent); // the panel holds focus only until a poster is back
            }
        });
        mPanelAdapter = new PanelAdapter();
        setAdapter(mPanelAdapter);
        mPanelGlide = new RavGlide(this, false);
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // room below the last row, so any row can scroll up to the top
            int bottom = Math.max(0, (b - t) - dp(16) - dp(ROW_H));
            if (getPaddingBottom() != bottom) {
                post(() -> setPadding(0, dp(16), 0, bottom));
            }
        });
    }

    private int dp(float v) {
        return RavStyle.dp(getContext(), v);
    }

    // ------------------------------------------------------------------ public API

    public int getMode() {
        return mMode;
    }

    /** Switches between rows and grid; clears the content. */
    public void setMode(int mode) {
        mMode = mode;
        clear();
    }

    public void clear() {
        mRows.clear();
        mRowAdapters.clear();
        mGridAdapter = null;
        mGridRowCount = 0;
        mFocusRow = 0;
        mFocusCol = 0;
        mPanelAdapter.notifyDataSetChanged();
        scrollToPosition(0);
        mPanelGlide.reset(0);
    }

    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }
        if (mMode == MODE_ROWS) {
            updateRows(group);
        } else if (mMode == MODE_GRID) {
            updateGrid(group);
        } else if (mMode == MODE_CHANNELS) {
            if (group.getPosition() == 0) {
                updateChannels(group);
            } else {
                updateGrid(group);
            }
        }
        afterUpdate();
    }

    public boolean isEmpty() {
        return mMode == MODE_GRID ? mGridAdapter == null || mGridAdapter.isEmpty() : mRows.isEmpty();
    }

    /** Channels mode: the channel row sits above the grid. */
    private int gridOffset() {
        return mMode == MODE_CHANNELS && !mRows.isEmpty() ? 1 : 0;
    }

    /** Rows that scroll sideways (rows mode, or the channel row). */
    private boolean isScrollRow(int row) {
        return mMode == MODE_ROWS || (mMode == MODE_CHANNELS && row < gridOffset());
    }

    public void setFocusOnContentPending(boolean pending) {
        mFocusOnContentPending = pending;
    }

    /** Focuses the remembered poster, or the first one when the content arrives. */
    public void focusContent() {
        if (rowCount() == 0) {
            mFocusOnContentPending = true;
            return;
        }
        int row = Math.max(0, Math.min(mFocusRow, rowCount() - 1));
        int col = Math.max(0, Math.min(mFocusCol, rowSize(row) - 1));
        focusCard(row, col);
    }

    /** Rows: the row index. Grid: the item index. */
    public void selectIndex(int index) {
        if (index < 0) {
            return;
        }
        mPendingIndex = index;
        mPendingVideo = null;
        applyPendingSelection();
    }

    public void selectVideo(Video video) {
        if (video == null) {
            return;
        }
        mPendingVideo = video;
        mPendingIndex = -1;
        applyPendingSelection();
    }

    /** Rows: the focused row. Grid: the focused item index. */
    public int getPosition() {
        if (mMode == MODE_CHANNELS) {
            return mFocusRow == 0 ? mFocusCol : 0;
        }
        return mMode == MODE_GRID ? mFocusRow * GRID_COLS + mFocusCol : mFocusRow;
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ data updates

    private void updateRows(VideoGroup group) {
        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            if (group.getPosition() == -1) {
                clear();
            } else {
                removeRowById(group.getId());
            }
        } else if (action == VideoGroup.ACTION_REMOVE) {
            VideoGroupObjectAdapter adapter = mRowAdapters.get(group.getId());
            if (adapter != null) {
                adapter.remove(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            VideoGroupObjectAdapter adapter = mRowAdapters.get(group.getId());
            if (adapter != null) {
                adapter.removeAuthor(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_SYNC) {
            VideoGroupObjectAdapter adapter = mRowAdapters.get(group.getId());
            if (adapter != null) {
                adapter.sync(group);
            }
            return;
        }

        if (group.isEmpty()) {
            return;
        }

        VideoGroupObjectAdapter existing = GridFragmentHelper.findRelatedAdapter(mRowAdapters, group, freeze -> { });

        if (existing == null) {
            RowData row = new RowData(group);
            mRowAdapters.put(group.getId(), row.adapter);
            int pos = group.getPosition();
            if (pos == -1 || pos > mRows.size()) {
                mRows.add(row);
                mPanelAdapter.notifyItemInserted(mRows.size() - 1);
            } else {
                mRows.add(pos, row);
                mPanelAdapter.notifyItemInserted(pos);
            }
        } else {
            existing.add(group); // continue
        }
    }

    private void removeRowById(int id) {
        VideoGroupObjectAdapter adapter = mRowAdapters.remove(id);
        if (adapter == null) {
            return;
        }
        for (int i = 0; i < mRows.size(); i++) {
            if (mRows.get(i).adapter == adapter) {
                mRows.remove(i);
                mPanelAdapter.notifyItemRemoved(i);
                return;
            }
        }
    }

    private void updateChannels(VideoGroup group) {
        int action = group.getAction();
        VideoGroupObjectAdapter adapter = mRows.isEmpty() ? null : mRows.get(0).adapter;

        if (action == VideoGroup.ACTION_REPLACE) {
            mRows.clear();
            mRowAdapters.clear();
            adapter = null;
            mPanelAdapter.notifyDataSetChanged();
        } else if (action == VideoGroup.ACTION_REMOVE) {
            if (adapter != null) {
                adapter.remove(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            if (adapter != null) {
                adapter.removeAuthor(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_SYNC) {
            if (adapter != null) {
                adapter.sync(group);
            }
            return;
        }

        if (group.isEmpty()) {
            return;
        }

        if (adapter == null) {
            RowData row = new RowData(group, true);
            mRows.add(row);
            mPanelAdapter.notifyItemInserted(0);
        } else {
            adapter.add(group);
        }
    }

    /** Channels mode: a new channel's videos replace only the grid. */
    private void clearGrid() {
        int oldRows = mGridRowCount;
        mGridAdapter = null;
        mGridRowCount = 0;
        if (oldRows > 0) {
            mPanelAdapter.notifyItemRangeRemoved(gridOffset(), oldRows); // the channel row stays untouched
        }
        if (mFocusRow >= gridOffset()) {
            mFocusRow = 0;
        }
    }

    private void updateGrid(VideoGroup group) {
        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            if (mMode == MODE_CHANNELS) {
                clearGrid();
            } else {
                clear();
            }
        } else if (action == VideoGroup.ACTION_REMOVE) {
            if (mGridAdapter != null) {
                mGridAdapter.remove(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            if (mGridAdapter != null) {
                mGridAdapter.removeAuthor(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_SYNC) {
            if (mGridAdapter != null) {
                mGridAdapter.sync(group);
            }
            return;
        }

        if (group.isEmpty()) {
            return;
        }

        if (mGridAdapter == null) {
            mGridAdapter = new VideoGroupObjectAdapter(group, (Presenter) null);
            mGridAdapter.registerObserver(new GridObserver(mGridAdapter));
            mGridRowCount = gridRows();
            mPanelAdapter.notifyItemRangeInserted(gridOffset(), mGridRowCount);
        } else {
            mGridAdapter.add(group);
        }
    }

    private int gridRows() {
        return mGridAdapter == null ? 0 : (mGridAdapter.size() + GRID_COLS - 1) / GRID_COLS;
    }

    private final class GridObserver extends ObjectAdapter.DataObserver {
        private final VideoGroupObjectAdapter mAdapter;

        GridObserver(VideoGroupObjectAdapter adapter) {
            mAdapter = adapter;
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            if (mAdapter != mGridAdapter) {
                return;
            }
            int oldRows = mGridRowCount;
            int newRows = gridRows();
            int off = gridOffset();
            mGridRowCount = newRows;
            if (positionStart >= oldRows * GRID_COLS - GRID_COLS && oldRows > 0) {
                mPanelAdapter.notifyItemChanged(off + oldRows - 1); // the last row may have been partly filled
            }
            if (positionStart < (oldRows - 1) * GRID_COLS) {
                mPanelAdapter.notifyDataSetChanged(); // prepended
                refocusLater();
            } else if (newRows > oldRows) {
                mPanelAdapter.notifyItemRangeInserted(off + oldRows, newRows - oldRows);
            }
        }

        @Override
        public void onChanged() {
            reset();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            if (mAdapter == mGridAdapter) {
                mPanelAdapter.notifyItemRangeChanged(gridOffset() + positionStart / GRID_COLS,
                        (positionStart + itemCount - 1) / GRID_COLS - positionStart / GRID_COLS + 1);
            }
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            reset();
        }

        private void reset() {
            if (mAdapter != mGridAdapter) {
                return;
            }
            mGridRowCount = gridRows();
            mPanelAdapter.notifyDataSetChanged();
            refocusLater();
        }
    }

    private void afterUpdate() {
        applyPendingSelection();

        if (mFocusOnContentPending && rowCount() > 0) {
            mFocusOnContentPending = false;
            mHandler.post(this::focusContent);
        }
    }

    private void applyPendingSelection() {
        if (mMode == MODE_NONE) {
            return;
        }

        int row = -1;
        int col = 0;

        if (mMode == MODE_CHANNELS) {
            RowData channels = mRows.isEmpty() ? null : mRows.get(0);
            if (channels == null) {
                return;
            }
            int idx = mPendingVideo != null ? channels.adapter.indexOfAlt(mPendingVideo) : mPendingIndex;
            if (idx < 0 || idx >= channels.adapter.size()) {
                return;
            }
            mPendingVideo = null;
            mPendingIndex = -1;
            row = 0;
            col = idx;
            mPendingVideo = null;
        } else if (mPendingVideo != null) {
            if (mMode == MODE_GRID && mGridAdapter != null) {
                int idx = mGridAdapter.indexOfAlt(mPendingVideo);
                if (idx != -1) {
                    row = idx / GRID_COLS;
                    col = idx % GRID_COLS;
                }
            } else if (mMode == MODE_ROWS) {
                for (int i = 0; i < mRows.size(); i++) {
                    int idx = mRows.get(i).adapter.indexOfAlt(mPendingVideo);
                    if (idx != -1) {
                        row = i;
                        col = idx;
                        break;
                    }
                }
            }
            if (row == -1) {
                loadMoreForRestore();
                return;
            }
            mPendingVideo = null;
        } else if (mPendingIndex >= 0) {
            if (mMode == MODE_ROWS && mPendingIndex < mRows.size()) {
                row = mPendingIndex;
            } else if (mMode == MODE_GRID && mGridAdapter != null && mPendingIndex < mGridAdapter.size()) {
                row = mPendingIndex / GRID_COLS;
                col = mPendingIndex % GRID_COLS;
            } else {
                loadMoreForRestore();
                return;
            }
            mPendingIndex = -1;
        } else {
            return;
        }

        boolean contentFocused = mPanel.hasFocus();
        mFocusRow = row;
        mFocusCol = col;
        if (contentFocused || mFocusOnContentPending) {
            mFocusOnContentPending = false;
            int r = row;
            int c = col;
            mHandler.post(() -> focusCard(r, c));
        } else {
            mPanelGlide.to(row * dp(ROW_H));
            if (isScrollRow(row)) {
                slideRow(row, col);
            }
        }
    }

    /** The item to restore is not loaded yet: look it up in the next page of a grid. */
    private void loadMoreForRestore() {
        if (mMode == MODE_GRID && mGridAdapter != null && !mGridAdapter.isEmpty() && mGridAdapter.size() < RESTORE_MAX_SIZE
                && !mPresenter.hasPendingActions()) {
            mPresenter.onScrollEnd((Video) mGridAdapter.get(mGridAdapter.size() - 1));
        }
    }

    // ------------------------------------------------------------------ focus + motion

    private int rowCount() {
        switch (mMode) {
            case MODE_ROWS:
                return mRows.size();
            case MODE_GRID:
                return mGridRowCount;
            case MODE_CHANNELS:
                return gridOffset() + mGridRowCount;
        }
        return 0;
    }

    private int rowSize(int row) {
        if (isScrollRow(row)) {
            return row >= 0 && row < mRows.size() ? mRows.get(row).adapter.size() : 0;
        }
        if (mGridAdapter != null) {
            int g = row - gridOffset();
            return g < 0 ? 0 : Math.max(0, Math.min(GRID_COLS, mGridAdapter.size() - g * GRID_COLS));
        }
        return 0;
    }

    private void focusCard(int row, int col) {
        if (row < 0 || row >= rowCount() || rowSize(row) == 0) {
            return;
        }
        col = Math.max(0, Math.min(col, rowSize(row) - 1));
        mFocusRow = row;
        mFocusCol = col;
        // the focused row sits at the top of the panel
        mPanelGlide.to(row * dp(ROW_H));
        if (isScrollRow(row)) {
            slideRow(row, col);
        }
        requestCardFocus(row, col, 0);
    }

    private void requestCardFocus(int row, int col, int attempt) {
        View card = findCard(row, col);
        if (card != null) {
            card.requestFocus();
        } else if (attempt < 20) {
            mHandler.postDelayed(() -> {
                if (mFocusRow == row && mFocusCol == col) {
                    requestCardFocus(row, col, attempt + 1);
                }
            }, 16);
        }
    }

    private View findCard(int row, int col) {
        RecyclerView.ViewHolder vh = mPanel.findViewHolderForAdapterPosition(row);
        if (vh instanceof RowHolder) {
            RecyclerView.ViewHolder card = ((RowHolder) vh).list.findViewHolderForAdapterPosition(col);
            return card != null ? card.itemView : null;
        }
        if (vh instanceof GridHolder) {
            CardHolder card = ((GridHolder) vh).cards[col];
            return card.itemView.getVisibility() == View.VISIBLE ? card.itemView : null;
        }
        return null;
    }

    private void refocusLater() {
        if (mPanel.hasFocus() || mPanel.isFocused()) {
            mHandler.post(this::focusContent);
        }
    }

    /**
     * Keep the selection in the middle column and slide the row underneath it, so the next poster is already
     * in view; only at the ends of the row does the selection move sideways.
     */
    private void slideRow(int row, int col) {
        if (row < 0 || row >= mRows.size() || !isScrollRow(row)) {
            return;
        }
        RowData data = mRows.get(row);
        int n = data.adapter.size();
        int slot = dp(CARD_W + GUTTER);
        int width = mPanel.getWidth();
        int visible = Math.max(1, (width - dp(EDGE) - dp(6) + dp(GUTTER)) / slot); // last card needs no gutter
        int first = Math.max(0, Math.min(col - visible / 2, Math.max(0, n - visible)));
        int max = Math.max(0, dp(EDGE) + n * slot + dp(GUTTER) - width);
        data.scrollX = Math.min(first * slot, max);
        data.first = first;
        RecyclerView.ViewHolder vh = mPanel.findViewHolderForAdapterPosition(row);
        if (vh instanceof RowHolder && ((RowHolder) vh).data == data) {
            ((RowHolder) vh).glide.to(data.scrollX);
        }
    }

    private boolean onCardKey(CardHolder h, int code) {
        int row = h.row();
        int col = h.col();
        if (row < 0 || col < 0) {
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focusCard(row, (col + 1) % Math.max(1, rowSize(row))); // wrap within the row
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col == 0) {
                    mPresenter.onExitLeft();
                } else {
                    focusCard(row, col - 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row + 1 < rowCount()) {
                    focusCard(row + 1, colInRow(row, col, row + 1));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) {
                    mPresenter.onExitUp();
                } else {
                    focusCard(row - 1, colInRow(row, col, row - 1));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                return mPresenter.onBack();
        }
        return false;
    }

    /** Same visible column in the other row. */
    private int colInRow(int from, int col, int to) {
        int visibleCol = isScrollRow(from) ? col - mRows.get(from).first : col;
        int target = isScrollRow(to) ? mRows.get(to).first + visibleCol : visibleCol;
        return Math.max(0, Math.min(target, rowSize(to) - 1));
    }

    private void onCardFocused(CardHolder h) {
        Video video = h.video;
        if (video == null) {
            return;
        }
        mFocusRow = h.row();
        mFocusCol = h.col();
        mPresenter.onVideoItemSelected(video);

        // load the next page near the end
        if (h.data != null) {
            int size = h.data.adapter.size();
            if (mFocusCol > size - ViewUtil.ROW_SCROLL_CONTINUE_NUM) {
                mPresenter.onScrollEnd((Video) h.data.adapter.get(size - 1));
            }
        } else if (mGridAdapter != null) {
            int size = mGridAdapter.size();
            if ((mFocusRow - gridOffset()) * GRID_COLS + mFocusCol > size - ViewUtil.GRID_SCROLL_CONTINUE_NUM) {
                mPresenter.onScrollEnd((Video) mGridAdapter.get(size - 1));
            }
        }
    }

    // ------------------------------------------------------------------ cards

    private CardHolder makeCard(Context ctx) {
        return makeCard(ctx, false);
    }

    /** @param channel a round avatar with the channel's name instead of a video poster */
    private CardHolder makeCard(Context ctx, boolean channel) {
        FrameLayout card = new FrameLayout(ctx);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        card.setAlpha(0.9f);
        card.setBackground(rect(TILE_BG, dp(8)));
        rounded(card, dp(8));

        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setColorFilter(washFilter(1f));
        if (channel) {
            FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            avatarLp.topMargin = dp(14);
            img.setBackground(oval(0x22FFFFFF));
            card.addView(img, avatarLp);
        } else {
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        }

        // glass: a faint sheen over the top half
        View sheen = new View(ctx);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x24FFFFFF, 0x00FFFFFF}));
        card.addView(sheen, new FrameLayout.LayoutParams(-1, (int) (dp(CARD_H) * 0.5f), Gravity.TOP));

        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0x8C000000, 0xD9000000, 0xF2000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, (int) (dp(CARD_H) * 0.62f), Gravity.BOTTOM));

        LinearLayout labels = new LinearLayout(ctx);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        font(title);
        title.setTextSize(12);
        title.setTextColor(0xD9FFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(4), 0, dp(1), 0xFF000000);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView meta = new TextView(ctx);
        meta.setTextSize(8.5f);
        meta.setTextColor(0x8CFFFFFF);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(1), 0, 0);
        labels.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams labelsLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        labelsLp.setMargins(dp(12), 0, dp(12), dp(10));
        card.addView(labels, labelsLp);

        TextView badge = new TextView(ctx);
        sansMedium(badge);
        badge.setTextSize(9);
        badge.setTextColor(0xE6FFFFFF);
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgeLp.setMargins(0, dp(6), dp(6), 0);
        card.addView(badge, badgeLp);

        FrameLayout track = new FrameLayout(ctx);
        track.setBackgroundColor(0x66000000);
        View bar = new View(ctx);
        bar.setBackgroundColor(AMBER);
        track.addView(bar, new FrameLayout.LayoutParams(0, -1));
        card.addView(track, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));

        if (Build.VERSION.SDK_INT >= 23) {
            card.setForeground(glassEdge(ctx)); // stays on when focused: the glass zooms with the poster
        }

        CardHolder h = new CardHolder(card, img, title, meta, badge, track, bar, labelsLp);
        h.channel = channel;
        if (channel) {
            sheen.setVisibility(View.GONE);
            shade.setVisibility(View.GONE);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setShadowLayer(0, 0, 0, 0);
        }

        card.setOnFocusChangeListener((v, has) -> {
            animateWash(h, has ? 0f : 1f);
            v.animate().scaleX(has ? CARD_ZOOM : 1f).scaleY(has ? CARD_ZOOM : 1f).setDuration(160).start();
            if (Build.VERSION.SDK_INT >= 21) {
                v.setTranslationZ(has ? 1f : 0f);
            }
            h.title.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            if (has) {
                onCardFocused(h);
            }
        });
        card.setOnClickListener(v -> {
            if (h.video != null) {
                mPresenter.onVideoItemClicked(h.video);
            }
        });
        card.setOnLongClickListener(v -> {
            if (h.video != null) {
                mPresenter.onVideoItemLongClicked(h.video);
            }
            return true;
        });
        card.setOnKeyListener((v, code, ev) -> ev.getAction() == KeyEvent.ACTION_DOWN && onCardKey(h, code));

        return h;
    }

    /** Fades the wash on the poster (and the card's dim) instead of popping it. */
    private void animateWash(CardHolder h, float target) {
        if (h.washAnim != null) {
            h.washAnim.cancel();
        }
        ValueAnimator anim = ValueAnimator.ofFloat(h.wash, target);
        anim.setDuration(200);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            h.wash = t;
            h.img.setColorFilter(washFilter(t));
            h.itemView.setAlpha(1f - 0.1f * t);
        });
        h.washAnim = anim;
        anim.start();
    }

    private void bindCard(CardHolder h, Video video) {
        h.video = video;
        if (video == null) {
            h.itemView.setVisibility(View.INVISIBLE);
            h.itemView.setFocusable(false);
            return;
        }
        h.itemView.setVisibility(View.VISIBLE);
        h.itemView.setFocusable(true);

        if (h.channel) {
            boolean focusedChannel = h.itemView.hasFocus();
            h.wash = focusedChannel ? 0f : 1f;
            h.img.setColorFilter(washFilter(h.wash));
            h.itemView.setAlpha(1f - 0.1f * h.wash);
            h.title.setText(video.getAuthor() != null ? video.getAuthor() : video.getTitle());
            h.meta.setVisibility(View.GONE);
            h.badge.setVisibility(View.GONE);
            h.track.setVisibility(View.GONE);
            h.labelsLp.bottomMargin = dp(12);
            loadImage(h.img, video.getCardImageUrl(), dp(56), dp(56), true);
            return;
        }

        boolean focused = h.itemView.hasFocus();
        h.wash = focused ? 0f : 1f;
        h.img.setColorFilter(washFilter(h.wash));
        h.itemView.setAlpha(1f - 0.1f * h.wash);
        if (!focused) {
            h.itemView.setScaleX(1f);
            h.itemView.setScaleY(1f);
        }

        h.title.setText(video.getTitle());
        CharSequence second = video.getSecondTitle();
        h.meta.setText(second != null ? second : video.getAuthor());
        h.meta.setVisibility(TextUtils.isEmpty(h.meta.getText()) ? View.GONE : View.VISIBLE);

        if (video.isLive) {
            h.badge.setText("LIVE");
            h.badge.setBackground(rect(LIVE_RED, dp(3)));
            h.badge.setVisibility(View.VISIBLE);
        } else if (!TextUtils.isEmpty(video.badge)) {
            h.badge.setText(video.badge);
            h.badge.setBackground(rect(0x99000000, dp(3)));
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }

        boolean progress = video.percentWatched > 0;
        h.track.setVisibility(progress ? View.VISIBLE : View.GONE);
        if (progress) {
            float f = Math.max(0.02f, Math.min(1f, video.percentWatched / 100f));
            h.bar.getLayoutParams().width = (int) (dp(CARD_W) * f);
            h.bar.requestLayout();
        }
        h.labelsLp.bottomMargin = dp(progress ? 12 : 10);

        loadImage(h.img, video.getCardImageUrl(), dp(CARD_W), dp(CARD_H), false);
    }

    static void loadImage(ImageView view, String url, int w, int h, boolean circle) {
        try {
            if (url == null) {
                Glide.with(view.getContext()).clear(view);
                view.setImageDrawable(null);
                return;
            }
            RequestOptions options = (circle ? RequestOptions.circleCropTransform() : RequestOptions.centerCropTransform())
                    .override(w, h);
            Glide.with(view.getContext()).load(url).apply(options).into(view);
        } catch (IllegalArgumentException e) {
            // You cannot start a load for a destroyed activity
        }
    }

    // ------------------------------------------------------------------ adapters

    private final class RowData {
        final VideoGroupObjectAdapter adapter;
        final String title;
        final CardAdapter cards;
        final boolean channels;
        int scrollX;
        int first;

        RowData(VideoGroup group) {
            this(group, false);
        }

        RowData(VideoGroup group, boolean channels) {
            this.channels = channels;
            adapter = new VideoGroupObjectAdapter(group, (Presenter) null);
            title = group.getTitle();
            cards = new CardAdapter(this);
            adapter.registerObserver(new ObjectAdapter.DataObserver() {
                @Override
                public void onChanged() {
                    cards.notifyDataSetChanged();
                    refocusLater();
                }

                @Override
                public void onItemRangeChanged(int positionStart, int itemCount) {
                    cards.notifyItemRangeChanged(positionStart, itemCount);
                }

                @Override
                public void onItemRangeInserted(int positionStart, int itemCount) {
                    cards.notifyItemRangeInserted(positionStart, itemCount);
                }

                @Override
                public void onItemRangeRemoved(int positionStart, int itemCount) {
                    cards.notifyItemRangeRemoved(positionStart, itemCount);
                    refocusLater();
                }
            });
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView img;
        final TextView title;
        final TextView meta;
        final TextView badge;
        final View track;
        final View bar;
        final FrameLayout.LayoutParams labelsLp;
        Video video;
        RowData data; // rows mode
        boolean channel;
        int gridRow = -1; // grid mode
        int gridCol = -1;
        float wash = 1f;
        ValueAnimator washAnim;

        CardHolder(View card, ImageView img, TextView title, TextView meta, TextView badge, View track, View bar,
                   FrameLayout.LayoutParams labelsLp) {
            super(card);
            this.img = img;
            this.title = title;
            this.meta = meta;
            this.badge = badge;
            this.track = track;
            this.bar = bar;
            this.labelsLp = labelsLp;
        }

        int row() {
            return data != null ? mRows.indexOf(data) : gridRow;
        }

        int col() {
            return data != null ? getAdapterPosition() : gridCol;
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final RowData mData;

        CardAdapter(RowData data) {
            mData = data;
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            CardHolder h = makeCard(parent.getContext(), mData.channels);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(CARD_W), dp(CARD_H));
            lp.rightMargin = dp(GUTTER);
            h.itemView.setLayoutParams(lp);
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            holder.data = mData;
            bindCard(holder, (Video) mData.adapter.get(position));
        }

        @Override
        public int getItemCount() {
            return mData.adapter.size();
        }
    }

    private TextView sectionHeader(Context ctx) {
        TextView tv = new TextView(ctx);
        sansMedium(tv);
        tv.setTextSize(8.5f);
        if (Build.VERSION.SDK_INT >= 21) {
            tv.setLetterSpacing(0.1f);
        }
        tv.setTextColor(0x66FFFFFF);
        tv.setGravity(Gravity.BOTTOM);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        // sit just above where a zoomed poster reaches, so the title never moves and is never covered
        tv.setPadding(dp(EDGE + 2), 0, 0, (int) (dp(CARD_H) * (CARD_ZOOM - 1f) / 2f) + dp(1));
        return tv;
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final RecyclerView list;
        final TextView header;
        final RavGlide glide;
        RowData data;

        RowHolder(FrameLayout root, RecyclerView list, TextView header) {
            super(root);
            this.list = list;
            this.header = header;
            this.glide = new RavGlide(list, true);
        }
    }

    private final class GridHolder extends RecyclerView.ViewHolder {
        final CardHolder[] cards;
        final TextView header;

        GridHolder(FrameLayout root, CardHolder[] cards, TextView header) {
            super(root);
            this.cards = cards;
            this.header = header;
        }
    }

    private final class PanelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return isScrollRow(position) ? TYPE_ROW : TYPE_GRID;
        }

        @Override
        public int getItemCount() {
            return rowCount();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            FrameLayout root = new FrameLayout(ctx);
            root.setClipChildren(false);
            root.setClipToPadding(false);
            root.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(ROW_H)));
            TextView header = sectionHeader(ctx);

            if (viewType == TYPE_GRID) {
                LinearLayout line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                line.setClipToPadding(false);
                line.setPadding(dp(EDGE), dp(GUTTER / 2f), 0, dp(GUTTER / 2f));
                CardHolder[] cards = new CardHolder[GRID_COLS];
                for (int i = 0; i < GRID_COLS; i++) {
                    cards[i] = makeCard(ctx);
                    cards[i].gridCol = i;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H));
                    lp.rightMargin = dp(GUTTER);
                    line.addView(cards[i].itemView, lp);
                }
                root.addView(line, new FrameLayout.LayoutParams(-1, -1));
                root.addView(header, new FrameLayout.LayoutParams(-1, dp(GUTTER / 2f), Gravity.TOP));
                return new GridHolder(root, cards, header);
            }

            RecyclerView list = new RecyclerView(ctx);
            list.setLayoutManager(new RavLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, dp(CARD_W + GUTTER) * 2));
            list.setItemAnimator(null);
            list.setClipChildren(false);
            list.setClipToPadding(false);
            list.setFocusable(false);
            list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list.setHorizontalScrollBarEnabled(false);
            list.setPadding(dp(EDGE), dp(GUTTER / 2f), dp(GUTTER), dp(GUTTER / 2f));
            root.addView(list, new FrameLayout.LayoutParams(-1, -1));
            root.addView(header, new FrameLayout.LayoutParams(-1, dp(GUTTER / 2f), Gravity.TOP));
            return new RowHolder(root, list, header);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof RowHolder) {
                RowHolder h = (RowHolder) holder;
                RowData data = mRows.get(position);
                setHeader(h.header, data.title);
                if (h.data != data) {
                    h.data = data;
                    h.list.setAdapter(data.cards);
                    int slot = dp(CARD_W + GUTTER);
                    ((LinearLayoutManager) h.list.getLayoutManager())
                            .scrollToPositionWithOffset(data.scrollX / slot, -(data.scrollX % slot));
                    h.glide.reset(data.scrollX);
                }
            } else if (holder instanceof GridHolder) {
                GridHolder h = (GridHolder) holder;
                setHeader(h.header, null);
                for (int i = 0; i < GRID_COLS; i++) {
                    int idx = (position - gridOffset()) * GRID_COLS + i;
                    CardHolder card = h.cards[i];
                    card.gridRow = position;
                    bindCard(card, mGridAdapter != null && idx < mGridAdapter.size() ? (Video) mGridAdapter.get(idx) : null);
                }
            }
        }

        private void setHeader(TextView header, String title) {
            boolean show = !TextUtils.isEmpty(title);
            header.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                header.setText(title.toUpperCase(Locale.getDefault()));
            }
        }
    }

    /** LinearLayoutManager that never scrolls by itself on focus (we glide it) and lays out neighbours early. */
    private static final class RavLayoutManager extends LinearLayoutManager {
        private final int mExtra;

        RavLayoutManager(Context context, int orientation, int extraPx) {
            super(context, orientation, false);
            mExtra = extraPx;
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect,
                                                     boolean immediate, boolean focusedChildVisible) {
            return false;
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect,
                                                     boolean immediate) {
            return false;
        }

        @Override
        protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
            extraLayoutSpace[0] = mExtra;
            extraLayoutSpace[1] = mExtra;
        }
    }

}
