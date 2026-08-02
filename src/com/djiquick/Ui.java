package com.djiquick;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Палитра и билдеры вью в стиле DJI Fly (тёмная тема). Разметки в XML нет — весь UI строится
 * кодом, поэтому эти хелперы общие для MainActivity и AboutActivity.
 */
public final class Ui {

    public static final int BG      = 0xFF0A0A0B;
    public static final int ROW_BG  = 0xFF161619;
    public static final int TXT     = 0xFFFFFFFF;
    public static final int TXT_SUB = 0xFF8E8E93;
    public static final int ACCENT  = 0xFF2E9BFF;
    public static final int GREEN   = 0xFF34C759;
    public static final int RED     = 0xFFFF453A;
    public static final int AMBER   = 0xFFFFB020;

    private Ui() {}

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Заголовок секции — мелкий, приглушённый, капсом по смыслу вызова. */
    public static TextView caption(Context c, LinearLayout parent, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(12);
        t.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 6));
        parent.addView(t);
        return t;
    }

    /** Пояснение обычным текстом. */
    public static TextView note(Context c, LinearLayout parent, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(13);
        t.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 10));
        parent.addView(t);
        return t;
    }

    /** Строка-статус на плашке ROW_BG. */
    public static TextView statusLine(Context c, LinearLayout parent, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setBackgroundColor(ROW_BG);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 1);
        parent.addView(t, lp);
        return t;
    }

    /** Кнопка-действие во всю ширину: цветной текст на плашке, выравнивание влево. */
    public static Button action(Context c, LinearLayout parent, String text, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setBackgroundColor(ROW_BG);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 52));
        lp.topMargin = dp(c, 8);
        parent.addView(b, lp);
        return b;
    }

    /** Плоская кнопка без добавления в родителя — вызывающий сам решает раскладку. */
    public static Button flatBtn(Context c, String text, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(ROW_BG);
        b.setOnClickListener(l);
        return b;
    }
}
