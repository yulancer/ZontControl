package tech.finbeat.zontcontrol;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Крупный диалог подтверждения: занимает большую часть экрана,
 * кнопки «Отмена» и «Да» — во всю нижнюю половину, легко попасть даже при тряске.
 *
 * Защита от случайного нажатия: кнопка подтверждения становится активной
 * через {@link #ARM_DELAY_MS} после появления диалога (чтобы «дребезг» пальца
 * после первого нажатия не подтвердил команду сразу).
 */
public final class BigConfirm {

    private static final long ARM_DELAY_MS = 500;

    private BigConfirm() {
    }

    public static void show(Activity a, String title, String message,
                            String okText, int okColor, Runnable onOk) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);

        int pad = dp(a, 20);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.getColor(R.color.card));
        bg.setCornerRadius(dp(a, 20));
        root.setBackground(bg);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(a.getColor(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);

        TextView m = new TextView(a);
        m.setText(message);
        m.setTextColor(a.getColor(R.color.text_primary));
        m.setTypeface(Typeface.DEFAULT_BOLD);
        m.setGravity(Gravity.CENTER);
        m.setMaxLines(2);
        m.setAutoSizeTextTypeUniformWithConfiguration(18, 40, 1, TypedValue.COMPLEX_UNIT_SP);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = bigButton(a, "Отмена", 0xFF78909C);
        Button ok = bigButton(a, okText, okColor);
        ok.setEnabled(false);
        ok.setAlpha(0.5f);
        ok.postDelayed(() -> {
            ok.setEnabled(true);
            ok.setAlpha(1f);
        }, ARM_DELAY_MS);

        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            d.dismiss();
            onOk.run();
        });

        int gap = dp(a, 16);
        LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lpCancel.setMarginEnd(gap / 2);
        LinearLayout.LayoutParams lpOk = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lpOk.setMarginStart(gap / 2);
        buttons.addView(cancel, lpCancel);   // «Отмена» слева
        buttons.addView(ok, lpOk);           // подтверждение справа

        root.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(m, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams lpButtons = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.6f);
        lpButtons.topMargin = dp(a, 12);
        root.addView(buttons, lpButtons);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout(Math.round(dm.widthPixels * 0.9f), Math.round(dm.heightPixels * 0.8f));
        }
        d.show();
    }

    private static Button bigButton(Activity a, String text, int color) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMaxLines(2);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setAutoSizeTextTypeUniformWithConfiguration(16, 40, 1, TypedValue.COMPLEX_UNIT_SP);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(a, 16));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null));
        return b;
    }

    private static int dp(Activity a, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics()));
    }
}
