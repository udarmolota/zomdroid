package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

public class StickControlElement extends AbstractControlElement {
    public static final float SQRT_2 = (float) Math.sqrt(2.0);
    private static final float STICK_DEAD_ZONE = 0.3f;
    private final StickControlDrawable drawable;
    int pointerId = -1;

    public StickControlElement(InputControlsView parentView, ControlElementDescription elementDescription) {
        super(parentView, elementDescription);
        this.drawable = new StickControlDrawable(parentView, elementDescription);
        this.bindings.clear();
        if (elementDescription.bindings != null) {
          this.bindings.addAll(Arrays.asList(elementDescription.bindings));
        }
    }

    @Override
    public void setInputType(InputType inputType) {
        var oldBindings = new ArrayList<>(this.bindings);
        clearBindings();
        this.inputType = inputType;
        if (this.inputType == InputType.MNK) {
            this.bindings.add(GLFWBinding.KEY_A);
            this.bindings.add(GLFWBinding.KEY_W);
            this.bindings.add(GLFWBinding.KEY_D);
            this.bindings.add(GLFWBinding.KEY_S);
        } else if (this.inputType == InputType.GAMEPAD) {
          GLFWBinding stick = GLFWBinding.LEFT_JOYSTICK;
          if (!oldBindings.isEmpty()) {
            GLFWBinding b = oldBindings.get(0);
            if (b == GLFWBinding.LEFT_JOYSTICK || b == GLFWBinding.RIGHT_JOYSTICK) {
              stick = b;
            }
          }
          this.bindings.add(stick);
        }
    }

