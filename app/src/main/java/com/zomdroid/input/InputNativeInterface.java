package com.zomdroid.input;

public class InputNativeInterface {
    public static native void sendKeyboard(int key, boolean isPressed);

    public static native void sendCursorPos(double x, double y);

    public static native void sendMouseButton(int button, boolean isPressed);

    public static native void sendJoystickAxis(int axis, float state);

    //public static native void sendJoystickDpad(int dpad, char state);

    public static void debugSendJoystickButton(int button, boolean isPressed) {
        System.out.println("InputNativeInterface: sendJoystickButton(" + button + ", " + isPressed + ")");
        sendJoystickButton(button, isPressed);
    }


    public static native void sendJoystickConnected();
}
