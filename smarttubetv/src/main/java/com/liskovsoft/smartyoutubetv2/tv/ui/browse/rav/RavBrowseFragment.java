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
 * RavTube overview: the RavLauncher layout (section tiles on the left, a calm header, poster rows or a
 * 3-column grid) on top of the unchanged {@link BrowsePresenter}.<br/>
 * Video sections (rows and grids) are drawn here. Settings, channel lists (multi grid) and errors reuse
 * the existing fragments, hosted in the content area.
 */
public class RavBrowseFragment extends Fragment implements BrowseView {
    private static final int MODE_NONE = 0;
    private static final int MODE_ROWS = 1;
    private static final int MODE_GRID = 2;
    private static final int MODE_HOSTED = 3;
    private static final int GRID_COLS = 3;
    private static final int RESTORE_MAX_SIZE = 200;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private BrowsePresenter mPresenter;
    private CrashRestorer mCrashRestorer;
    private boolean mIsFragmentCreated;

    // sections
    private final List<BrowseSection> mSections = new ArrayList<>();
    private final Map<Integer, View> mTiles = new HashMap<>();
    private int mSelectedId = -1;
    private int mMode = MODE_NONE;

    // views
    private RavBokehView mBokeh;
    private TileScrollView mTileScroll;
    private RavGlide mTileGlide;
    private LinearLayout mTileColumn;
    private TextView mTitle;
    private TextView mClock;
    private View mSearchBtn;
    private View mSettingsBtn;
    private RecyclerView mPanel;
    private RavGlide mPanelGlide;
    private PanelAdapter mPanelAdapter;
    private FrameLayout mHost;
    private ProgressBar mProgress;
    private Fragment mHosted;
    private boolean mErrorShown;

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

    private final Runnable mRenderSelected = this::renderSelected;
    private Runnable mNotifySectionFocused;
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
    private final Runnable mRebuildTiles = this::rebuildTiles;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(null);

        if (getContext() == null) {
            return;
        }

