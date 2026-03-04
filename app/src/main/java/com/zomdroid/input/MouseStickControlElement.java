package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import android.graphics.Path;

public class MouseStickControlElement extends AbstractControlElement {

    private final MouseStickDrawable drawable;

    private int pointerId = -1;
    private static final float SENSITIVITY = 2.0f; // можно потом сделать 1.2–1.8, если надо медленнее
    private float lastX, lastY;

    // “курсор” для crosshair (в координатах overlay)
    private double cursorX = -1;
    private double cursorY = -1;

    private final Paint cursorFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MouseStickControlElement(InputControlsView parentView, ControlElementDescription desc) {
        super(parentView, desc);

        this.inputType = InputType.MNK;
        this.bindings.clear();

        this.drawable = new MouseStickDrawable(parentView, desc);

        cursorFill.setStyle(Paint.Style.FILL);
        cursorFill.setColor(Color.WHITE);
        cursorFill.setAlpha(220);

        cursorStroke.setStyle(Paint.Style.STROKE);
        cursorStroke.setColor(Color.BLACK);
        cursorStroke.setStrokeWidth(2f * parentView.pixelScale);
        cursorStroke.setStrokeJoin(Paint.Join.ROUND);
        cursorStroke.setStrokeCap(Paint.Cap.ROUND);
        cursorStroke.setAlpha(220);

        // старт: “курсор” в центре стика
        cursorX = drawable.outerCenterX;
        cursorY = drawable.outerCenterY;
    }

    @Override
    public void setInputType(InputType inputType) {
        this.inputType = InputType.MNK;
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
                lastX = x;
                lastY = y;
                drawable.setInnerFromTouch(x, y);
                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (pointerId == -1) return false;
                int idx = e.findPointerIndex(pointerId);
                if (idx < 0) return false;

                float x = e.getX(idx), y = e.getY(idx);

                // Визуально двигаем inner-кружок как раньше (чтобы выглядело как стик)
                drawable.setInnerFromTouch(x, y);

                // ТАЧПАДНАЯ ЛОГИКА: курсор двигается по реальному delta пальца
                float dx = (x - lastX) * SENSITIVITY;
                float dy = (y - lastY) * SENSITIVITY;

                lastX = x;
                lastY = y;

                // курсор в пределах экрана overlay
                cursorX = clamp(cursorX + dx, 0, parentView.getWidth()  - 1);
                cursorY = clamp(cursorY + dy, 0, parentView.getHeight() - 1);

                double rs = parentView.getRenderScale();
                InputNativeInterface.sendCursorPos(cursorX * rs, cursorY * rs);

                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pid != pointerId && action != MotionEvent.ACTION_CANCEL) return false;
                pointerId = -1;
                drawable.resetInner();
                parentView.invalidate();
                return true;
            }
        }

        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        drawable.draw(canvas);

        // crosshair (как в TouchpadControlElement)
        if (cursorX >= 0 && cursorY >= 0) {
            // Arrow cursor (Windows-like). Tip is exactly at (x, y).
            float x = (float) cursorX;
            float y = (float) cursorY;
            float s = parentView.pixelScale;
            float scale = 0.65f;
            // Геометрия треугольника
            float h = 75f * s * scale;   // длина
            float w = 45f * s * scale;   // ширина

            // параметры выемки
            float notchX = 0.55f;   // где по ширине выемка (0..1)
            float notchIn = 0.28f;  // насколько “внутрь” (доля w)

            Path p = new Path();
            p.moveTo(x, y);                      // TIP

            // два основания, но со смещением по X/Y, чтобы был наклон
            p.lineTo(x + w,       y + h * 0.85f);   // нижний левый
            p.lineTo(x + w * notchX, y + h - w * notchIn); // выемка на нижней грани: точка чуть выше/левее линии основания
            p.lineTo(x + w * 0.15f, y + h);         // нижний “правее”
            p.close();

            canvas.drawPath(p, cursorFill);
            canvas.drawPath(p, cursorStroke);
        }
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
        return new ControlElementDescription(
                drawable.outerCenterX / parentView.getWidth(),
                drawable.outerCenterY / parentView.getHeight(),
                drawable.scale,
                Type.STICK_MOUSE,
                new GLFWBinding[0],
                null,
                drawable.color,
                drawable.alpha,
                InputType.MNK,
                ControlElementDescription.Icon.NO_ICON,
                false
        );
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ====================== Drawable: как обычный StickControlElement ======================

    static class MouseStickDrawable {
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

        MouseStickDrawable(InputControlsView parentView, ControlElementDescription desc) {
            this.parentView = parentView;

            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);

            setColor(desc.color);
            setAlpha(desc.alpha);
            setScale(desc.scale);

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

            canvas.drawCircle(outerCenterX, outerCenterY, outerRadius, strokePaint);
            canvas.drawCircle(outerCenterX, outerCenterY, outerRadius, fillPaint);

            canvas.drawCircle(innerCenterX, innerCenterY, innerRadius, strokePaint);
            canvas.drawCircle(innerCenterX, innerCenterY, innerRadius, fillPaint);
        }
    }
}