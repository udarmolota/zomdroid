package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

public class WasdStickControlElement extends AbstractControlElement {

    private final WasdStickDrawable drawable;

    private int pointerId = -1;

    private boolean wDown, aDown, sDown, dDown;

    private static final float DEADZONE = 0.20f;
    private static final float THRESH   = 0.35f;

    public WasdStickControlElement(InputControlsView parentView, ControlElementDescription desc) {
        super(parentView, desc);

        // Жёстко фиксируем тип: MNK
        this.inputType = InputType.MNK;

        // ВАЖНО: никаких bindings у этого элемента быть не должно
        this.bindings.clear();

        this.drawable = new WasdStickDrawable(parentView, desc);
    }

    @Override
    public void setInputType(InputType inputType) {
        // VKBD-stick всегда MNK, игнорируем попытки переключения
        this.inputType = InputType.MNK;
    }

    private void setKey(int glfwKey, boolean down) {
        InputNativeInterface.sendKeyboard(glfwKey, down);
    }

    private void updateWasdFromStick(float x, float y) {
        // y вверх отрицательный
        boolean w = (y < -THRESH);
        boolean s = (y >  THRESH);
        boolean a = (x < -THRESH);
        boolean d = (x >  THRESH);

        if (w != wDown) { setKey(GLFWBinding.KEY_W.code, w); wDown = w; }
        if (s != sDown) { setKey(GLFWBinding.KEY_S.code, s); sDown = s; }
        if (a != aDown) { setKey(GLFWBinding.KEY_A.code, a); aDown = a; }
        if (d != dDown) { setKey(GLFWBinding.KEY_D.code, d); dDown = d; }
    }

