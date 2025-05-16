package com.zomdroid.input;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.zomdroid.R;

public class ControlElementDescription { public enum Icon {
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
            case STICK:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.LEFT_JOYSTICK}, null, Color.LTGRAY, 255,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON);
            default:
                throw new IllegalArgumentException("Unrecognized type " + type.name());
        }
    }

    private void validate() {
        if (this.scale < AbstractControlElement.MIN_SCALE || this.scale > AbstractControlElement.MAX_SCALE)
            throw new IllegalStateException("Scale is supposed to be in range [" +
                    AbstractControlElement.MIN_SCALE + ", " + AbstractControlElement.MAX_SCALE + "]");
        if (this.centerXRelative <= 0.f || this.centerXRelative >= 1.f
                || this.centerYRelative <= 0.f || this.centerYRelative >= 1.f)
            throw new IllegalStateException("Relative center co-ords are supposed to be in range (0, 1)," +
                    " got centerX=" + this.centerXRelative + " centerY=" + this.centerYRelative);
        if (this.alpha < 0 || this.alpha > 255)
            throw new IllegalStateException("Alpha is supposed to be in range [0, 255], got " + this.alpha);
        for (GLFWBinding binding: this.bindings) {
            if (binding == null)
                throw new IllegalStateException("Null binding is not allowed");
        }
        if (this.inputType == AbstractControlElement.InputType.MNK && (this.type == AbstractControlElement.Type.DPAD ||
                this.type == AbstractControlElement.Type.STICK) && this.bindings.length != 4)
            throw new IllegalStateException("Controls of type DPAD and STICK with MNK input are supposed to have" +
                    " exactly 4 bindings, got " + this.bindings.length);

        if (this.inputType == AbstractControlElement.InputType.GAMEPAD && this.type == AbstractControlElement.Type.STICK) {
            if (this.bindings.length != 1) {
                throw new IllegalStateException("Controls of type STICK with GAMEPAD input are supposed to have" +
                        " exactly 1 binding, got " + this.bindings.length);
            }
            if (this.bindings[0] != GLFWBinding.LEFT_JOYSTICK && this.bindings[0] != GLFWBinding.RIGHT_JOYSTICK) {
                throw new IllegalStateException("Controls of type STICK with GAMEPAD input are supposed to have" +
                        " either " + GLFWBinding.LEFT_JOYSTICK + " or " + GLFWBinding.RIGHT_JOYSTICK
                        + " binding, got " + this.bindings[0]);
            }
        }
    }


}
