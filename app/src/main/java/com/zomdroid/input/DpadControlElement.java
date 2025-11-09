package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.util.Arrays;

public class DpadControlElement extends AbstractControlElement {
    private final DpadControlDrawable drawable;
    private int pointerId = -1;
    private static final float DPAD_DEAD_ZONE = 0.3f;
    private enum Mode { COMPOSITE, SPLIT }
    // New fields for separation
    private Mode mode = Mode.COMPOSITE;
    private int splitBit = 0; // 0x1 UP, 0x2 RIGHT, 0x4 DOWN, 0x8 LEFT
    private static int pressedMask = 0;

    public DpadControlElement(InputControlsView parentView, ControlElementDescription elementDescription) {
        super(parentView, elementDescription);
        this.drawable = new DpadControlDrawable(parentView, elementDescription);
        this.bindings.addAll(Arrays.asList(elementDescription.bindings));
        
        if (this.description.type == Type.DPAD) {
            mode = Mode.COMPOSITE;
        } else if (this.description.type == Type.DPAD_UP) {
            mode = Mode.SPLIT; splitBit = 0x1;
        } else if (this.description.type == Type.DPAD_RIGHT) {
            mode = Mode.SPLIT; splitBit = 0x2;
        } else if (this.description.type == Type.DPAD_DOWN) {
            mode = Mode.SPLIT; splitBit = 0x4;
        } else if (this.description.type == Type.DPAD_LEFT) {
            mode = Mode.SPLIT; splitBit = 0x8;
        }
    }

    @Override
    public void setInputType(InputType inputType) {
        clearBindings();
        this.inputType = inputType;

        if (this.inputType == InputType.MNK) {
            this.bindings.add(GLFWBinding.KEY_A);
            this.bindings.add(GLFWBinding.KEY_W);
            this.bindings.add(GLFWBinding.KEY_D);
            this.bindings.add(GLFWBinding.KEY_S);
        }
    }

    private void dispatchEvent(float x, float y, boolean isPress) {
        int state = 0;
        if (isPress) {
            float dx = x - this.drawable.centerX;
            float dy = y - this.drawable.centerY;
            float r = this.drawable.size / 2;
            float nx = clamp(dx / r, -1.f, 1.f);
            float ny = clamp(dy / r, -1.f, 1.f);
            if (ny < -DPAD_DEAD_ZONE) state |= 0x1;
            if (nx > DPAD_DEAD_ZONE) state |= 0x2;
            if (ny > DPAD_DEAD_ZONE) state |= 0x4;
            if (nx < -DPAD_DEAD_ZONE) state |= 0x8;
        }

        if (this.inputType == InputType.GAMEPAD)
            InputNativeInterface.sendJoystickDpad(0, (char) state);
        else if (this.inputType == InputType.MNK) {
            handleMNKBinding(getBindingUp(), (state & 0x1) != 0);
            handleMNKBinding(getBindingRight(), (state & 0x2) != 0);
            handleMNKBinding(getBindingDown(), (state & 0x4) != 0);
            handleMNKBinding(getBindingLeft(), (state & 0x8) != 0);
        }
    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        int pid = e.getPointerId(actionIndex);
    
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(actionIndex);
                float y = e.getY(actionIndex);
                if (!this.drawable.isPointOver(x, y)) return false;
    
                this.parentView.requestDisallowInterceptTouchEvent(true);
                this.pointerId = pid;
    
                if (mode == Mode.SPLIT) {
                    setSplitPressed(true);
                } else { // COMPOSITE
                    this.dispatchEvent(x, y, true);
                }
                return true;
            }
    
            case MotionEvent.ACTION_MOVE: {
                if (this.pointerId < 0) return false;
                int idx = e.findPointerIndex(this.pointerId);
                if (idx < 0) { this.pointerId = -1; return false; }
    
                if (mode == Mode.SPLIT) {
                    return true;
                } else {
                    float x = e.getX(idx);
                    float y = e.getY(idx);
                    this.dispatchEvent(x, y, true);
                    return true;
                }
            }
    
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (pid != this.pointerId) return false;
                this.pointerId = -1;
    
