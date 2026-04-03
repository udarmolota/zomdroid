package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import android.graphics.Path;

public class TouchpadControlElement extends AbstractControlElement {

    private static final String TAG = "ZomdroidTouch";

    private static final float BASE_WIDTH  = 560f;
    private static final float BASE_HEIGHT = 370f;
    private static final float SENSITIVITY = 2.0f;
    private static final float TAP_SLOP    = 12f;
    private static final long  TAP_MAX_MS  = 250;

    private final TouchpadDrawable drawable;
    private int    pointerId = -1;
    private float  lastX, lastY;
    private float  downX, downY;
    private long   downTime;
    private double cursorX = -1;
    private double cursorY = -1;

    // Debug: draw a visible cursor dot on top of everything
    private static final boolean DEBUG_DRAW_CURSOR = true;

    private final Paint debugCursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugCursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TouchpadControlElement(InputControlsView parentView,
                                  ControlElementDescription description) {
        super(parentView, description);
        this.drawable = new TouchpadDrawable(parentView, description);
        debugCursorFill.setStyle(Paint.Style.FILL);
        debugCursorFill.setColor(Color.WHITE);
        debugCursorFill.setAlpha(220);

        debugCursorStroke.setStyle(Paint.Style.STROKE);
        debugCursorStroke.setStrokeWidth(3f * parentView.pixelScale);
        debugCursorStroke.setColor(Color.BLACK);
        debugCursorStroke.setAlpha(220);
        //Log.d(TAG, "created — rect=" + drawable.rect
        //        + " parentSize=" + parentView.getWidth() + "x" + parentView.getHeight()
        //        + " pixelScale=" + parentView.pixelScale);
    }

    @Override
    public void setInputType(InputType inputType) {
        this.inputType = inputType;
    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action   = e.getActionMasked();
        int actIndex = e.getActionIndex();
        int pid      = e.getPointerId(actIndex);

        // Log every event so we can confirm delivery
        /*Log.v("ZomdroidTouch", "handleMotionEvent action=" + action
                + " pid=" + pid + " trackedPid=" + pointerId
                + " xy=(" + e.getX(actIndex) + "," + e.getY(actIndex) + ")"
                + " rect=" + drawable.rect);
        */
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerId >= 0) {
                    //7778777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777 Log.d(TAG, "DOWN ignored — already tracking pid=" + pointerId);
                    return false;
                }
                float x = e.getX(actIndex);
                float y = e.getY(actIndex);
                boolean over = drawable.isPointOver(x, y);
                //Log.d(TAG, "DOWN isPointOver=" + over
                //        + " touch=(" + x + "," + y + ")"
                //        + " rect=" + drawable.rect);
                if (!over) return false;

                pointerId = pid;
                lastX = x;  lastY = y;
                downX = x;  downY = y;
                downTime = System.currentTimeMillis();

                if (cursorX < 0) {
                    cursorX = parentView.getWidth()  / 2.0;
                    cursorY = parentView.getHeight() / 2.0;
                    //Log.d(TAG, "cursor lazy-init to (" + cursorX + "," + cursorY + ")");
                }

                double rs = parentView.getRenderScale();
                InputNativeInterface.sendCursorPos(cursorX * rs, cursorY * rs);
                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (pointerId < 0) return false;
                int idx = e.findPointerIndex(pointerId);
                if (idx < 0) { pointerId = -1; return false; }

                float x = e.getX(idx);
                float y = e.getY(idx);
                float dx = (x - lastX) * SENSITIVITY;
                float dy = (y - lastY) * SENSITIVITY;
                lastX = x;  lastY = y;

                cursorX = clamp(cursorX + dx, 0, parentView.getWidth());
                cursorY = clamp(cursorY + dy, 0, parentView.getHeight());

                //Log.v(TAG, "MOVE delta=(" + dx + "," + dy
                //        + ") cursor=(" + cursorX + "," + cursorY + ")");
                //InputNativeInterface.sendCursorPos(cursorX, cursorY);
                double rs = parentView.getRenderScale();
                InputNativeInterface.sendCursorPos(cursorX * rs, cursorY * rs);
                parentView.invalidate();
                //Log.v(TAG, "MOVE sendCursorPos=(" + (cursorX*rs) + "," + (cursorY*rs) + "), rs=" + rs);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (pid != pointerId) return false;
                pointerId = -1;

                float totalDist = dist(e.getX(actIndex), e.getY(actIndex), downX, downY);
                long elapsed    = System.currentTimeMillis() - downTime;
                boolean isTap   = totalDist < TAP_SLOP && elapsed < TAP_MAX_MS;
                Log.d(TAG, "UP dist=" + totalDist + " elapsed=" + elapsed
                        + "ms isTap=" + isTap);