    private void releaseAll() {
        if (wDown) { setKey(GLFWBinding.KEY_W.code, false); wDown = false; }
        if (aDown) { setKey(GLFWBinding.KEY_A.code, false); aDown = false; }
        if (sDown) { setKey(GLFWBinding.KEY_S.code, false); sDown = false; }
        if (dDown) { setKey(GLFWBinding.KEY_D.code, false); dDown = false; }
    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action   = e.getActionMasked();
        int actIndex = e.getActionIndex();
        int pid      = e.getPointerId(actIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerId != -1) return false;
                float x = e.getX(actIndex), y = e.getY(actIndex);
                if (!drawable.isPointOver(x, y)) return false;
                pointerId = pid;
                drawable.setInnerFromTouch(x, y);
                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (pointerId == -1) return false;
                int idx = e.findPointerIndex(pointerId);
                if (idx < 0) return false;

                float x = e.getX(idx), y = e.getY(idx);
                drawable.setInnerFromTouch(x, y);

                // нормализуем [-1..1]
                float nx = drawable.getNormalizedX();
                float ny = drawable.getNormalizedY();

                // deadzone
                if (Math.abs(nx) < DEADZONE) nx = 0;
                if (Math.abs(ny) < DEADZONE) ny = 0;

                updateWasdFromStick(nx, ny);
                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pid != pointerId && action != MotionEvent.ACTION_CANCEL) return false;
                pointerId = -1;
                drawable.resetInner();
                releaseAll();
                parentView.invalidate();
                return true;
            }
        }

        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        drawable.draw(canvas);
    }

    @Override
    public boolean isPointOver(float x, float y) {
        return drawable.isPointOver(x, y);
    }

    @Override
    public void setHighlighted(boolean highlighted) {
        drawable.setColorFilter(highlighted ? HIGHLIGHT_COLOR_FILTER : null);
        parentView.invalidate();
    }

    @Override
    public void setAlpha(int alpha) {
        drawable.setAlpha(alpha);
        parentView.invalidate();
    }

    @Override
    public int getAlpha() {
        return drawable.alpha;
    }

    @Override
    public void setScale(float value) {
        value = Math.clamp(value, MIN_SCALE, MAX_SCALE);
        drawable.setScale(value);
        parentView.invalidate();
    }

    @Override
    public float getScale() {
        return drawable.scale;
    }

    @Override
    public void setCenterPosition(float x, float y) {
        drawable.setCenterPosition(x, y);
        parentView.invalidate();
    }

    @Override
    public void moveCenterPosition(float dx, float dy) {
        drawable.moveCenterPosition(dx, dy);
        parentView.invalidate();
    }

    @Override
    public float getCenterX() {
        return drawable.outerCenterX;
    }

    @Override
    public ControlElementDescription describe() {
        // ВАЖНО: bindings должны быть пустыми
        return new ControlElementDescription(
                drawable.outerCenterX / parentView.getWidth(),
                drawable.outerCenterY / parentView.getHeight(),
                drawable.scale,
                Type.STICK_WASD,
                new GLFWBinding[0],
                null,
                drawable.color,
                drawable.alpha,
                InputType.MNK,
                ControlElementDescription.Icon.NO_ICON,
                false
        );
    }

    // ====================== Drawable: как обычный StickControlElement ======================

    static class WasdStickDrawable {
        private static final int   PAINT_STROKE_WIDTH   = 3;
        private static final float OUTER_CIRCLE_RADIUS  = 160.f;
        private static final float INNER_CIRCLE_RADIUS  = 90.f;

        int color;
        int alpha;
        float scale;

        float outerRadius;
        float innerRadius;

        float outerCenterX;
        float outerCenterY;

        float innerCenterX;
        float innerCenterY;

        private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final InputControlsView parentView;

        WasdStickDrawable(InputControlsView parentView, ControlElementDescription desc) {
            this.parentView = parentView;

            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);

            setColor(desc.color);
            setAlpha(desc.alpha);
            setScale(desc.scale);

            // ВАЖНО: relative поля
            setCenterPosition(
                    desc.centerXRelative * parentView.getWidth(),
                    desc.centerYRelative * parentView.getHeight()
            );

            resetInner();
        }

        void setColor(int c) {
            color = c;
            fillPaint.setColor(c);
            strokePaint.setColor(c);
        }

        void setAlpha(int a) {
            alpha = a;
            fillPaint.setAlpha(a / 3);
            strokePaint.setAlpha(a);
        }

        void setColorFilter(@Nullable ColorFilter cf) {
            fillPaint.setColorFilter(cf);
            strokePaint.setColorFilter(cf);
        }

        void setScale(float s) {
            scale = s;
            float px = parentView.pixelScale * scale;
            outerRadius = OUTER_CIRCLE_RADIUS * px;
            innerRadius = INNER_CIRCLE_RADIUS * px;
        }

        void setCenterPosition(float x, float y) {
            outerCenterX = x;
            outerCenterY = y;
            resetInner();
        }

        void moveCenterPosition(float dx, float dy) {
            setCenterPosition(outerCenterX + dx, outerCenterY + dy);
        }

        void resetInner() {
            innerCenterX = outerCenterX;
            innerCenterY = outerCenterY;
        }

        boolean isPointOver(float x, float y) {
            float dx = x - outerCenterX;
            float dy = y - outerCenterY;
            return dx * dx + dy * dy <= outerRadius * outerRadius;
        }

        void setInnerFromTouch(float x, float y) {
            float dx = x - outerCenterX;
            float dy = y - outerCenterY;

            float max = outerRadius - innerRadius;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > max && len > 0.0001f) {
                float k = max / len;
                dx *= k;
                dy *= k;
            }

            innerCenterX = outerCenterX + dx;
            innerCenterY = outerCenterY + dy;
        }

        float getNormalizedX() {
            float max = (outerRadius - innerRadius);
            if (max <= 0.0001f) return 0f;
            return (innerCenterX - outerCenterX) / max;
        }

        float getNormalizedY() {
            float max = (outerRadius - innerRadius);
            if (max <= 0.0001f) return 0f;
            return (innerCenterY - outerCenterY) / max;
        }

        void draw(Canvas canvas) {
            strokePaint.setStrokeWidth(PAINT_STROKE_WIDTH * parentView.pixelScale);

            // outer
            canvas.drawCircle(outerCenterX, outerCenterY, outerRadius, strokePaint);
            canvas.drawCircle(outerCenterX, outerCenterY, outerRadius, fillPaint);

            // inner
            canvas.drawCircle(innerCenterX, innerCenterY, innerRadius, strokePaint);
            canvas.drawCircle(innerCenterX, innerCenterY, innerRadius, fillPaint);
        }
    }
}