                if (mode == Mode.SPLIT) {
                    setSplitPressed(false);
                } else {
                    this.dispatchEvent(0, 0, false);
                }
                this.parentView.requestDisallowInterceptTouchEvent(false);
                return true;
            }
    
            case MotionEvent.ACTION_CANCEL: {
                if (this.pointerId != -1) {
                    this.pointerId = -1;
                    if (mode == Mode.SPLIT) setSplitPressed(false);
                    else this.dispatchEvent(0, 0, false);
                    this.parentView.requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                return false;
            }
        }
    
        return false;
    }

    @Override
    public float getCenterX() {
        return this.drawable.centerX;
    }

    @Override
    public void draw(Canvas canvas) {
        this.drawable.draw(canvas);
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
        scale = clamp(scale, MIN_SCALE, MAX_SCALE);
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
                this.drawable.centerX / this.parentView.getWidth(),
                this.drawable.centerY / this.parentView.getHeight(),
                this.drawable.scale, 
                //Type.DPAD,
                this.description.type,
                this.bindings.toArray(new GLFWBinding[0]), null, this.drawable.color,
                this.drawable.alpha,
                this.inputType, ControlElementDescription.Icon.NO_ICON);
    }

    public class DpadControlDrawable {
        private static final int PAINT_STROKE_WIDTH = 6;
        private static final float DPAD_SIZE = 340.f;

        private int color;
        private int alpha;
        private float scale;
        private float size;
        private float centerX;
        private float centerY;
        private float x;
        private float y;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        public DpadControlDrawable(InputControlsView parentView, ControlElementDescription description) {
            setColor(description.color);
            setAlpha(description.alpha);

            setScale(description.scale);
            setCenterPosition(description.centerXRelative * parentView.getWidth(),
                    description.centerYRelative * parentView.getHeight());

            this.paint.setStyle(Paint.Style.STROKE);
        }

        private void calculatePath() {
            float halfWidth = this.size / 6;
            float halfHeight = this.size / 4;
            float offset = this.size / 12;
            this.path.reset();

            this.path.moveTo(this.centerX, this.centerY - offset);
            this.path.lineTo(this.centerX - halfWidth, this.centerY - halfHeight);
            this.path.lineTo(this.centerX - halfWidth, this.y);
            this.path.lineTo(this.centerX + halfWidth, this.y);
            this.path.lineTo(this.centerX + halfWidth, this.centerY - halfHeight);
            this.path.close();

            this.path.moveTo(this.centerX - offset, this.centerY);
            this.path.lineTo(this.centerX - halfHeight, this.centerY - halfWidth);
            this.path.lineTo(this.x, this.centerY - halfWidth);
            this.path.lineTo(this.x, this.centerY + halfWidth);
            this.path.lineTo(this.centerX - halfHeight, this.centerY + halfWidth);
            this.path.close();

            this.path.moveTo(this.centerX, this.centerY + offset);
            this.path.lineTo(this.centerX - halfWidth, this.centerY + halfHeight);
            this.path.lineTo(this.centerX - halfWidth, this.y + this.size);
            this.path.lineTo(this.centerX + halfWidth, this.y + this.size);
            this.path.lineTo(this.centerX + halfWidth, this.centerY + halfHeight);
            this.path.close();

            this.path.moveTo(this.centerX + offset, this.centerY);
            this.path.lineTo(this.centerX + halfHeight, this.centerY - halfWidth);
            this.path.lineTo(this.x + this.size, this.centerY - halfWidth);
            this.path.lineTo(this.x + this.size, this.centerY + halfWidth);
            this.path.lineTo(this.centerX + halfHeight, this.centerY + halfWidth);
            this.path.close();
        }

        public void draw(@NonNull Canvas canvas) {
            canvas.drawPath(this.path, this.paint);
        }

        public void setColor(int color) {
            this.color = color;
            this.paint.setColor(this.color);
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
            this.paint.setAlpha(this.alpha);
        }

        public void setScale(float scale) {
            this.scale = scale;
            this.paint.setStrokeWidth(PAINT_STROKE_WIDTH * (float) Math.sqrt(this.scale));
            this.paint.setPathEffect(new CornerPathEffect(15f * this.scale));
            updateDimensions();
        }

        private void updateDimensions() {
            this.size = DPAD_SIZE * parentView.pixelScale * this.scale;
            updateBounds();
        }

        private void updateBounds() {
            this.x = this.centerX - this.size / 2;
            this.y = this.centerY - this.size / 2;
            calculatePath();
        }

        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            this.paint.setColorFilter(colorFilter);
        }

        public boolean isPointOver(float x, float y) {
            return x >= this.x && x <= this.x + this.size && y >= this.y && y <= this.y + this.size;
        }

        public void setCenterPosition(float x, float y) {
            this.centerX = x;
            this.centerY = y;
            updateBounds();
        }

        public void moveCenterPosition(float dx, float dy) {
            setCenterPosition(this.centerX + dx, this.centerY + dy);
        }
    }

    private void setSplitPressed(boolean pressed) {
        if (pressed) pressedMask |= splitBit; else pressedMask &= ~splitBit;
    
        if (this.inputType == InputType.GAMEPAD) {
            InputNativeInterface.sendJoystickDpad(0, (char) pressedMask);
        } else { // MNK
            if (splitBit == 0x1) handleMNKBinding(getBindingUp(), pressed);
            if (splitBit == 0x2) handleMNKBinding(getBindingRight(), pressed);
            if (splitBit == 0x4) handleMNKBinding(getBindingDown(), pressed);
            if (splitBit == 0x8) handleMNKBinding(getBindingLeft(), pressed);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
