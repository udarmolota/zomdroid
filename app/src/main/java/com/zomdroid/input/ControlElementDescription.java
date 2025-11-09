package com.zomdroid.input;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.zomdroid.R;

public class ControlElementDescription {
    public enum Icon {
        NO_ICON(R.drawable.ic_void),
        GAMEPAD_BACK_ICON(R.drawable.mt_icon_stack),
        GAMEPAD_START_ICON(R.drawable.mt_icon_menu);
        public final int resId;

        Icon(int resId) {
            this.resId = resId;
        }
    }

    public final float centerXRelative;
    public final float centerYRelative;
    public final float scale;
    public final AbstractControlElement.Type type;
    public final GLFWBinding[] bindings;
    public final String text;
    public final int color;
    public final int alpha;
    public final AbstractControlElement.InputType inputType;
    public final Icon icon;

    public ControlElementDescription(float centerXRelative, float centerYRelative, float scale,
                                     @NonNull AbstractControlElement.Type type, @NonNull GLFWBinding[] bindings,
                                     String text, int color, int alpha,
                                     AbstractControlElement.InputType inputType, @NonNull Icon icon) {
        this.centerXRelative = centerXRelative;
        this.centerYRelative = centerYRelative;
        this.scale = scale;
        this.type = type;
        this.bindings = bindings;
        this.text = text;
        this.color = color;
        this.alpha = alpha;
        this.inputType = inputType;
        this.icon = icon;
        validate();
    }

    public static ControlElementDescription getDefaultForType(AbstractControlElement.Type type) {
        switch (type) {
            case BUTTON_CIRCLE:
            case BUTTON_RECT:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.GAMEPAD_BUTTON_A}, "A", Color.LTGRAY, 255,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON);
            case DPAD:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON);
            case DPAD_UP:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.KEY_UP}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.MNK, Icon.NO_ICON);
    
            case DPAD_RIGHT:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.KEY_RIGHT}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.MNK, Icon.NO_ICON);
    
            case DPAD_DOWN:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.KEY_DOWN}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.MNK, Icon.NO_ICON);
    
            case DPAD_LEFT:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.KEY_LEFT}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.MNK, Icon.NO_ICON);
            case STICK:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.LEFT_JOYSTICK}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON);
            default:
                throw new IllegalArgumentException("Unrecognized type " + type.name());
        }
    }

    private void validate() {
        // scale
        if (this.scale < AbstractControlElement.MIN_SCALE || this.scale > AbstractControlElement.MAX_SCALE) {
            throw new IllegalStateException("Scale must be in [" +
                    AbstractControlElement.MIN_SCALE + ", " + AbstractControlElement.MAX_SCALE + "]");
        }
    
        // center (exclusive 0..1)
        if (this.centerXRelative <= 0.f || this.centerXRelative >= 1.f
                || this.centerYRelative <= 0.f || this.centerYRelative >= 1.f) {
            throw new IllegalStateException("Relative center must be in (0,1), got x=" +
                    this.centerXRelative + " y=" + this.centerYRelative);
        }
    
        // alpha
        if (this.alpha < 0 || this.alpha > 255) {
            throw new IllegalStateException("Alpha must be in [0,255], got " + this.alpha);
        }
    
        // bindings not null
        for (GLFWBinding b : this.bindings) {
            if (b == null) throw new IllegalStateException("Null binding is not allowed");
        }
    
        final boolean isMNK = (this.inputType == AbstractControlElement.InputType.MNK);
        final boolean isGAMEPAD = (this.inputType == AbstractControlElement.InputType.GAMEPAD);
    
        // --- MNK rules ---
        if (isMNK) {
            // Композитный DPAD и STICK в MNK используют 4 биндинга (WASD/стрелки)
            if (this.type == AbstractControlElement.Type.DPAD
                    || this.type == AbstractControlElement.Type.STICK) {
                if (this.bindings.length != 4) {
                    throw new IllegalStateException("DPAD/STICK with MNK input must have exactly 4 bindings, got " +
                            this.bindings.length);
                }
            }
    
            // Split-DPAD (UP/RIGHT/DOWN/LEFT) в MNK — ровно 1 биндинг на элемент
            if (this.type == AbstractControlElement.Type.DPAD_UP
                    || this.type == AbstractControlElement.Type.DPAD_RIGHT
                    || this.type == AbstractControlElement.Type.DPAD_DOWN
                    || this.type == AbstractControlElement.Type.DPAD_LEFT) {
                if (this.bindings.length != 1) {
                    throw new IllegalStateException("Split-DPAD with MNK input must have exactly 1 binding, got " +
                            this.bindings.length);
                }
            }
        }
    
        // --- GAMEPAD rules ---
        if (isGAMEPAD) {
            // STICK в геймпаде — ровно 1 биндинг: LEFT_JOYSTICK или RIGHT_JOYSTICK
            if (this.type == AbstractControlElement.Type.STICK) {
                if (this.bindings.length != 1) {
                    throw new IllegalStateException("STICK with GAMEPAD input must have exactly 1 binding, got " +
                            this.bindings.length);
                }
                if (this.bindings[0] != GLFWBinding.LEFT_JOYSTICK
                        && this.bindings[0] != GLFWBinding.RIGHT_JOYSTICK) {
                    throw new IllegalStateException("STICK with GAMEPAD input must bind to LEFT_JOYSTICK or RIGHT_JOYSTICK, got " +
                            this.bindings[0]);
                }
            }
    
            // Для DPAD (композитного) и split-DPAD в GAMEPAD биндинги не требуются:
            // используется sendJoystickDpad с маской направлений.
            // Ничего не проверяем.
        }
    }
}
