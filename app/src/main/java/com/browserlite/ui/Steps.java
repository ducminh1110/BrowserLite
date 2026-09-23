package com.browserlite.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * A slider with a few labelled stops, drawn in flat black and white: no thumb animation, one redraw per change
 * (each redraw is an e-ink refresh).
 */
public final class Steps extends View {
    public interface OnChange {
        void onChange(int index);
    }

    private final String[] labels;
    private int selected;
    private OnChange listener;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hollow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Steps(Context c, String[] labels, int selected) {
        super(c);
        this.labels = labels;
        this.selected = selected;
        float d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        line.setColor(0xFF000000);
        line.setStrokeWidth(Math.max(2, 3 * d));
        fill.setColor(0xFF000000);
        hollow.setColor(0xFFFFFFFF);
        text.setColor(0xFF000000);
        text.setTextSize(11.5f * sd);
        text.setTextAlign(Paint.Align.CENTER);
        bold.set(text);
        bold.setFakeBoldText(true);
        setClickable(true);
    }

    public void setOnChange(OnChange l) {
        listener = l;
    }

    public int selected() {
        return selected;
    }

    private float pad() {
        return Math.max(getWidth() / (labels.length * 2f), 24 * getResources().getDisplayMetrics().density);
    }

    private float xOf(int i) {
        float p = pad();
        return p + (getWidth() - 2 * p) * i / (float) Math.max(1, labels.length - 1);
    }

    @Override
    protected void onMeasure(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), (int) (74 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float y = 22 * d;
        c.drawLine(xOf(0), y, xOf(labels.length - 1), y, line);
        for (int i = 0; i < labels.length; i++) {
            float x = xOf(i);
            boolean on = i == selected;
            float r = (on ? 13 : 8) * d;
            c.drawRect(x - r, y - r, x + r, y + r, fill);
            if (!on && i > selected) c.drawRect(x - r + 3 * d, y - r + 3 * d, x + r - 3 * d, y + r - 3 * d, hollow);
            String[] words = labels[i].split(" ", 2);
            Paint p = on ? bold : text;
            c.drawText(words[0], x, y + 30 * d, p);
            if (words.length > 1) c.drawText(words[1], x, y + 44 * d, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            if (a == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            int best = 0;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < labels.length; i++) {
                float dd = Math.abs(e.getX() - xOf(i));
                if (dd < bestD) {
                    bestD = dd;
                    best = i;
                }
            }
            if (best != selected) {
                selected = best;
                invalidate();
                if (listener != null) listener.onChange(best);
            }
            if (a == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
