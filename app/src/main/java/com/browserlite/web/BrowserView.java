package com.browserlite.web;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.WebView;

/**
 * WebView with e-ink page turning gestures: a vertical swipe can turn a whole page instead of scrolling
 * (scrolling smears ghosting across the panel), and taps on the screen edges can turn pages too.
 * Horizontal swipes, pinch zoom, taps and long presses reach the page untouched.
 */
public final class BrowserView extends WebView {
    public interface Listener {
        void onPageTurnGesture(int direction);

        void onScrolled();
    }

    private static final int IDLE = 0, UNDECIDED = 1, SWIPE = 2, PASS = 3;

    private Listener listener;
    private boolean swipePages, tapZones;
    private final int slop;
    private int mode = IDLE;
    private boolean edge;
    private float downX, downY;
    private MotionEvent heldDown;

    public BrowserView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setGestures(boolean swipePages, boolean tapZones) {
        this.swipePages = swipePages;
        this.tapZones = tapZones;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (listener != null) listener.onScrolled();
    }

    private void releaseHeld() {
        if (heldDown != null) {
            heldDown.recycle();
            heldDown = null;
        }
    }

    private void forwardHeld() {
        if (heldDown != null) {
            super.onTouchEvent(heldDown);
            releaseHeld();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!swipePages && !tapZones) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                releaseHeld();
                downX = e.getX();
                downY = e.getY();
                mode = UNDECIDED;
                int w = getWidth();
                edge = tapZones && (downX < w * 0.12f || downX > w * 0.88f);
                if (edge) {
                    heldDown = MotionEvent.obtain(e);
                    return true;
                }
                return super.onTouchEvent(e);
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mode != PASS) {
                    forwardHeld();
                    mode = PASS;
                }
                return super.onTouchEvent(e);
            case MotionEvent.ACTION_MOVE: {
                if (mode == UNDECIDED) {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        boolean vertical = Math.abs(dy) > Math.abs(dx);
                        if (swipePages && vertical) {
                            mode = SWIPE;
                            if (!edge) {
                                MotionEvent cancel = MotionEvent.obtain(e);
                                cancel.setAction(MotionEvent.ACTION_CANCEL);
                                super.onTouchEvent(cancel);
                                cancel.recycle();
                            }
                            releaseHeld();
                            return true;
                        }
                        mode = PASS;
                        forwardHeld();
                    } else {
                        return edge || super.onTouchEvent(e);
                    }
                }
                if (mode == SWIPE) return true;
                return super.onTouchEvent(e);
            }
            case MotionEvent.ACTION_UP: {
                int m = mode;
                mode = IDLE;
                if (m == SWIPE) {
                    float dy = e.getY() - downY;
                    if (Math.abs(dy) > slop && listener != null) listener.onPageTurnGesture(dy < 0 ? 1 : -1);
                    return true;
                }
                if (edge && m == UNDECIDED) {
                    releaseHeld();
                    if (listener != null) listener.onPageTurnGesture(downX > getWidth() / 2f ? 1 : -1);
                    return true;
                }
                return super.onTouchEvent(e);
            }
            case MotionEvent.ACTION_CANCEL:
                releaseHeld();
                mode = IDLE;
                return super.onTouchEvent(e);
            default:
                if (mode == SWIPE) return true;
                return super.onTouchEvent(e);
        }
    }
}
