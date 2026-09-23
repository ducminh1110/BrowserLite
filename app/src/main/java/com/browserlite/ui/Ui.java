package com.browserlite.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal black-on-white widgets for e-ink: no ripples, no fades, big touch targets, 1-2 px borders.
 */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Pressed state is a flat light gray: one partial refresh, readable feedback on slow panels. */
    public static Drawable pressable() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(0xFFBBBBBB));
        d.addState(new int[] {android.R.attr.state_focused}, new ColorDrawable(0xFFDDDDDD));
        d.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    public static GradientDrawable border(Context c, int strokeDp, int fillColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setStroke(dp(c, strokeDp), 0xFF000000);
        return g;
    }

    public static ImageView iconButton(Context c, int icon, String description, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(icon, c.getResources().getDisplayMetrics().density));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackgroundDrawable(pressable());
        v.setContentDescription(description);
        v.setOnClickListener(l);
        v.setFocusable(true);
        int pad = dp(c, 10);
        v.setPadding(pad, pad, pad, pad);
        return v;
    }

    public static TextView text(Context c, String s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(0xFF000000);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF000000);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    /** A row in a {@link #sheet}. {@code checked} null means "not a toggle". */
    public static final class Item {
        public final String label;
        public final String detail;
        public final Boolean checked;
        public final Runnable action;
        public Runnable longAction;
        public int trailingIcon;
        public Runnable trailingAction;

        public Item(String label, Boolean checked, Runnable action) {
            this(label, null, checked, action);
        }

        public Item(String label, String detail, Boolean checked, Runnable action) {
            this.label = label;
            this.detail = detail;
            this.checked = checked;
            this.action = action;
        }
    }

    public static final class Header {
        public final String title;
        public final List<Item> buttons = new ArrayList<>();

        public Header(String title) {
            this.title = title;
        }
    }

    /** Shows a bordered, animation-free list dialog. */
    public static Dialog sheet(final Activity a, Header header, List<Item> items, boolean anchorTopRight) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Holo_Light_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundDrawable(border(a, 2, 0xFFFFFFFF));
        int pad = dp(a, 2);
        outer.setPadding(pad, pad, pad, pad);

        if (header != null) {
            LinearLayout h = new LinearLayout(a);
            h.setOrientation(LinearLayout.HORIZONTAL);
            h.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text(a, header.title, 17, true);
            t.setPadding(dp(a, 14), dp(a, 10), dp(a, 8), dp(a, 10));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            for (final Item b : header.buttons) {
                TextView btn = text(a, b.label, 15, true);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
                btn.setBackgroundDrawable(pressable());
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        if (b.action != null) b.action.run();
                    }
                });
                h.addView(btn);
            }
            outer.addView(h);
            View line = new View(a);
            line.setBackgroundColor(0xFF000000);
            outer.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 2)));
        }

        int screenH0 = a.getResources().getDisplayMetrics().heightPixels;
        MaxScroll scroll = new MaxScroll(a, screenH0 - dp(a, header != null ? 130 : 80));
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        float density = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(a, 52));
            row.setBackgroundDrawable(pressable());
            row.setPadding(dp(a, 14), dp(a, 6), dp(a, 6), dp(a, 6));
            if (it.checked != null) {
                ImageView check = new ImageView(a);
                check.setImageDrawable(new Icon(it.checked ? Icon.CHECK_ON : Icon.CHECK_OFF, density));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
                lp.rightMargin = dp(a, 12);
                row.addView(check, lp);
            }
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView label = text(a, it.label, 18, false);
            label.setSingleLine(it.detail != null);
            label.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(label);
            if (it.detail != null) {
                TextView detail = text(a, it.detail, 13, false);
                detail.setTextColor(0xFF333333);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                texts.addView(detail);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (it.trailingIcon != 0) {
                ImageView tr = iconButton(a, it.trailingIcon, "", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        if (it.trailingAction != null) it.trailingAction.run();
                    }
                });
                row.addView(tr, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (it.action != null) it.action.run();
                }
            });
            if (it.longAction != null) {
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        d.dismiss();
                        it.longAction.run();
                        return true;
                    }
                });
            }
            list.addView(row);
            if (i < items.size() - 1) {
                View div = new View(a);
                div.setBackgroundColor(0xFF000000);
                list.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(a);
        frame.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(frame);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setWindowAnimations(0);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            int screenW = a.getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(screenW - dp(a, 16), dp(a, 440));
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (anchorTopRight) {
                lp.gravity = Gravity.TOP | Gravity.RIGHT;
                lp.x = dp(a, 4);
                lp.y = dp(a, 48);
            }
            w.setAttributes(lp);
        }
        d.show();
        return d;
    }

    /** ScrollView that never grows past a maximum height, so long lists scroll inside their dialog. */
    static final class MaxScroll extends ScrollView {
        private final int max;

        MaxScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    /** Plain dialog window with the e-ink styling, content supplied by the caller. */
    public static Dialog panel(Activity a, View content) {
        Dialog d = new Dialog(a, android.R.style.Theme_Holo_Light_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout frame = new FrameLayout(a);
        frame.setBackgroundDrawable(border(a, 2, 0xFFFFFFFF));
        int pad = dp(a, 14);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(content);
        d.setContentView(frame);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setWindowAnimations(0);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = Math.min(a.getResources().getDisplayMetrics().widthPixels - dp(a, 16), dp(a, 440));
            w.setAttributes(lp);
        }
        return d;
    }

    public static TextView button(Context c, String label, boolean primary, View.OnClickListener l) {
        TextView b = text(c, label, 17, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        if (primary) {
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[] {android.R.attr.state_pressed}, border(c, 2, 0xFF555555));
            d.addState(new int[0], border(c, 2, 0xFF000000));
            b.setBackgroundDrawable(d);
            b.setTextColor(0xFFFFFFFF);
        } else {
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[] {android.R.attr.state_pressed}, border(c, 2, 0xFFBBBBBB));
            d.addState(new int[0], border(c, 2, 0xFFFFFFFF));
            b.setBackgroundDrawable(d);
        }
        b.setOnClickListener(l);
        return b;
    }
}