        mCrashRestorer = new CrashRestorer(getContext(), savedInstanceState);
        mIsFragmentCreated = true;
        mPresenter = BrowsePresenter.instance(getContext());
        mPresenter.setView(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildLayout(inflater.getContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mPresenter.onViewInitialized();

        // Restore state after crash
        mCrashRestorer.restoreHeader((idx, video) -> {
            selectSection(idx, true);
            selectSectionItem(video);
        });
        mCrashRestorer.restorePlayback();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mCrashRestorer.persistHeaderIndex(outState, indexOfSection(mSelectedId));
        mCrashRestorer.persistVideo(outState, mPresenter.getCurrentVideo());
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
    public void onResume() {
        super.onResume();
        if (!mIsFragmentCreated) {
            mPresenter.onViewResumed();
        }
        mIsFragmentCreated = false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!mIsFragmentCreated) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }

    /** Back from the content (or a hosted fragment) goes to the section tiles. */
    public boolean handleBack() {
        if (mTileColumn != null && !mTileColumn.hasFocus()) {
            focusTile();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ layout

    private int dp(float v) {
        return RavStyle.dp(requireContext(), v);
    }

    private View buildLayout(Context ctx) {
        FrameLayout root = new FrameLayout(ctx);
        root.setClipChildren(true); // posters never slide over the header or the tiles

        mBokeh = new RavBokehView(ctx);
        root.addView(mBokeh, new FrameLayout.LayoutParams(-1, -1));

        // section tiles
        mTileColumn = new LinearLayout(ctx);
        mTileColumn.setOrientation(LinearLayout.VERTICAL);
        mTileColumn.setPadding(dp(18), dp((HEADER_H - TILE_H) / 2f), dp(18), dp(36));
        mTileColumn.setClipChildren(false);
        mTileColumn.setClipToPadding(false);
        mTileScroll = new TileScrollView(ctx);
        mTileScroll.setVerticalScrollBarEnabled(false);
        mTileScroll.setClipChildren(false);
        mTileScroll.setClipToPadding(false);
        mTileScroll.addView(mTileColumn);
        mTileGlide = new RavGlide(mTileScroll, false);
        FrameLayout.LayoutParams tileLp = new FrameLayout.LayoutParams(dp(124), -1);
        tileLp.leftMargin = dp(28);
        root.addView(mTileScroll, tileLp);

        // header
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        mTitle = new TextView(ctx);
        font(mTitle);
        mTitle.setTextSize(30);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 21) {
            mTitle.setLetterSpacing(0.01f);
        }
        header.addView(mTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        mClock = new TextView(ctx);
        font(mClock);
        mClock.setTextSize(19);
        mClock.setTextColor(0xB3FFFFFF);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-2, -2);
        clockLp.rightMargin = dp(14);
        header.addView(mClock, clockLp);
        mSearchBtn = roundButton(ctx, R.drawable.rav_ic_search, () -> SearchPresenter.instance(getContext()).startSearch(null));
        mSettingsBtn = roundButton(ctx, R.drawable.rav_ic_settings, this::openSettings);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        searchLp.rightMargin = dp(10);
        header.addView(mSearchBtn, searchLp);
        header.addView(mSettingsBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(-1, dp(HEADER_H));
        headerLp.leftMargin = dp(208);
        headerLp.rightMargin = dp(48);
        root.addView(header, headerLp);

        // poster panel
        mPanel = new RecyclerView(ctx);
        mPanel.setLayoutManager(new RavLayoutManager(ctx, LinearLayoutManager.VERTICAL, dp(ROW_H) * 2));
        mPanel.setItemAnimator(null);
        mPanel.setClipChildren(false);
        mPanel.setClipToPadding(false);
        mPanel.setPadding(0, dp(16), 0, 0);
        mPanel.setFocusable(false);
        mPanel.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mPanelAdapter = new PanelAdapter();
        mPanel.setAdapter(mPanelAdapter);
        mPanelGlide = new RavGlide(mPanel, false);
        mPanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // room below the last row, so any row can scroll up to the top
            int bottom = Math.max(0, (b - t) - dp(16) - dp(ROW_H));
            if (mPanel.getPaddingBottom() != bottom) {
                mPanel.post(() -> mPanel.setPadding(0, dp(16), 0, bottom));
            }
        });
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -1);
        panelLp.leftMargin = dp(208);
        panelLp.topMargin = dp(HEADER_H);
        panelLp.rightMargin = dp(42); // 6 dp into the right padding: room for the zoom of the last column
        root.addView(mPanel, panelLp);

        // hosted fragments (settings, channel lists, errors)
        mHost = new FrameLayout(ctx);
        mHost.setId(View.generateViewId());
        mHost.setVisibility(View.GONE);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(-1, -1);
        hostLp.leftMargin = dp(168);
        hostLp.topMargin = dp(HEADER_H - 40);
        root.addView(mHost, hostLp);

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

    private View roundButton(Context ctx, int icon, Runnable onClick) {
        ImageView btn = new ImageView(ctx);
        btn.setImageResource(icon);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));
        btn.setAlpha(0.75f);
        btn.setFocusable(true);
        btn.setBackground(oval(0x22FFFFFF));
        btn.setOnClickListener(v -> onClick.run());
        btn.setOnFocusChangeListener((v, has) -> {
            ((GradientDrawable) v.getBackground()).setColor(has ? 0xFFFFFFFF : 0x22FFFFFF);
            btn.setColorFilter(has ? 0xFF101020 : Color.WHITE);
            v.setAlpha(has ? 1f : 0.75f);
        });
        btn.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusContent();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    View other = v == mSearchBtn ? mSettingsBtn : mSearchBtn;
                    if (other.getVisibility() == View.VISIBLE) {
                        other.requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    focusTile();
                    return true;
            }
            return false;
        });
        return btn;
    }

    // ------------------------------------------------------------------ section tiles

    private int indexOfSection(int id) {
        for (int i = 0; i < mSections.size(); i++) {
            if (mSections.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    private BrowseSection findSection(int id) {
        int i = indexOfSection(id);
        return i == -1 ? null : mSections.get(i);
    }

    private static boolean isTile(BrowseSection section) {
        return section.getType() != BrowseSection.TYPE_SETTINGS_GRID; // settings live behind the header button
    }

    private void scheduleRebuildTiles() {
        mHandler.removeCallbacks(mRebuildTiles);
        mHandler.post(mRebuildTiles);
    }

    private void rebuildTiles() {
        if (mTileColumn == null) {
            return;
        }
        boolean hadFocus = mTileColumn.hasFocus();
        mTileColumn.removeAllViews();
        mTiles.clear();
        for (BrowseSection section : mSections) {
            View tile = makeTile(section);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H));
            lp.bottomMargin = dp(12);
            mTileColumn.addView(tile, lp);
            tile.setVisibility(isTile(section) ? View.VISIBLE : View.GONE);
            mTiles.put(section.getId(), tile);
        }
        mSettingsBtn.setVisibility(settingsSection() != null ? View.VISIBLE : View.GONE);

        if (mSelectedId == -1 || findSection(mSelectedId) == null) {
            View first = firstVisibleTile();
            if (first != null) {
                for (BrowseSection s : mSections) {
                    if (mTiles.get(s.getId()) == first) {
                        selectTile(s.getId(), 0);
                        break;
                    }
                }
            }
        }
        updateTileStates();

        View root = getView();
        if (hadFocus || (root != null && root.findFocus() == null)) {
            focusTile();
        }
    }

    private View makeTile(BrowseSection section) {
        Context ctx = requireContext();
        FrameLayout tile = new FrameLayout(ctx);
        tile.setFocusable(true);
        tile.setAlpha(0.6f);
        tile.setBackground(rect(TILE_BG, dp(6)));
        rounded(tile, dp(6));

        if (section.getId() == MediaGroup.TYPE_HOME) {
            tile.setBackground(null);
            ImageView logo = new ImageView(ctx);
            logo.setImageResource(R.drawable.rav_logo);
            logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            logo.setPadding(0, dp(10), 0, dp(10));
            tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(ctx);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (section.getResId() > 0) {
                icon.setImageResource(section.getResId());
                icon.setColorFilter(Color.WHITE);
            } else if (section.getIconUrl() != null) {
                loadImage(icon, section.getIconUrl(), dp(20), dp(20), true);
            }
            box.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView label = new TextView(ctx);
            font(label);
            label.setText(section.getTitle());
            label.setTextSize(9);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(4), dp(2), dp(4), 0);
            box.addView(label, new LinearLayout.LayoutParams(-1, -2));
            tile.addView(box, new FrameLayout.LayoutParams(-1, -1));
        }

        int id = section.getId();
        tile.setOnFocusChangeListener((v, has) -> {
            boolean keep = has || id == mSelectedId; // the active section stays zoomed while you browse it
            v.animate().scaleX(keep ? TILE_ZOOM : 1f).scaleY(keep ? TILE_ZOOM : 1f).setDuration(160).start();
            if (has) {
                selectTile(id, 180);
                centerTile(v);
            }
        });
        tile.setOnClickListener(v -> focusContent());
        tile.setOnLongClickListener(v -> {
            mPresenter.onSectionLongPressed(id);
            return true;
        });
        tile.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    focusContent();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveTileFocus(v, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveTileFocus(v, -1);
                    return true;
            }
            return false;
        });
        return tile;
    }

    /** No wrap: holding up ends on the first tile, holding down on the last. */
    private void moveTileFocus(View from, int dir) {
        int i = mTileColumn.indexOfChild(from) + dir;
        while (i >= 0 && i < mTileColumn.getChildCount()) {
            View t = mTileColumn.getChildAt(i);
            if (t.getVisibility() == View.VISIBLE) {
                t.requestFocus();
                return;
            }
            i += dir;
        }
    }

    private View firstVisibleTile() {
        for (int i = 0; i < mTileColumn.getChildCount(); i++) {
            View t = mTileColumn.getChildAt(i);
            if (t.getVisibility() == View.VISIBLE) {
                return t;
            }
        }
        return null;
    }

    private void centerTile(View v) {
        v.post(() -> {
            int max = Math.max(0, mTileColumn.getHeight() - mTileScroll.getHeight());
            int y = v.getTop() - (mTileScroll.getHeight() - v.getHeight()) / 2;
            mTileGlide.to(Math.max(0, Math.min(y, max)));
        });
    }

    private void focusTile() {
        View t = mTiles.get(mSelectedId);
        if (t == null || t.getVisibility() != View.VISIBLE) {
            t = firstVisibleTile();
        }
        if (t != null) {
            t.requestFocus();
        }
    }

    private void updateTileStates() {
        for (Map.Entry<Integer, View> e : mTiles.entrySet()) {
            View v = e.getValue();
            boolean selected = e.getKey() == mSelectedId;
            v.setAlpha(selected ? 1f : 0.6f);
            float scale = selected || v.hasFocus() ? TILE_ZOOM : 1f;
            if (v.getScaleX() != scale) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
            }
        }
    }

    private void selectTile(int id, long delayMs) {
        if (id == mSelectedId && mMode != MODE_NONE) {
            return;
        }
        mSelectedId = id;
        updateTileStates();
        BrowseSection section = findSection(id);
        mTitle.setText(section != null ? section.getTitle() : "");
        mHandler.removeCallbacks(mRenderSelected);
        mHandler.postDelayed(mRenderSelected, delayMs);
    }

    private BrowseSection settingsSection() {
        for (BrowseSection s : mSections) {
            if (s.getType() == BrowseSection.TYPE_SETTINGS_GRID) {
                return s;
            }
        }
        return null;
    }

    private void openSettings() {
        BrowseSection settings = settingsSection();
        if (settings != null) {
            mFocusOnContentPending = true;
            selectTile(settings.getId(), 0);
        }
    }

    // ------------------------------------------------------------------ section content

    private void renderSelected() {
        BrowseSection section = findSection(mSelectedId);
        if (section == null || getView() == null) {
            return;
        }

        removeHosted();
        mErrorShown = false;

        switch (section.getType()) {
            case BrowseSection.TYPE_ROW:
                setMode(MODE_ROWS);
                break;
            case BrowseSection.TYPE_GRID:
            case BrowseSection.TYPE_SHORTS_GRID:
                setMode(MODE_GRID);
                break;
            case BrowseSection.TYPE_SETTINGS_GRID:
                host(new SettingsGridFragment());
                break;
            case BrowseSection.TYPE_MULTI_GRID:
                host(new MultiVideoGridFragment());
                break;
            case BrowseSection.TYPE_ERROR:
                host(new ErrorDialogFragment((ErrorFragmentData) section.getData()));
                mErrorShown = true;
                break;
        }

        // give a chance to clear pending updates, then let the presenter load the section
        int id = section.getId();
        mHandler.removeCallbacks(mNotifySectionFocused);
        mNotifySectionFocused = () -> mPresenter.onSectionFocused(id);
        mHandler.postDelayed(mNotifySectionFocused, 100);
    }

    private void setMode(int mode) {
        mMode = mode;
        clearData();
        mPanel.setVisibility(View.VISIBLE);
    }

    private void clearData() {
        mRows.clear();
        mRowAdapters.clear();
        mGridAdapter = null;
        mGridRowCount = 0;
        mFocusRow = 0;
        mFocusCol = 0;
        mPanelAdapter.notifyDataSetChanged();
        mPanel.scrollToPosition(0);
        mPanelGlide.reset(0);
    }

    private void host(Fragment fragment) {
        if (fragment instanceof com.liskovsoft.smartyoutubetv2.tv.ui.mod.fragments.ErrorSupportFragment) {
            // no dark panel: the error sits on the bokeh background like everything else
            ((com.liskovsoft.smartyoutubetv2.tv.ui.mod.fragments.ErrorSupportFragment) fragment)
                    .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        mMode = MODE_HOSTED;
        mHosted = fragment;
        clearData();
        mPanel.setVisibility(View.GONE);
        mHost.setVisibility(View.VISIBLE);
        boolean focus = mFocusOnContentPending || (mHost.hasFocus());
        mFocusOnContentPending = false;
        getChildFragmentManager().beginTransaction()
                .replace(mHost.getId(), fragment)
                .runOnCommit(() -> {
                    clearErrorBackground(fragment);
                    if (focus) {
                        mHandler.postDelayed(this::focusContent, 200);
                    }
                })
                .commitAllowingStateLoss();
    }

    /** The error screen sits on the bokeh background like everything else, without a dark panel. */
    private static void clearErrorBackground(Fragment fragment) {
        View root = fragment.getView();
        if (!(fragment instanceof com.liskovsoft.smartyoutubetv2.tv.ui.mod.fragments.ErrorSupportFragment) || root == null) {
            return;
        }
        root.setBackground(null);
        View frame = root.findViewById(R.id.error_frame);
        if (frame != null) {
            frame.setBackground(null);
            if (Build.VERSION.SDK_INT >= 26) {
                // the focused frame would otherwise get Android's default focus highlight: a dark veil
                frame.setDefaultFocusHighlightEnabled(false);
            }
        }
    }

    private void removeHosted() {
        if (mHosted != null) {
            try {
                getChildFragmentManager().beginTransaction().remove(mHosted).commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                // NOP
            }
            mHosted = null;
        }
        mHost.setVisibility(View.GONE);
    }

    private boolean isPanelSection(BrowseSection section) {
        return section != null && (section.getType() == BrowseSection.TYPE_ROW
                || section.getType() == BrowseSection.TYPE_GRID || section.getType() == BrowseSection.TYPE_SHORTS_GRID);
    }

    // ------------------------------------------------------------------ BrowseView

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }
        int existing = indexOfSection(section.getId());
        if (existing != -1 && (index == -1 || existing == index)) {
            return;
        }
        if (existing != -1) {
            mSections.remove(existing);
        }
        if (index == -1 || index > mSections.size()) {
            mSections.add(section);
        } else {
            mSections.add(index, section);
        }
        scheduleRebuildTiles();
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }
        int i = indexOfSection(section.getId());
        if (i != -1) {
            mSections.remove(i);
            scheduleRebuildTiles();
        }
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        scheduleRebuildTiles();
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (index < 0 || mSections.isEmpty()) {
            return;
        }
        BrowseSection section = mSections.get(Math.min(index, mSections.size() - 1));
        mFocusOnContentPending = focusOnContent;

        if (section.getId() == mSelectedId && mMode != MODE_NONE) {
            // refresh the current section
            mPresenter.onSectionFocused(section.getId());
            if (focusOnContent) {
                focusContent();
            }
            return;
        }

        boolean tilesFocused = mTileColumn != null && mTileColumn.hasFocus();
        selectTile(section.getId(), 0);
        View tile = mTiles.get(section.getId());
        if (tilesFocused && !focusOnContent && tile != null && tile.getVisibility() == View.VISIBLE) {
            tile.requestFocus();
        }
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }

        if (mMode == MODE_HOSTED) {
            if (mHosted instanceof VideoSection) {
                ((VideoSection) mHosted).update(group);
                return;
            }
            BrowseSection section = findSection(mSelectedId);
            if (mErrorShown && isPanelSection(section)) {
                // restore after the error fragment
                removeHosted();
                mErrorShown = false;
                setMode(section.getType() == BrowseSection.TYPE_ROW ? MODE_ROWS : MODE_GRID);
            } else {
                return;
            }
        }

        if (mMode == MODE_ROWS) {
            updateRows(group);
        } else if (mMode == MODE_GRID) {
            updateGrid(group);
        }

        afterUpdate();
    }

    @Override
    public void updateSection(SettingsGroup group) {
        if (mHosted instanceof SettingsSection) {
            ((SettingsSection) mHosted).update(group);
        }
    }

    @Override
    public void clearSection(BrowseSection section) {
        if (mHosted instanceof Section) {
            ((Section) mHosted).clear();
        } else if (mMode == MODE_ROWS || mMode == MODE_GRID) {
            clearData();
        }
    }

    @Override
    public void selectSectionItem(int index) {
        if (index < 0) {
            return;
        }
        if (mHosted instanceof VideoSection) {
            ((VideoSection) mHosted).setPosition(index);
            return;
        }
        mPendingIndex = index;
        mPendingVideo = null;
        applyPendingSelection();
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item == null) {
            return;
        }
        if (mHosted instanceof VideoSection) {
            ((VideoSection) mHosted).selectItem(item);
            return;
        }
        mPendingVideo = item;
        mPendingIndex = -1;
        applyPendingSelection();
    }

    @Override
    public void showError(ErrorFragmentData data) {
        host(new ErrorDialogFragment(data));
        mErrorShown = true;
    }

    @Override
    public void showProgressBar(boolean show) {
        mHandler.post(() -> {
            if (mProgress != null) {
                mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgress != null && mProgress.getVisibility() == View.VISIBLE;
    }

    @Override
    public void focusOnContent() {
        focusContent();
    }

    @Override
    public boolean isEmpty() {
        switch (mMode) {
            case MODE_HOSTED:
                return mHosted instanceof Section && ((Section) mHosted).isEmpty();
            case MODE_ROWS:
                return mRows.isEmpty();
            case MODE_GRID:
                return mGridAdapter == null || mGridAdapter.isEmpty();
        }
        return false;
    }

    @Override
    public void updateBadge() {
        // NOP: no badge in the RavTube header
    }

    // ------------------------------------------------------------------ data updates

    private void updateRows(VideoGroup group) {
        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            if (group.getPosition() == -1) {
                clearData();
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

    private void updateGrid(VideoGroup group) {
        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            clearData();
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
            mPanelAdapter.notifyDataSetChanged();
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
            mGridRowCount = newRows;
            if (positionStart >= oldRows * GRID_COLS - GRID_COLS && oldRows > 0) {
                mPanelAdapter.notifyItemChanged(oldRows - 1); // the last row may have been partly filled
            }
            if (positionStart < (oldRows - 1) * GRID_COLS) {
                mPanelAdapter.notifyDataSetChanged(); // prepended
                refocusLater();
            } else if (newRows > oldRows) {
                mPanelAdapter.notifyItemRangeInserted(oldRows, newRows - oldRows);
            }
        }

        @Override
        public void onChanged() {
            reset();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            if (mAdapter == mGridAdapter) {
                mPanelAdapter.notifyItemRangeChanged(positionStart / GRID_COLS,
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
        if (mMode != MODE_ROWS && mMode != MODE_GRID) {
            return;
        }

        int row = -1;
        int col = 0;

        if (mPendingVideo != null) {
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
            if (mMode == MODE_ROWS) {
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
        return mMode == MODE_ROWS ? mRows.size() : mMode == MODE_GRID ? mGridRowCount : 0;
    }

    private int rowSize(int row) {
        if (mMode == MODE_ROWS) {
            return row >= 0 && row < mRows.size() ? mRows.get(row).adapter.size() : 0;
        }
        if (mMode == MODE_GRID && mGridAdapter != null) {
            return Math.max(0, Math.min(GRID_COLS, mGridAdapter.size() - row * GRID_COLS));
        }
        return 0;
    }

    private void focusContent() {
        if (getView() == null) {
            return;
        }
        if (mMode == MODE_HOSTED) {
            if (mHosted != null && mHosted.getView() != null) {
                mHosted.getView().requestFocus();
            } else {
                mFocusOnContentPending = true;
            }
            return;
        }
        if (rowCount() == 0) {
            mFocusOnContentPending = true;
            return;
        }
        int row = Math.max(0, Math.min(mFocusRow, rowCount() - 1));
        int col = Math.max(0, Math.min(mFocusCol, rowSize(row) - 1));
        focusCard(row, col);
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
        if (mMode == MODE_ROWS) {
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
        if (mPanel.hasFocus()) {
            mHandler.post(this::focusContent);
        }
    }

    /**
     * Keep the selection in the middle column and slide the row underneath it, so the next poster is already
     * in view; only at the ends of the row does the selection move sideways.
     */
    private void slideRow(int row, int col) {
        if (row < 0 || row >= mRows.size()) {
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
                    focusTile();
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
                    mSearchBtn.requestFocus();
                } else {
                    focusCard(row - 1, colInRow(row, col, row - 1));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                focusTile();
                return true;
        }
        return false;
    }

    /** Same visible column in the other row. */
    private int colInRow(int from, int col, int to) {
        if (mMode == MODE_ROWS) {
            int visibleCol = col - mRows.get(from).first;
            return Math.max(0, Math.min(mRows.get(to).first + visibleCol, rowSize(to) - 1));
        }
        return Math.max(0, Math.min(col, rowSize(to) - 1));
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
        if (mMode == MODE_ROWS && h.data != null) {
            int size = h.data.adapter.size();
            if (mFocusCol > size - ViewUtil.ROW_SCROLL_CONTINUE_NUM) {
                mPresenter.onScrollEnd((Video) h.data.adapter.get(size - 1));
            }
        } else if (mMode == MODE_GRID && mGridAdapter != null) {
            int size = mGridAdapter.size();
            if (mFocusRow * GRID_COLS + mFocusCol > size - ViewUtil.GRID_SCROLL_CONTINUE_NUM) {
                mPresenter.onScrollEnd((Video) mGridAdapter.get(size - 1));
            }
        }
    }

    // ------------------------------------------------------------------ cards

    private CardHolder makeCard(Context ctx) {
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
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));

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

    private void loadImage(ImageView view, String url, int w, int h, boolean circle) {
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
        int scrollX;
        int first;

        RowData(VideoGroup group) {
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
            CardHolder h = makeCard(parent.getContext());
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
            return mMode;
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

            if (viewType == MODE_GRID) {
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
                    int idx = position * GRID_COLS + i;
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

    /** ScrollView that never scrolls by itself on focus; we glide it. */
    private static final class TileScrollView extends ScrollView {
        TileScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }
}
