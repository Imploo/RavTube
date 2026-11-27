package com.liskovsoft.smartyoutubetv2.tv.ui.playback.rav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.CommentsReceiver;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavGlide;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavStyle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavStyle.*;

/**
 * RavTube comments: glass cards floating on the right, over the full-size video. No side panel, no smaller
 * video. Small Lexend Exa type and a wide card, so a long comment fits in one card.<br/>
 * ▲▼ moves between comments, OK opens the replies, holding OK likes, ◀ or Back closes (or leaves the replies).
 * Loading, paging, replies and likes go through the app's own {@link CommentsReceiver}.
 */
public class RavCommentsOverlay extends FrameLayout {
    private static final int CARD_WIDTH = 320; // 20% narrower: more of the video stays visible
    // light glass with dark grey text; the selected card is brighter and fully opaque, the others translucent
    private static final int CARD_FILL = 0x85EEEDF3;
    private static final int CARD_FILL_FOCUSED = 0xF7FBFBFD;
    private static final int CARD_EDGE = 0x8CFFFFFF;
    private static final int CARD_EDGE_FOCUSED = 0xFFFFFFFF;
    private static final float CARD_ALPHA = 0.8f;
    private static final int TEXT_STRONG = 0xFF0E0D14;
    private static final int TEXT = 0xFF1A1922;
    private static final int TEXT_SOFT = 0xFF45444F;
    private static final int LIKED = 0xFFC2701E;
    private static final int LOAD_MORE_AHEAD = 3;

    /** One list of comments: the video's comments, or the replies to one comment. */
    private static final class Page implements CommentsReceiver.Backup {
        final CommentsReceiver receiver;
        final String title;
        final List<CommentItem> items = new ArrayList<>();
        CommentGroup lastGroup;
        int focus;
        boolean loading = true;
        boolean ended;

        Page(CommentsReceiver receiver, String title) {
            this.receiver = receiver;
            this.title = title;
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Page> mStack = new ArrayList<>();
    private final Set<Integer> mConsumedKeys = new HashSet<>();
    private final View mShade;
    private final LinearLayout mColumn;
    private final TextView mHeader;
    private final CommentsScroll mScroll;
    private final RavGlide mGlide;
    private final LinearLayout mList;
    private final TextView mHint;
    private boolean mOkLongHandled;
    private Runnable mOnShownChanged;

    public RavCommentsOverlay(Context context) {
        super(context);
        setVisibility(GONE);
        setClipChildren(false);

        // a soft darkening on the right only, so the cards stay readable over bright video
        mShade = new View(context);
        mShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00000000, 0x00000000, 0x26000000}));
        addView(mShade, new LayoutParams(-1, -1));

        mColumn = new LinearLayout(context);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        mColumn.setPadding(0, dp(30), 0, 0);
        mColumn.setClipChildren(true); // the list is cut off at its own edges (and fades there)
        LayoutParams columnLp = new LayoutParams(dp(CARD_WIDTH + 24), -1, Gravity.END);
        columnLp.rightMargin = dp(6); // close to the screen edge
        addView(mColumn, columnLp);

