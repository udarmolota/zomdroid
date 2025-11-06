package com.zomdroid.input;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

public final class InputDispatch {
    private static WeakReference<View> targetRef = new WeakReference<>(null);

    public static void setTarget(View v) {
        targetRef = new WeakReference<>(v);
    }

    public static void dispatchKey(int androidKeyCode, boolean isPressed) {
        final View v = targetRef.get();
        if (v == null) return;

        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(
                now, now,
                isPressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                androidKeyCode,
                0,                       // repeat
                0,                       // meta
                KeyCharacterMap.VIRTUAL_KEYBOARD, // deviceId surrogate
                0,                       // scancode
                0,                       // flags
                InputDevice.SOURCE_KEYBOARD
        );
        // Безопасно отдать в UI-поток:
        v.post(() -> v.dispatchKeyEvent(ev));
    }
}
