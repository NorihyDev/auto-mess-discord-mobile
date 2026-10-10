package dev.norihy.discordmobile;

import android.content.Context;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Small native UI primitives; bundled typeface works offline. */
final class UiStyle {
    static final int PAGE = 0xfff5f7f8, INK = 0xff172b2a, MUTED = 0xff657674;
    static final int GREEN = 0xff087f68, TINT = 0xffe4f4ee, LINE = 0xffe1e8e6;
    private final Context context;
    UiStyle(Context context) { this.context = context; }
    int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    GradientDrawable surface(int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(dp(1), LINE);
        return drawable;
    }
    void font(TextView view, int weight) {
        view.setTypeface(context.getResources().getFont(R.font.manrope));
        view.setFontVariationSettings("'wght' " + weight);
        view.setIncludeFontPadding(false);
    }
    TextView text(String value, int size, int color, int weight) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size);
        view.setTextColor(color); font(view, weight); return view;
    }
    Button button(String value, int color, int textColor) {
        Button view = new Button(context); view.setText(value); view.setAllCaps(false);
        view.setTextSize(14); font(view, 650); view.setTextColor(textColor);
        view.setMinHeight(dp(48)); view.setMinimumWidth(0); view.setMinWidth(0);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f68),
            surface(color, 14, false), surface(Color.WHITE, 14, false)));
        return view;
    }
    EditText input(String value, String hint) {
        EditText view = new EditText(context); view.setTextSize(14); font(view, 500);
        view.setTextColor(INK); view.setHintTextColor(0xff879691);
        view.setBackground(surface(PAGE, 12, false));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setSingleLine(true); view.setGravity(Gravity.TOP | Gravity.START);
        view.setHint(hint); view.setText(value); return view;
    }
    LinearLayout column() {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setBackground(surface(Color.WHITE, 20, true)); return view;
    }
    void polishDialog(Dialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(surface(Color.WHITE, 24, false));
        dialog.getWindow().setDimAmount(0.35f);
        styleDialogText(dialog.getWindow().getDecorView());
    }
    private void styleDialogText(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            int titleId = context.getResources().getIdentifier("alertTitle", "id", "android");
            font(text, view.getId() == titleId || view instanceof Button ? 700 : 500);
            if (view.getId() == titleId) { text.setTextSize(20); text.setTextColor(INK); }
            if (view.getId() == android.R.id.message) { text.setTextSize(13); text.setLineSpacing(dp(3), 1); }
            if (view instanceof Button) ((Button)view).setAllCaps(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) styleDialogText(group.getChildAt(i));
        }
    }
}
