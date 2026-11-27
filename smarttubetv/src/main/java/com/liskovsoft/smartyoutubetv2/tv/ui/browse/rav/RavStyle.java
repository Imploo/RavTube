package com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * RavTube look, shared by the overview (and later the player). Values match RavLauncher.
 */
public final class RavStyle {
    // sizes in dp
    public static final int CARD_W = 196;
    public static final int CARD_H = 110;
    public static final int GUTTER = 40;
    /** Every poster row has the same height: GUTTER/2 above and below the posters. */
    public static final int ROW_H = CARD_H + GUTTER;
    public static final int EDGE = 20;
    public static final float CARD_ZOOM = 1.2f;
    public static final float TILE_ZOOM = 1.2f;
    public static final int TILE_W = 88;
    public static final int TILE_H = 50;
    public static final int HEADER_H = 132;

    // colors
    public static final int TILE_BG = 0xFF1E2033;
    public static final int AMBER = 0xFFFFB46B;
    public static final int LIVE_RED = 0xD9D63C3C;

    private static Typeface sFont;

    private RavStyle() {
    }

    public static int dp(Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /** Lexend Exa Light, the launcher's font. Falls back to the system font. */
    public static void font(TextView tv) {
        if (sFont == null) {
            try {
                sFont = ResourcesCompat.getFont(tv.getContext(), R.font.rav_lexend_exa);
            } catch (Exception e) {
                sFont = Typeface.DEFAULT;
            }
        }
        tv.setTypeface(sFont);
        if (Build.VERSION.SDK_INT >= 26) {
            tv.setFontVariationSettings("'wght' 300");
        }
    }

    /** Medium sans for small labels and meta lines. */
    public static void sansMedium(TextView tv) {
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /**
     * Softer, lighter poster ("wash"), t = 0 (none) .. 1 (full): contrast down to 82%, blacks lifted,
     * saturation 90%. Interpolated so focus can fade it instead of switching it.
     */
    public static ColorFilter washFilter(float t) {
        if (t <= 0f) {
            return null;
        }
        float c = 1f - 0.18f * t;
        float lift = 255f * (1f - c) / 2f + 24f * t;
        ColorMatrix m = new ColorMatrix(new float[]{
                c, 0, 0, 0, lift,
                0, c, 0, 0, lift,
                0, 0, c, 0, lift,
                0, 0, 0, 1, 0});
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1f - 0.1f * t);
        m.postConcat(sat);
        return new ColorMatrixColorFilter(m);
    }

    public static GradientDrawable rect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static void rounded(View v, float radiusPx) {
        if (Build.VERSION.SDK_INT >= 21) {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            v.setClipToOutline(true);
        }
    }

    /**
     * Glass edge: a hairline light edge all round, plus a specular line along the top edge that is brightest
     * in the middle and fades out toward the corners.
     */
    public static Drawable glassEdge(Context context) {
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(dp(context, 8));
        edge.setStroke(Math.max(1, dp(context, 0.75f)), 0x2EFFFFFF);
        edge.setColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT < 23) {
            return edge; // no layer sizing before M: the specular line would cover the whole poster
        }
        GradientDrawable rim = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x99FFFFFF, 0xF2FFFFFF, 0x99FFFFFF, 0x00FFFFFF});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{edge, rim});
        int inset = dp(context, 8);
        layers.setLayerInset(1, inset, 0, inset, 0);
        layers.setLayerHeight(1, Math.max(2, dp(context, 1)));
        layers.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        return layers;
    }
}