                if (isTap) {
                    Log.d(TAG, "TAP → sendMouseButton LEFT code="
                            + GLFWBinding.MOUSE_BUTTON_LEFT.code);
                    double rs = parentView.getRenderScale();
                    InputNativeInterface.sendCursorPos(cursorX * rs, cursorY * rs);
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                    parentView.postDelayed(() -> {
                        InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                    }, 50);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                Log.d(TAG, "CANCEL");
                pointerId = -1;
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        drawable.draw(canvas);

        if (!DEBUG_DRAW_CURSOR) return;
        if (cursorX < 0 || cursorY < 0) return;

        float x = (float) cursorX;
        float y = (float) cursorY;
        float s = parentView.pixelScale;
        float scale = 0.55f;

        float h = 75f * s * scale;
        float w = 50f * s * scale;

        float notchX = 0.55f;
        float notchIn = 0.28f;

        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + w,          y + h * 0.85f);
        p.lineTo(x + w * notchX, y + h - w * notchIn);
        p.lineTo(x + w * 0.15f,  y + h);
        p.close();

        canvas.drawPath(p, debugCursorFill);
        canvas.drawPath(p, debugCursorStroke);

        //float dotR = 5f * s * scale;
        //canvas.drawCircle(x, y, dotR, debugCursorFill);
        //canvas.drawCircle(x, y, dotR, debugCursorStroke);
    }
    @Override public boolean isPointOver(float x, float y)      { return drawable.isPointOver(x, y); }
    @Override public void setHighlighted(boolean h)             { drawable.setColorFilter(h ? HIGHLIGHT_COLOR_FILTER : null); parentView.invalidate(); }
    @Override public void setAlpha(int alpha)                   { drawable.setAlpha(alpha); parentView.invalidate(); }
    @Override public int getAlpha()                             { return drawable.alpha; }
    @Override public void setScale(float scale)                 { drawable.setScale(Math.clamp(scale, MIN_SCALE, MAX_SCALE)); parentView.invalidate(); }
    @Override public float getScale()                           { return drawable.scale; }
    @Override public void setCenterPosition(float x, float y)  { drawable.setCenterPosition(x, y); parentView.invalidate(); }
    @Override public void moveCenterPosition(float dx, float dy){ drawable.moveCenterPosition(dx, dy); parentView.invalidate(); }
    @Override public float getCenterX()                         { return drawable.centerX; }

    @Override
    public ControlElementDescription describe() {
        return new ControlElementDescription(
                drawable.centerX / parentView.getWidth(),
                drawable.centerY / parentView.getHeight(),
                drawable.scale, Type.TOUCHPAD,
                new GLFWBinding[0], null,
                drawable.color, drawable.alpha,
                InputType.MNK,
                ControlElementDescription.Icon.NO_ICON, false);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1-x2, dy = y1-y2;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    // -------------------------------------------------------------------------

    static class TouchpadDrawable {
        private static final int   CORNER_RADIUS_DP = 16;
        private static final float STROKE_WIDTH_DP  = 2f;

        int   color, alpha;
        float scale, centerX, centerY;
        private float halfW, halfH;
        final RectF rect = new RectF();

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final InputControlsView parentView;

        TouchpadDrawable(InputControlsView parentView, ControlElementDescription desc) {
            this.parentView = parentView;
            fillPaint.setStyle(Paint.Style.FILL);
            rimPaint .setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            setColor(desc.color);
            setAlpha(desc.alpha);
            setScale(desc.scale);
            setCenterPosition(desc.centerXRelative * parentView.getWidth(),
                    desc.centerYRelative * parentView.getHeight());
        }

        void draw(Canvas canvas) {
            float cr = CORNER_RADIUS_DP * parentView.pixelScale * scale;
            rimPaint.setColor(Color.rgb(30, 30, 30));
            rimPaint.setAlpha(80);
            rimPaint.setStrokeWidth((STROKE_WIDTH_DP + 3f) * parentView.pixelScale);
            canvas.drawRoundRect(rect, cr, cr, rimPaint);
            canvas.drawRoundRect(rect, cr, cr, fillPaint);
            rimPaint.setColor(color);
            rimPaint.setAlpha(alpha);
            rimPaint.setStrokeWidth(STROKE_WIDTH_DP * parentView.pixelScale);
            canvas.drawRoundRect(rect, cr, cr, rimPaint);
            float textSize = 28f * parentView.pixelScale * scale;
            textPaint.setTextSize(textSize);
            textPaint.setAlpha(Math.min(255, alpha + 60));
            canvas.drawText("TOUCH", centerX, centerY + textSize * 0.35f, textPaint);
        }

        void setColor(int c) { color=c; fillPaint.setColor(c); rimPaint.setColor(c); textPaint.setColor(c); }
        void setAlpha(int a) { alpha=a; fillPaint.setAlpha(a/3); rimPaint.setAlpha(a); textPaint.setAlpha(Math.min(255,a+60)); }
        void setColorFilter(@Nullable ColorFilter cf) { fillPaint.setColorFilter(cf); rimPaint.setColorFilter(cf); }
        void setScale(float s) { scale=s; updateDimensions(); }
        void setCenterPosition(float x, float y) { centerX=x; centerY=y; updateBounds(); }
        void moveCenterPosition(float dx, float dy) { setCenterPosition(centerX+dx, centerY+dy); }
        boolean isPointOver(float x, float y) { return rect.contains(x, y); }

        private void updateDimensions() {
            halfW = BASE_WIDTH  * parentView.pixelScale * scale / 2f;
            halfH = BASE_HEIGHT * parentView.pixelScale * scale / 2f;
            updateBounds();
        }
        private void updateBounds() {
            rect.set(centerX-halfW, centerY-halfH, centerX+halfW, centerY+halfH);
        }
    }
}