        mHeader = new TextView(context);
        font(mHeader);
        mHeader.setTextSize(12);
        mHeader.setTextColor(TEXT_STRONG);
        mHeader.setPadding(dp(13), dp(6), dp(13), dp(6));
        mHeader.setBackground(glass(CARD_FILL_FOCUSED, dp(99), CARD_EDGE_FOCUSED, false));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-2, -2);
        headerLp.leftMargin = dp(16);
        headerLp.bottomMargin = dp(10);
        mHeader.setVisibility(GONE); // no "Comments" label; the cards speak for themselves
        mColumn.addView(mHeader, headerLp);

        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setClipChildren(false);
        mList.setPadding(dp(8), dp(8), dp(8), dp(300));
        mScroll = new CommentsScroll(context);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setVerticalFadingEdgeEnabled(true);
        mScroll.setFadingEdgeLength(dp(36));
        mScroll.setClipChildren(true); // cards never slide over the header or the hint; the edges fade
        mScroll.setFocusable(false);
        mScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mScroll.addView(mList, new LayoutParams(-1, -2));
        mGlide = new RavGlide(mScroll, false);
        mColumn.addView(mScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        mHint = new TextView(context);
        font(mHint);
        mHint.setTextSize(8.5f);
        mHint.setTextColor(0xFF2A2933);
        mHint.setShadowLayer(dp(2), 0, 0, 0x66FFFFFF); // a faint light halo keeps it readable on darker video
        mHint.setText(context.getString(R.string.rav_comments_hint));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-2, -2);
        hintLp.leftMargin = dp(12);
        hintLp.bottomMargin = dp(8);
        mColumn.addView(mHint, 0, hintLp); // above the cards
    }

    private int dp(float v) {
        return RavStyle.dp(getContext(), v);
    }

    /** Glass: a translucent fill, a hairline edge and, optionally, a specular line along the top. */
    private Drawable glass(int fill, float radius, int edge, boolean rim) {
        GradientDrawable body = new GradientDrawable();
        body.setColor(fill);
        body.setCornerRadius(radius);
        body.setStroke(Math.max(1, dp(0.75f)), edge);
        if (!rim || Build.VERSION.SDK_INT < 23) {
            return body;
        }
        GradientDrawable line = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x73FFFFFF, 0xB3FFFFFF, 0x73FFFFFF, 0x00FFFFFF});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{body, line});
        layers.setLayerInset(1, dp(14), 0, dp(14), 0);
        layers.setLayerHeight(1, Math.max(1, dp(1)));
        layers.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        return layers;
    }

    public void setOnShownChanged(Runnable callback) {
        mOnShownChanged = callback;
    }

    public boolean isShown2() {
        return getVisibility() == VISIBLE;
    }

    // ------------------------------------------------------------------ pages

    /** Opens the comments, or pushes the replies on top when already open. */
    public void open(CommentsReceiver receiver, String title) {
        boolean wasShown = isShown2();
        Page page = new Page(receiver, mStack.isEmpty() ? getContext().getString(R.string.rav_comments)
                : getContext().getString(R.string.rav_replies));
        mStack.add(page);
        receiver.setCallback(new CommentsReceiver.Callback() {
            @Override
            public void onCommentGroup(CommentGroup group) {
                mHandler.post(() -> addGroup(page, group));
            }

            @Override
            public void onBackup(CommentsReceiver.Backup backup) {
                mHandler.post(() -> restore(page, backup));
            }

            @Override
            public void onSync(CommentItem item) {
                mHandler.post(() -> syncItem(page, item));
            }
        });
        showPage();
        if (!wasShown) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            mColumn.setTranslationX(dp(28));
            animate().alpha(1f).setDuration(260).start();
            mColumn.animate().translationX(0).setDuration(420).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            if (mOnShownChanged != null) {
                mOnShownChanged.run();
            }
        }
        receiver.onStart();
    }

    private Page page() {
        return mStack.isEmpty() ? null : mStack.get(mStack.size() - 1);
    }

    /** Leaves the replies, or closes the comments. */
    public void back() {
        Page top = page();
        if (top == null) {
            return;
        }
        mStack.remove(mStack.size() - 1);
        top.receiver.onFinish(top);
        if (mStack.isEmpty()) {
            animate().alpha(0f).setDuration(200).withEndAction(() -> {
                setVisibility(GONE);
                mList.removeAllViews();
                if (mOnShownChanged != null) {
                    mOnShownChanged.run();
                }
            }).start();
        } else {
            showPage();
        }
    }

    /** Closes everything (e.g. a new video starts). */
    public void close() {
        while (!mStack.isEmpty()) {
            back();
        }
    }

    private void restore(Page page, CommentsReceiver.Backup backup) {
        if (backup instanceof Page) {
            Page old = (Page) backup;
            page.items.addAll(old.items);
            page.lastGroup = old.lastGroup;
            page.focus = old.focus;
            page.ended = old.ended;
            page.loading = false;
            if (page == page()) {
                showPage();
            }
        } else {
            page.loading = false;
            page.ended = true;
            if (page == page()) {
                showPage();
            }
        }
    }

    private void addGroup(Page page, CommentGroup group) {
        page.loading = false;
        if (group == null || group.getComments() == null) {
            page.ended = true;
        } else {
            for (CommentItem item : group.getComments()) {
                // NOTE: isEmpty() means "no replies", not "no content"; skip only placeholders without text
                if (item != null && !(TextUtils.isEmpty(item.getMessage()) && TextUtils.isEmpty(item.getAuthorName()))) {
                    page.items.add(item);
                }
            }
            page.lastGroup = group;
            page.ended = group.getNextCommentsKey() == null;
        }
        if (page == page()) {
            showPage();
        }
    }

    private void syncItem(Page page, CommentItem item) {
        for (int i = 0; i < page.items.size(); i++) {
            CommentItem old = page.items.get(i);
            if (old.getId() != null && old.getId().equals(item.getId())) {
                page.items.set(i, item);
                break;
            }
        }
        if (page == page()) {
            showPage();
        }
    }

    // ------------------------------------------------------------------ rendering

    private void showPage() {
        Page page = page();
        if (page == null) {
            return;
        }
        mHeader.setText(page.title);
        mList.removeAllViews();
        if (page.items.isEmpty()) {
            String message = page.loading ? page.receiver.getLoadingMessage() : page.receiver.getErrorMessage();
            mList.addView(messageCard(message), cardParams());
        } else {
            for (CommentItem item : page.items) {
                mList.addView(commentCard(item), cardParams());
            }
            if (page.loading) {
                mList.addView(messageCard(page.receiver.getLoadingMessage()), cardParams());
            }
        }
        page.focus = Math.max(0, Math.min(page.focus, page.items.size() - 1));
        mList.post(this::renderFocus);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private View messageCard(String text) {
        TextView tv = new TextView(getContext());
        font(tv);
        tv.setTextSize(10);
        tv.setTextColor(TEXT_SOFT);
        tv.setText(text);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(glass(CARD_FILL, dp(14), CARD_EDGE, false));
        return tv;
    }

    private View commentCard(CommentItem item) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(11));
        card.setAlpha(CARD_ALPHA);
        card.setBackground(glass(CARD_FILL, dp(14), CARD_EDGE, false));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(2)); // a soft shadow lifts the light card off bright video
        }

        ImageView avatar = new ImageView(ctx);
        avatar.setBackground(oval(0x1F000000));
        card.addView(avatar, new LinearLayout.LayoutParams(dp(26), dp(26)));
        if (item.getAuthorPhoto() != null) {
            try {
                Glide.with(ctx).load(item.getAuthorPhoto())
                        .apply(RequestOptions.circleCropTransform().override(dp(26), dp(26))).into(avatar);
            } catch (IllegalArgumentException e) {
                // destroyed activity
            }
        }

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bodyLp.leftMargin = dp(10);
        card.addView(body, bodyLp);

        TextView who = new TextView(ctx);
        font(who);
        if (Build.VERSION.SDK_INT >= 26) {
            who.setFontVariationSettings("'wght' 500");
        }
        who.setTextSize(9);
        who.setTextColor(TEXT_STRONG);
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        String date = item.getPublishedDate();
        who.setText(TextUtils.isEmpty(date) ? item.getAuthorName() : item.getAuthorName() + "  ·  " + date);
        body.addView(who);

        TextView message = new TextView(ctx);
        font(message);
        message.setTextSize(9.5f);
        message.setTextColor(TEXT);
        message.setLineSpacing(dp(2), 1f);
        message.setText(item.getMessage());
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(-1, -2);
        messageLp.topMargin = dp(4);
        messageLp.bottomMargin = dp(6);
        body.addView(message, messageLp);

        TextView meta = new TextView(ctx);
        font(meta);
        meta.setTextSize(8.5f);
        meta.setTextColor(item.isLiked() ? LIKED : TEXT_SOFT);
        StringBuilder sb = new StringBuilder();
        sb.append(item.isLiked() ? "▲ " : "△ ").append(item.getLikeCount() != null ? item.getLikeCount() : "0");
        if (!TextUtils.isEmpty(item.getReplyCount())) {
            sb.append("    ").append(item.getReplyCount());
        }
        meta.setText(sb);
        body.addView(meta);

        return card;
    }

    private void renderFocus() {
        Page page = page();
        if (page == null) {
            return;
        }
        int count = Math.min(page.items.size(), mList.getChildCount());
        for (int i = 0; i < count; i++) {
            View card = mList.getChildAt(i);
            boolean focused = i == page.focus;
            card.animate().alpha(focused ? 1f : CARD_ALPHA).setDuration(200).start();
            card.setBackground(focused ? glass(CARD_FILL_FOCUSED, dp(14), CARD_EDGE_FOCUSED, false)
                    : glass(CARD_FILL, dp(14), CARD_EDGE, false));
        }
        if (page.focus < count) {
            View focused = mList.getChildAt(page.focus);
            mGlide.to(Math.max(0, focused.getTop() - mList.getPaddingTop() - dp(30))); // the card above peeks in, faded
        } else {
            mGlide.to(0);
        }
    }

    // ------------------------------------------------------------------ keys

    public boolean dispatchKey(KeyEvent event) {
        if (!isShown2()) {
            return false;
        }
        int code = event.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_BACK || isOk(code);
        if (!ours) {
            return false; // play / pause etc. keep working
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean consumed = mConsumedKeys.remove(code);
            if (consumed && isOk(code) && !mOkLongHandled) {
                openReplies();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        mConsumedKeys.add(code);
        boolean first = event.getRepeatCount() == 0;
        Page page = page();
        if (page == null) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (page.focus < page.items.size() - 1) {
                    page.focus++;
                    renderFocus();
                }
                loadMoreIfNeeded(page);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (page.focus > 0) {
                    page.focus--;
                    renderFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
                if (first) {
                    back();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
        }
        // OK: a short press opens the replies (on release), holding it likes the comment
        if (first) {
            mOkLongHandled = false;
        } else if (!mOkLongHandled) {
            mOkLongHandled = true;
            if (page.focus < page.items.size()) {
                page.receiver.onCommentLongClicked(page.items.get(page.focus));
            }
        }
        return true;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void openReplies() {
        Page page = page();
        if (page == null || page.focus >= page.items.size()) {
            return;
        }
        CommentItem item = page.items.get(page.focus);
        if (item.getNestedCommentsKey() != null && !TextUtils.isEmpty(item.getReplyCount())) {
            page.receiver.onCommentClicked(item); // the app calls back with the replies' receiver
        }
    }

    private void loadMoreIfNeeded(Page page) {
        if (!page.loading && !page.ended && page.lastGroup != null && page.focus >= page.items.size() - LOAD_MORE_AHEAD) {
            page.loading = true;
            page.receiver.onLoadMore(page.lastGroup);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    /** ScrollView that never scrolls by itself; we glide it. */
    private static final class CommentsScroll extends ScrollView {
        CommentsScroll(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        protected float getBottomFadingEdgeStrength() {
            return 1f;
        }
    }
}
