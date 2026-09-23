package com.browserlite.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Toolbar icons drawn as strokes: crisp at any e-ink density, no bitmaps to decode, a few bytes of code each.
 * Coordinates are on a 24x24 grid like Material icons.
 */
public final class Icon extends Drawable {
    public static final int BACK = 1, FORWARD = 2, RELOAD = 3, STOP = 4, MENU = 5, PAGE_UP = 6, PAGE_DOWN = 7,
            HOME = 8, READER = 9, CLOSE = 10, PLUS = 11, UP = 12, DOWN = 13, CHECK_ON = 14, CHECK_OFF = 15,
            FULLSCREEN_EXIT = 16, STAR = 17, SEARCH = 18;

    private final int type;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int sizePx;

    public Icon(int type, float density) {
        this.type = type;
        this.sizePx = Math.round(24 * density);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeJoin(Paint.Join.MITER);
        stroke.setColor(0xFF000000);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF000000);
    }

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        float s = size / 24f;
        float ox = b.left + (b.width() - size) / 2f, oy = b.top + (b.height() - size) / 2f;
        current = c;
        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        stroke.setStrokeWidth(Math.max(1.5f / s, 2.2f));
        path.reset();
        switch (type) {
            case BACK:
                line(15, 5, 8, 12);
                line(8, 12, 15, 19);
                break;
            case FORWARD:
                line(9, 5, 16, 12);
                line(16, 12, 9, 19);
                break;
            case UP:
                line(5, 15, 12, 8);
                line(12, 8, 19, 15);
                break;
            case DOWN:
                line(5, 9, 12, 16);
                line(12, 16, 19, 9);
                break;
            case PAGE_UP:
                line(5, 12, 12, 5);
                line(12, 5, 19, 12);
                line(5, 19, 12, 12);
                line(12, 12, 19, 19);
                break;
            case PAGE_DOWN:
                line(5, 5, 12, 12);
                line(12, 12, 19, 5);
                line(5, 12, 12, 19);
                line(12, 19, 19, 12);
                break;
            case RELOAD: {
                RectF r = new RectF(5, 5, 19, 19);
                c.drawArc(r, -60, 300, false, stroke);
                path.moveTo(15.5f, 2.5f);
                path.lineTo(19.5f, 6.5f);
                path.lineTo(14f, 8.5f);
                path.close();
                c.drawPath(path, fill);
                break;
            }
            case STOP:
            case CLOSE:
                line(6, 6, 18, 18);
                line(18, 6, 6, 18);
                break;
            case PLUS:
                line(12, 5, 12, 19);
                line(5, 12, 19, 12);
                break;
            case MENU:
                c.drawCircle(12, 5.5f, 2f, fill);
                c.drawCircle(12, 12, 2f, fill);
                c.drawCircle(12, 18.5f, 2f, fill);
                break;
            case HOME:
                path.moveTo(4, 11);
                path.lineTo(12, 4);
                path.lineTo(20, 11);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(6.5f, 9.5f);
                path.lineTo(6.5f, 20);
                path.lineTo(17.5f, 20);
                path.lineTo(17.5f, 9.5f);
                c.drawPath(path, stroke);
                c.drawRect(10.5f, 14, 13.5f, 20, fill);
                break;
            case READER:
                line(4, 6, 20, 6);
                line(4, 10, 20, 10);
                line(4, 14, 20, 14);
                line(4, 18, 14, 18);
                break;
            case CHECK_OFF:
                c.drawRect(4, 4, 20, 20, stroke);
                break;
            case CHECK_ON:
                c.drawRect(4, 4, 20, 20, fill);
                Paint white = new Paint(stroke);
                white.setColor(0xFFFFFFFF);
                path.moveTo(7.5f, 12);
                path.lineTo(10.5f, 15.5f);
                path.lineTo(16.5f, 8.5f);
                c.drawPath(path, white);
                break;
            case FULLSCREEN_EXIT:
                line(4, 9, 9, 9);
                line(9, 9, 9, 4);
                line(15, 4, 15, 9);
                line(15, 9, 20, 9);
                line(4, 15, 9, 15);
                line(9, 15, 9, 20);
                line(15, 20, 15, 15);
                line(15, 15, 20, 15);
                break;
            case STAR:
                path.moveTo(12, 3.5f);
                path.lineTo(14.6f, 9.2f);
                path.lineTo(20.5f, 9.6f);
                path.lineTo(16, 13.5f);
                path.lineTo(17.4f, 19.8f);
                path.lineTo(12, 16.5f);
                path.lineTo(6.6f, 19.8f);
                path.lineTo(8, 13.5f);
                path.lineTo(3.5f, 9.6f);
                path.lineTo(9.4f, 9.2f);
                path.close();
                c.drawPath(path, stroke);
                break;
            case SEARCH:
                c.drawCircle(10, 10, 5.5f, stroke);
                line(14.5f, 14.5f, 20, 20);
                break;
            default:
                break;
        }
        c.restore();
        current = null;
    }

    private final Path line = new Path();

    private void line(float x1, float y1, float x2, float y2) {
        // drawLine on some old Skia builds ignores the cap on scaled canvases; a path is consistent.
        line.reset();
        line.moveTo(x1, y1);
        line.lineTo(x2, y2);
        lastCanvasDraw(line);
    }

    private Canvas current;

    private void lastCanvasDraw(Path p) {
        if (current != null) current.drawPath(p, stroke);
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        stroke.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
