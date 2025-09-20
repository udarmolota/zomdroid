package com.zomdroid.input;

public final class InputNativeInterface {

    private InputNativeInterface() { /* no instances */ }

    static {
        // Load the native library that implements the JNI methods below.
        // Replace "zomdroid" if your .so has a different name.
        System.loadLibrary("zomdroid");
    }

    // DPAD hat bit masks (must match native / GLFW expectations)
    public static final char DPAD_UP    = 0x01;
    public static final char DPAD_RIGHT = 0x02;
    public static final char DPAD_DOWN  = 0x04;
    public static final char DPAD_LEFT  = 0x08;

    // Default HAT index (most controllers expose a single HAT)
    public static final int DEFAULT_HAT_INDEX = 0;

    // ==== Native methods (signatures unchanged) ====
    public static native void sendKeyboard(int key, boolean isPressed);
    public static native void sendCursorPos(double x, double y);
    public static native void sendMouseButton(int button, boolean isPressed);
    public static native void sendJoystickAxis(int axis, float state);
    public static native void sendJoystickDpad(int dpad, char state);
    public static native void sendJoystickButton(int button, boolean isPressed);
    public static native void sendJoystickConnected();

    // Optional helper to build a DPAD state bitmask from booleans.
    public static char buildDpadState(boolean up, boolean right, boolean down, boolean left) {
        char state = 0;
        if (up)    state |= DPAD_UP;
        if (right) state |= DPAD_RIGHT;
        if (down)  state |= DPAD_DOWN;
        if (left)  state |= DPAD_LEFT;
        return state;
    }
}
