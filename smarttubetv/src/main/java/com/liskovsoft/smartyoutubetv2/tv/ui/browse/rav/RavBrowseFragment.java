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
    private static final int MODE_CHANNELS = 4;
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
    private RavPosterPanel mPanel;
    private FrameLayout mHost;
    private ProgressBar mProgress;
    private Fragment mHosted;
    private boolean mErrorShown;

    private boolean mFocusOnContentPending;

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
        mPanel = new RavPosterPanel(ctx, new PanelCallback());
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
                RavPosterPanel.loadImage(icon, section.getIconUrl(), dp(20), dp(20), true);
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
                setMode(MODE_CHANNELS); // channel row on top, the chosen channel's videos below
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
        mPanel.setMode(mode == MODE_ROWS ? RavPosterPanel.MODE_ROWS
                : mode == MODE_CHANNELS ? RavPosterPanel.MODE_CHANNELS : RavPosterPanel.MODE_GRID);
        mPanel.setFocusOnContentPending(mFocusOnContentPending);
        mFocusOnContentPending = false; // handed to the panel; browsing the tiles must not pull focus later
        mPanel.setVisibility(View.VISIBLE);
    }

    private void clearData() {
        mPanel.clear();
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
                || section.getType() == BrowseSection.TYPE_GRID || section.getType() == BrowseSection.TYPE_SHORTS_GRID
                || section.getType() == BrowseSection.TYPE_MULTI_GRID);
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
        mPanel.setFocusOnContentPending(focusOnContent);

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
                setMode(section.getType() == BrowseSection.TYPE_ROW ? MODE_ROWS
                        : section.getType() == BrowseSection.TYPE_MULTI_GRID ? MODE_CHANNELS : MODE_GRID);
            } else {
                return;
            }
        }

        mPanel.update(group);
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
        } else if (mMode == MODE_ROWS || mMode == MODE_GRID || mMode == MODE_CHANNELS) {
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
        mPanel.selectIndex(index);
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
        mPanel.selectVideo(item);
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
            case MODE_GRID:
            case MODE_CHANNELS:
                return mPanel.isEmpty();
        }
        return false;
    }

    @Override
    public void updateBadge() {
        // NOP: no badge in the RavTube header
    }

    // ------------------------------------------------------------------ data updates

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
        mPanel.focusContent();
    }

    private final class PanelCallback implements RavPosterPanel.Callback {
        @Override
        public void onVideoItemSelected(Video item) {
            mPresenter.onVideoItemSelected(item);
        }

        @Override
        public void onVideoItemClicked(Video item) {
            mPresenter.onVideoItemClicked(item);
        }

        @Override
        public void onVideoItemLongClicked(Video item) {
            mPresenter.onVideoItemLongClicked(item);
        }

        @Override
        public void onScrollEnd(Video item) {
            mPresenter.onScrollEnd(item);
        }

        @Override
        public boolean hasPendingActions() {
            return mPresenter.hasPendingActions();
        }

        @Override
        public void onExitLeft() {
            focusTile();
        }

        @Override
        public void onExitUp() {
            mSearchBtn.requestFocus();
        }

        @Override
        public boolean onBack() {
            focusTile();
            return true;
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