    private void dispatchEvent() {
        float dx = this.drawable.innerCenterX - this.drawable.outerCenterX;
        float dy = this.drawable.innerCenterY - this.drawable.outerCenterY;
        float r = this.drawable.outerRadius;
        float nx = Math.clamp(dx * SQRT_2 / r, -1.f, 1.f);
        float ny = Math.clamp(dy * SQRT_2 / r, -1.f, 1.f);

        switch (this.inputType) {
            case MNK:
                handleMNKBinding(getBindingUp(), ny < -STICK_DEAD_ZONE);
                handleMNKBinding(getBindingRight(), nx > STICK_DEAD_ZONE);
                handleMNKBinding(getBindingDown(), ny > STICK_DEAD_ZONE);
                handleMNKBinding(getBindingLeft(), nx < -STICK_DEAD_ZONE);
                break;
            case GAMEPAD:
                switch (getBindingStick()) {
                    case LEFT_JOYSTICK: {
                        InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_LX.code, nx);
                        InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_LY.code, ny);
                        break;
                    }
                    case RIGHT_JOYSTICK: {
                        InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RX.code, nx);
                        InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RY.code, ny);
                        break;
                    }
                }
                break;
        }

    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        int pointerId = e.getPointerId(actionIndex);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(actionIndex);
                float y = e.getY(actionIndex);
                if (!this.drawable.isPointOver(x, y)) return false;
                this.pointerId = pointerId;
                this.drawable.setInnerPosition(x, y);
                this.parentView.invalidate();
                this.dispatchEvent();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (this.pointerId < 0) return false;
                int pointerIndex = e.findPointerIndex(this.pointerId);
                if (pointerIndex < 0) {
                    this.pointerId = -1;
                    return false;
                }
                float x = e.getX(pointerIndex);
                float y = e.getY(pointerIndex);
                this.drawable.setInnerPosition(x, y);
                this.parentView.invalidate();
                this.dispatchEvent();
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerId != this.pointerId) return false;
                this.pointerId = -1;
                drawable.resetInnerPosition();
                this.parentView.invalidate();
                this.dispatchEvent();
                return true;
        }
        return false;
    }

    @Override
    public float getCenterX() {
        return this.drawable.outerCenterX;
    }

    @Override
    public void draw(Canvas canvas) {
        drawable.draw(canvas);
    }

    @Override
    public boolean isPointOver(float x, float y) {
        return this.drawable.isPointOver(x, y);
    }

    @Override
    public void setAlpha(int alpha) {
        this.drawable.setAlpha(alpha);
        this.parentView.invalidate();
    }

    @Override
    public int getAlpha() {
        return this.drawable.alpha;
    }

    @Override
    public void setScale(float scale) {
        scale = Math.clamp(scale, MIN_SCALE, MAX_SCALE);
        this.drawable.setScale(scale);
        this.parentView.invalidate();
    }

    @Override
    public float getScale() {
        return this.drawable.scale;
    }

    @Override
    public void setCenterPosition(float x, float y) {
        this.drawable.setCenterPosition(x, y);
        this.parentView.invalidate();
    }

    @Override
    public void moveCenterPosition(float dx, float dy) {
        this.drawable.moveCenterPosition(dx, dy);
        this.parentView.invalidate();
    }

    @Override
    public void setBindingLeft(GLFWBinding binding) {
        this.bindings.set(0, binding);
    }

    @Override
    public GLFWBinding getBindingLeft() {
        return this.bindings.get(0);
    }

    @Override
    public void setBindingUp(GLFWBinding binding) {
        this.bindings.set(1, binding);
    }

    @Override
    public GLFWBinding getBindingUp() {
        return this.bindings.get(1);
    }

    @Override
    public void setBindingRight(GLFWBinding binding) {
        this.bindings.set(2, binding);
    }

    @Override
    public GLFWBinding getBindingRight() {
        return this.bindings.get(2);
    }

    @Override
    public void setBindingDown(GLFWBinding binding) {
        this.bindings.set(3, binding);
    }

    @Override
    public GLFWBinding getBindingDown() {
        return this.bindings.get(3);
    }

    @Override
    public void setBindingStick(GLFWBinding binding) {
        this.bindings.set(0, binding);
    }

    @Override
    public GLFWBinding getBindingStick() {
        return this.bindings.get(0);
    }

    @Override
    public void setHighlighted(boolean highlighted) {
        if (highlighted) {
            this.drawable.setColorFilter(HIGHLIGHT_COLOR_FILTER);
        } else {
            this.drawable.setColorFilter(null);
        }
        this.parentView.invalidate();
    }

    public ControlElementDescription describe() {
        return new ControlElementDescription(
                this.drawable.outerCenterX / this.parentView.getWidth(),
                this.drawable.outerCenterY / this.parentView.getHeight(),
                this.drawable.scale, Type.STICK,
                this.bindings.toArray(new GLFWBinding[0]), null, this.drawable.color,
                this.drawable.alpha,
                this.inputType, ControlElementDescription.Icon.NO_ICON,
                false);
    }

    public class StickControlDrawable {
        private static final int PAINT_STROKE_WIDTH = 6;
        private static final float OUTER_CIRCLE_RADIUS = 160.f;
        private static final float INNER_CIRCLE_RADIUS = 90.f;

        int color;
        int alpha;
        private float scale;
        private float outerRadius;
        private float outerCenterX;
        private float outerCenterY;
        private final ShapeDrawable outerShapeDrawable = new ShapeDrawable(new OvalShape());
        private float innerRadius;
        private float innerCenterX;
        private float innerCenterY;
        private final ShapeDrawable innerShapeDrawable = new ShapeDrawable(new OvalShape());


        public StickControlDrawable(InputControlsView parentView, ControlElementDescription description) {
            setColor(description.color);
            setAlpha(description.alpha);

            setScale(description.scale);
            setCenterPosition(description.centerXRelative * parentView.getWidth(), description.centerYRelative * parentView.getHeight());

            this.outerShapeDrawable.getPaint().setStyle(Paint.Style.STROKE);

            this.innerShapeDrawable.getPaint().setStyle(Paint.Style.FILL);
        }

        public void draw(@NonNull Canvas canvas) {
            // --- Outer outline ---
            Paint op = outerShapeDrawable.getPaint();
            int oc = op.getColor();
            int oa = op.getAlpha();
            float os = op.getStrokeWidth();
        
            op.setColor(android.graphics.Color.BLACK);
            op.setAlpha(120);
            op.setStrokeWidth(os + 5f * parentView.pixelScale);
            outerShapeDrawable.draw(canvas);
        
            op.setColor(oc);
            op.setAlpha(oa);
            op.setStrokeWidth(os);
            outerShapeDrawable.draw(canvas);
        
            // --- Inner: outline + fill ---
            // 1) inner outline (stroke around fill)
            Paint ip = innerShapeDrawable.getPaint();
            int ic = ip.getColor();
            int ia = ip.getAlpha();
            Paint.Style istyle = ip.getStyle();
            float iStroke = ip.getStrokeWidth();
        
            ip.setStyle(Paint.Style.STROKE);
            ip.setColor(android.graphics.Color.BLACK);
            ip.setAlpha(140);
            ip.setStrokeWidth(2f * parentView.pixelScale);
            innerShapeDrawable.draw(canvas);
        
            // 2) inner fill (restore)
            ip.setStyle(istyle); // should be FILL in your ctor
            ip.setColor(ic);
            ip.setAlpha(ia);
            ip.setStrokeWidth(iStroke);
            innerShapeDrawable.draw(canvas);
        }

        public void setColor(int color) {
            this.color = color;
            this.outerShapeDrawable.getPaint().setColor(this.color);
            this.innerShapeDrawable.getPaint().setColor(this.color);
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
            this.outerShapeDrawable.getPaint().setAlpha(this.alpha);
            this.innerShapeDrawable.getPaint().setAlpha(this.alpha);
        }

        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            this.outerShapeDrawable.getPaint().setColorFilter(colorFilter);
            this.innerShapeDrawable.getPaint().setColorFilter(colorFilter);
        }

        public boolean isPointOver(float x, float y) {
            return Math.pow(x - this.outerCenterX, 2) + Math.pow(y - this.outerCenterY, 2) <= Math.pow(this.outerRadius, 2);
        }

        public void setScale(float scale) {
            this.scale = scale;
            this.outerShapeDrawable.getPaint().setStrokeWidth(PAINT_STROKE_WIDTH * (float) Math.sqrt(this.scale));
            this.innerShapeDrawable.getPaint().setStrokeWidth(PAINT_STROKE_WIDTH * (float) Math.sqrt(this.scale));
            updateDimensions();
        }

        public void setCenterPosition(float x, float y) {
            this.outerCenterX = x;
            this.outerCenterY = y;
            this.innerCenterX = this.outerCenterX;
            this.innerCenterY = this.outerCenterY;
            updateBounds();
        }

        public void moveCenterPosition(float dx, float dy) {
            setCenterPosition(this.outerCenterX + dx, this.outerCenterY + dy);
        }

        private void updateDimensions() {
            this.outerRadius = OUTER_CIRCLE_RADIUS * parentView.pixelScale * this.scale;
            this.innerRadius = INNER_CIRCLE_RADIUS * parentView.pixelScale * this.scale;
            updateBounds();
        }

        private void updateBounds() {
            this.outerShapeDrawable.setBounds(Math.round(this.outerCenterX - this.outerRadius),
                    Math.round(this.outerCenterY - this.outerRadius),
                    Math.round(this.outerCenterX + this.outerRadius),
                    Math.round(this.outerCenterY + this.outerRadius));

            this.innerShapeDrawable.setBounds(Math.round(this.innerCenterX - this.innerRadius),
                    Math.round(this.innerCenterY - this.innerRadius),
                    Math.round(this.innerCenterX + this.innerRadius),
                    Math.round(this.innerCenterY + this.innerRadius));
        }

        public void setInnerPosition(float x, float y) {
            float dx = x - this.outerCenterX;
            float dy = y - this.outerCenterY;
            float distance = (float) Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));
            if (distance <= this.outerRadius) {
                this.innerCenterX = x;
                this.innerCenterY = y;
            } else {
                float scale = this.outerRadius / distance;
                this.innerCenterX = this.outerCenterX + dx * scale;
                this.innerCenterY = this.outerCenterY + dy * scale;
            }

            updateBounds();
        }

        public void resetInnerPosition() {
            this.innerCenterX = this.outerCenterX;
            this.innerCenterY = this.outerCenterY;

            updateBounds();
        }
    }
}
