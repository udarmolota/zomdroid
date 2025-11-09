package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;
import android.content.Context;
import android.view.KeyEvent;

import java.util.ArrayList;

import com.zomdroid.input.KeyCodes;
import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.InputDispatch;

public abstract class AbstractControlElement {
    private static final String LOG_TAG = AbstractControlElement.class.getName();
    protected static final float MIN_SCALE = 0.5f;
    protected static final float MAX_SCALE = 2.0f;
    protected final ArrayList<GLFWBinding> bindings = new ArrayList<>();
    protected final InputControlsView parentView;
    protected static final ColorFilter HIGHLIGHT_COLOR_FILTER = new PorterDuffColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP);
    protected final Type type;
    protected InputType inputType;
    protected Context context;

    AbstractControlElement(InputControlsView parentView, ControlElementDescription description) {
        this.parentView = parentView;
        this.type = description.type;
        this.inputType = description.inputType;
        this.context = parentView.getContext();
    }

    public static AbstractControlElement fromDescription(InputControlsView parentView, ControlElementDescription description) {
        switch (description.type) {
            case BUTTON_CIRCLE:
            case BUTTON_RECT:
                return new ButtonControlElement(parentView, description);
            case DPAD:
                return new DpadControlElement(parentView, description);
            case STICK:
                return new StickControlElement(parentView, description);
            default:
                throw new IllegalArgumentException("Unrecognized type " + description.type);
        }
    }

    public Type getType() {
        return this.type;
    }

    public InputType getInputType() {
        return this.inputType;
    }

    public abstract void setInputType(InputType inputType);

    public float getCenterX() {
        throw new UnsupportedOperationException();
    }

    public abstract boolean handleMotionEvent(MotionEvent e);

    public abstract void draw(Canvas canvas);

    public abstract boolean isPointOver(float x, float y);

    public abstract void setHighlighted(boolean highlighted);

    public abstract void setAlpha(int alpha);

    public abstract int getAlpha();

    public abstract void setScale(float value);

    public abstract float getScale();

    public void setText(String text) {
        throw new UnsupportedOperationException();
    }

    public String getText() {
        throw new UnsupportedOperationException();
    }

    public void setIcon(ControlElementDescription.Icon icon) {
        throw new UnsupportedOperationException();
    }

    public ControlElementDescription.Icon getIcon() {
        throw new UnsupportedOperationException();
    }

    public void setCenterPosition(float x, float y) {
        throw new UnsupportedOperationException();
    }

    public void moveCenterPosition(float dx, float dy) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding[] getBindings() {
        return this.bindings.toArray(new GLFWBinding[0]);
    }

    public void clearBindings() {
        this.bindings.clear();
    }

    public void addBinding(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public void setBinding(int index, GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public void removeBinding(int index) {
        throw new UnsupportedOperationException();
    }

    public void setBindingLeft(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingLeft() {
        throw new UnsupportedOperationException();
    }

    public void setBindingUp(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingUp() {
        throw new UnsupportedOperationException();
    }

    public void setBindingRight(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingRight() {
        throw new UnsupportedOperationException();
    }

    public void setBindingDown(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingDown() {
        throw new UnsupportedOperationException();
    }

    public void setBindingStick(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingStick() {
        throw new UnsupportedOperationException();
    }

    public abstract ControlElementDescription describe();

    void handleGamepadBinding(GLFWBinding binding, boolean isPressed) {
        Log.v(LOG_TAG, "handleGamepadBinding binding=" + binding + " isPressed=" + isPressed);
        if (binding.ordinal() >= GLFWBinding.GAMEPAD_MIN_ORDINAL
                && binding.ordinal() <= GLFWBinding.GAMEPAD_MAX_ORDINAL) {
            switch (binding) {
                case GAMEPAD_LTRIGGER:
                    InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_LT.code, isPressed ? 1 : 0);
                    break;
                case GAMEPAD_RTRIGGER:
                    InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RT.code, isPressed ? 1 : 0);
                    break;
                default:
                    InputNativeInterface.sendJoystickButton(binding.code, isPressed);
                    break;
            }
        }
    }

    public static void handleMNKBinding(GLFWBinding binding, boolean isPressed) {
        // Mouse
        if (binding.name().startsWith("MOUSE_BUTTON_")) {
            InputNativeInterface.sendMouseButton(binding.code, isPressed);
            return;
        }
    
        // Buttons
        Integer androidCode = KeyCodes.toAndroid(binding);
        if (androidCode != null && androidCode != KeyEvent.KEYCODE_UNKNOWN) {
            InputNativeInterface.sendKeyboard(binding.code, isPressed);
            return;
        }
    
        // Fallback
        InputNativeInterface.sendKeyboard(binding.code, isPressed);
    }


    public enum Type {
        STICK,
        DPAD,
        BUTTON_RECT,
        BUTTON_CIRCLE
    }

    public enum InputType {
        MNK,
        GAMEPAD
    }
    private boolean visible = true;

    public void setVisible(boolean value) {
      this.visible = value;
    }

    public boolean isVisible() {
      return visible;
    }

    private boolean touchable = true;

    public void setTouchable(boolean touchable) {
      this.touchable = touchable;
    }

    public boolean isTouchable() {
      return touchable;
    }

}
