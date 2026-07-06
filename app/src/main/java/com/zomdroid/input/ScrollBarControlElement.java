package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import androidx.annotation.Nullable;

/**
 * Vertical drag bar that converts vertical touch movement into mouse-wheel
 * scroll ticks (MNK). Useful for scrolling the in-game inventory on touch.
 */
public class ScrollBarControlElement extends AbstractControlElement {

    private final ScrollBarDrawable drawable;

    private int pointerId = -1;
    private float sensitivity;
    private float lastY;

    public ScrollBarControlElement(InputControlsView parentView, ControlElementDescription desc) {
        super(parentView, desc);

        this.inputType = InputType.MNK;
        this.sensitivity = (desc.sensitivity > 0f) ? desc.sensitivity : ControlElementDescription.DEFAULT_SENSITIVITY;
        this.drawable = new ScrollBarDrawable(parentView, desc);
    }

    @Override
    public void setInputType(InputType inputType) {
        this.inputType = InputType.MNK;
    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actIndex = e.getActionIndex();
        int pid = e.getPointerId(actIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerId != -1) return false;
                float x = e.getX(actIndex), y = e.getY(actIndex);
                if (!drawable.isPointOver(x, y)) return false;

                pointerId = pid;
                lastY = y;
                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (pointerId == -1) return false;
                int idx = e.findPointerIndex(pointerId);
                if (idx < 0) return false;

                float y = e.getY(idx);
                float dy = y - lastY;

                float scrollThreshold = 25f * parentView.pixelScale;
                float scaledDelta = dy * sensitivity;

                if (Math.abs(scaledDelta) > scrollThreshold) {
                    float scrollDir = (dy > 0) ? -1.0f : 1.0f;
                    InputNativeInterface.sendMouseScroll(0, scrollDir);
                    lastY = y;
                }

                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pid != pointerId && action != MotionEvent.ACTION_CANCEL) return false;
                pointerId = -1;
                parentView.invalidate();
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        drawable.setIsPressed(pointerId != -1);
        drawable.draw(canvas);
    }

    @Override public boolean isPointOver(float x, float y)       { return drawable.isPointOver(x, y); }
    @Override public void setHighlighted(boolean highlighted)     { drawable.setColorFilter(highlighted ? HIGHLIGHT_COLOR_FILTER : null); parentView.invalidate(); }
    @Override public void setAlpha(int alpha)                     { drawable.setAlpha(alpha); parentView.invalidate(); }
    @Override public int getAlpha()                               { return drawable.alpha; }
    @Override public void setScale(float value)                   { value = Math.clamp(value, MIN_SCALE, MAX_SCALE); drawable.setScale(value); parentView.invalidate(); }
    @Override public float getScale()                             { return drawable.scale; }
    @Override public void setCenterPosition(float x, float y)    { drawable.setCenterPosition(x, y); parentView.invalidate(); }
    @Override public void moveCenterPosition(float dx, float dy)  { drawable.moveCenterPosition(dx, dy); parentView.invalidate(); }
    @Override public float getCenterX()                           { return drawable.centerX; }

    public void setSensitivity(float s) {
        this.sensitivity = Math.clamp(s, ControlElementDescription.MIN_SENSITIVITY, ControlElementDescription.MAX_SENSITIVITY);
    }

    public float getSensitivity() { return sensitivity; }

    @Override
    public ControlElementDescription describe() {
        return new ControlElementDescription(
                drawable.centerX / parentView.getWidth(),
                drawable.centerY / parentView.getHeight(),
                drawable.scale,
                Type.SCROLL_BAR,
                new GLFWBinding[0],
                null,
                drawable.color,
                drawable.alpha,
                InputType.MNK,
                ControlElementDescription.Icon.NO_ICON,
                false,
                sensitivity
        );
    }

    @Override public void setText(String text) {}
    @Override public String getText() { return "SCROLL"; }
    @Override public void setIcon(ControlElementDescription.Icon icon) {}
    @Override public ControlElementDescription.Icon getIcon() { return null; }
    @Override public void addBinding(GLFWBinding binding) {}
    @Override public void setBinding(int index, GLFWBinding binding) {}
    @Override public void removeBinding(int index) {}


    static class ScrollBarDrawable {
        // Slim, tall, capsule-shaped bar — visual style suggested by user Willing-Run-8987.
        private static final float STROKE_WIDTH_DP = 3f;
        private static final float CORNER_RADIUS_DP = 30f;
        private static final float BASE_WIDTH  = 50.f;
        private static final float BASE_HEIGHT = 350.f;

        int color;
        int alpha;
        float scale;
        float centerX, centerY;
        float width, height;
        private boolean isPressed = false;

        private final RectF rect = new RectF();
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final InputControlsView parentView;

        ScrollBarDrawable(InputControlsView parentView, ControlElementDescription desc) {
            this.parentView = parentView;

            fillPaint.setStyle(Paint.Style.FILL);
            rimPaint.setStyle(Paint.Style.STROKE);
            linePaint.setStyle(Paint.Style.STROKE);

            setColor(desc.color);
            setAlpha(desc.alpha);
            setScale(desc.scale);

            setCenterPosition(
                    desc.centerXRelative * parentView.getWidth(),
                    desc.centerYRelative * parentView.getHeight()
            );
        }

        void setColor(int c) {
            color = c;
            fillPaint.setColor(c);
            rimPaint.setColor(c);
            linePaint.setColor(c);
        }

        void setAlpha(int a) {
            alpha = a;
            fillPaint.setAlpha(a / 3);
            rimPaint.setAlpha(a);
            linePaint.setAlpha(a);
        }

        void setColorFilter(@Nullable ColorFilter cf) {
            fillPaint.setColorFilter(cf);
            rimPaint.setColorFilter(cf);
            linePaint.setColorFilter(cf);
        }

        void setScale(float s) {
            scale = s;
            float px = parentView.pixelScale * scale;
            width = BASE_WIDTH * px;
            height = BASE_HEIGHT * px;
            updateBounds();
        }

        void setCenterPosition(float x, float y) {
            centerX = x;
            centerY = y;
            updateBounds();
        }

        void moveCenterPosition(float dx, float dy) {
            setCenterPosition(centerX + dx, centerY + dy);
        }

        private void updateBounds() {
            rect.set(centerX - width/2, centerY - height/2, centerX + width/2, centerY + height/2);
        }

        boolean isPointOver(float x, float y) {
            return rect.contains(x, y);
        }

        void setIsPressed(boolean pressed) {
            this.isPressed = pressed;
        }

        void draw(Canvas canvas) {
            float cr = CORNER_RADIUS_DP * parentView.pixelScale * scale;

            rimPaint.setColor(android.graphics.Color.rgb(30, 30, 30));
            rimPaint.setAlpha(Math.min(alpha, 80));
            rimPaint.setStrokeWidth((STROKE_WIDTH_DP + 3f) * parentView.pixelScale);
            canvas.drawRoundRect(rect, cr, cr, rimPaint);

            fillPaint.setColor(color);
            fillPaint.setAlpha(alpha / 3);
            canvas.drawRoundRect(rect, cr, cr, fillPaint);

            int feedbackColor = ((isPressed && !parentView.isEditMode && parentView.showPressFeedback) ? android.graphics.Color.parseColor("#FF8800") : color);

            rimPaint.setColor(feedbackColor);
            rimPaint.setAlpha(alpha);
            rimPaint.setStrokeWidth(STROKE_WIDTH_DP * parentView.pixelScale);
            canvas.drawRoundRect(rect, cr, cr, rimPaint);

            // Grip = 3 short horizontal ridges stacked in the centre. The slim capsule is too
            // narrow for a single full-width line. Capsule + grip suggested by user Willing-Run-8987.
            linePaint.setColor(feedbackColor);
            linePaint.setAlpha(alpha);
            linePaint.setStrokeWidth(4f * parentView.pixelScale);
            float ridgeHalf = width * 0.22f;
            float ridgeGap = 9f * parentView.pixelScale * scale;
            for (int i = -1; i <= 1; i++) {
                float ly = centerY + i * ridgeGap;
                canvas.drawLine(centerX - ridgeHalf, ly, centerX + ridgeHalf, ly, linePaint);
            }
        }
    }
}
