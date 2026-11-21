package kr.routine.phone;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small view kit for the phone screens. Colors and sizes live here; screens only arrange parts. */
public final class Ui {
    public static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT, WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    final Activity activity;
    final boolean dark;
    final int background, surface, text, secondary, primary, onPrimary, warning, warningText;

    public Ui(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = dark ? 0xff121314 : 0xfff3f2ee; surface = dark ? 0xff1c1d1f : 0xffffffff;
        text = dark ? 0xffecebe8 : 0xff1b1c1e; secondary = dark ? 0xffa3a6ab : 0xff5c6066;
        primary = dark ? 0xff5aa2ee : 0xff0b5fb8; onPrimary = dark ? 0xff0b1520 : 0xffffffff;
        warning = dark ? 0xff33280f : 0xfffbf0d9; warningText = dark ? 0xfff0c065 : 0xff8a5a00;
    }

    /** Scrolling page with padding; returns the column to fill. */
    public LinearLayout page() {
        ScrollView scroll = new ScrollView(activity); scroll.setBackgroundColor(background); scroll.setFillViewport(true);
        LinearLayout column = column(); column.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(MATCH, WRAP));
        activity.setContentView(scroll);
        return column;
    }

    public LinearLayout column() { LinearLayout l = new LinearLayout(activity); l.setOrientation(LinearLayout.VERTICAL); return l; }

    public LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(16), dp(16), dp(16), dp(16)); c.setBackground(shape(surface, dp(16)));
        return c;
    }

    public TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(activity); t.setText(value); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public TextView brand() { return text("늘길", 14, true, primary); }
    public TextView title(String value) { TextView t = text(value, 24, true, text); t.setAccessibilityHeading(true); return t; }
    public TextView muted(String value) { return text(value, 14, false, secondary); }

    public TextView notice(String value, boolean warn) {
        TextView t = text(value, 14, false, warn ? warningText : text); t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(shape(warn ? warning : surface, dp(10)));
        return t;
    }

    public Button button(String label, boolean primaryStyle, Runnable action) {
        Button b = new Button(activity); b.setText(label); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(primaryStyle ? onPrimary : text); b.setBackground(shape(primaryStyle ? primary : (dark ? 0xff26282b : 0xffebe9e3), dp(12)));
        b.setMinHeight(dp(52)); b.setPadding(dp(16), 0, dp(16), 0); b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    public EditText field(String hint, String value, int maxLength) {
        EditText e = new EditText(activity); e.setHint(hint); e.setText(value); e.setSingleLine(true);
        e.setTextColor(text); e.setHintTextColor(secondary);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        return e;
    }

    public void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP); lp.topMargin = dp(topDp); parent.addView(child, lp);
    }

    public GradientDrawable shape(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); return g; }
    public int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
