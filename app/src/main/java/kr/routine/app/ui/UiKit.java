package kr.routine.app.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared building blocks of the vehicle screens: text, buttons, cards, banners. Change the look of every screen
 * here (with the tokens in VehicleVisuals); screens only arrange these pieces.
 */
public final class UiKit {
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    public final Activity activity;
    public final Typeface typeface;
    public final VehicleVisuals.Metrics metrics;
    public final VehicleVisuals.Palette colors;

    public UiKit(Activity activity, Typeface typeface, VehicleVisuals.Metrics metrics, VehicleVisuals.Palette colors) {
        this.activity = activity; this.typeface = typeface; this.metrics = metrics; this.colors = colors;
    }

    public TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(activity); view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.text(size)); view.setTextColor(color);
        view.setTypeface(typeface, bold ? Typeface.BOLD : Typeface.NORMAL); view.setIncludeFontPadding(true);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        return view;
    }

    public Button button(String value, boolean primary, boolean enabled, Runnable command) {
        Button button = new Button(activity); button.setAllCaps(false); button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.text(primary ? 48 : 34));
        button.setTypeface(typeface, primary ? Typeface.BOLD : Typeface.NORMAL);
        int height = primary ? metrics.primaryHeight() : metrics.touch(84);
        button.setMinHeight(height); button.setMinimumHeight(height);
        button.setMinWidth(metrics.touch(84)); button.setMinimumWidth(metrics.touch(84));
        button.setPadding(24, 16, 24, 16); button.setSingleLine(false); button.setGravity(Gravity.CENTER);
        button.setTextColor(enabled ? (primary ? colors.primaryText() : colors.text()) : colors.muted());
        button.setBackgroundTintList(null);
        int fill = enabled ? (primary ? colors.primary() : colors.surface()) : colors.raised();
        button.setBackground(background(fill, 0));
        button.setEnabled(enabled); button.setOnClickListener(v -> command.run());
        button.setOnFocusChangeListener((v, focused) -> button.setBackground(background(fill, focused ? colors.selectedLine() : 0)));
        return button;
    }

    public RippleDrawable background(int color, int border) {
        return new RippleDrawable(ColorStateList.valueOf(colors.selectedLine() & 0x33ffffff), shape(color, border, 20), null);
    }

    public GradientDrawable shape(int color, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius);
        if (border != 0) drawable.setStroke(3, border);
        return drawable;
    }

    public LinearLayout column() { LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.VERTICAL); return value; }

    public LinearLayout row() {
        LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL); return value;
    }

    /** A rounded surface that groups related content. */
    public LinearLayout card() {
        LinearLayout box = column(); box.setPadding(28, 24, 28, 24); box.setBackground(shape(colors.surface(), 0, 26)); return box;
    }

    public void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP); lp.topMargin = top; parent.addView(child, lp);
    }

    /** Places views side by side on wide windows and stacked on compact ones. */
    public LinearLayout pair(View first, View second, float firstWeight, float secondWeight) {
        LinearLayout line = metrics.compact() ? column() : row();
        if (metrics.compact()) { add(line, first, 0); if (second != null) add(line, second, 12); }
        else {
            line.addView(first, new LinearLayout.LayoutParams(0, WRAP, firstWeight));
            if (second != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, secondWeight); lp.leftMargin = 14;
                line.addView(second, lp);
            }
        }
        return line;
    }

    public void heading(LinearLayout parent, String value) {
        TextView title = text(value, metrics.compact() ? 52 : 62, true, colors.text()); title.setAccessibilityHeading(true);
        add(parent, title, 12);
    }

    public void description(LinearLayout parent, String value) {
        if (value != null && !value.isBlank()) add(parent, text(value, 38, false, colors.secondary()), 14);
    }

    public void banner(LinearLayout parent, String value, boolean warning) {
        TextView banner = text(value, 34, false, warning ? colors.warningText() : colors.text()); banner.setPadding(24, 20, 24, 20);
        banner.setBackground(shape(warning ? colors.warning() : colors.surface(), 0, 20)); add(parent, banner, 16);
    }
}
