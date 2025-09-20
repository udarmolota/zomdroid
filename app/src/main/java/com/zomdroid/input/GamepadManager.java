package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;


/**
 * Manages gamepad connection and input mapping for Android.
 */
public class GamepadManager implements InputManager.InputDeviceListener {
    // Android input manager
    private final InputManager inputManager;
    // Gamepad event listener
    private final GamepadListener listener;

    // Number of logical gamepad buttons (no D-Pad)
    public static final int GAMEPAD_BUTTON_COUNT = 15; // D-Pad included

    private static final int[] DEFAULT_MAPPING = {
        KeyEvent.KEYCODE_BUTTON_A,      // 0: A
        KeyEvent.KEYCODE_BUTTON_B,      // 1: B
        KeyEvent.KEYCODE_BUTTON_X,      // 2: X
        KeyEvent.KEYCODE_BUTTON_Y,      // 3: Y
        KeyEvent.KEYCODE_BUTTON_L1,     // 4: LB
        KeyEvent.KEYCODE_BUTTON_R1,     // 5: RB
        KeyEvent.KEYCODE_BUTTON_SELECT, // 6: SELECT
        KeyEvent.KEYCODE_BUTTON_START,  // 7: START
        KeyEvent.KEYCODE_BUTTON_MODE,   // 8: GUIDE
        KeyEvent.KEYCODE_BUTTON_THUMBL, // 9: LSTICK
        KeyEvent.KEYCODE_BUTTON_THUMBR, // 10: RSTICK
        KeyEvent.KEYCODE_DPAD_UP,       // 11
        KeyEvent.KEYCODE_DPAD_RIGHT,    // 12
        KeyEvent.KEYCODE_DPAD_DOWN,     // 13
        KeyEvent.KEYCODE_DPAD_LEFT      // 14
    };

    // Custom mapping
    private static int[] customMapping = null;

    // SharedPreferences keys
    private static final String PREFS_NAME = "gamepad_prefs";
    private static final String PREFS_KEY_MAPPING = "custom_gamepad_mapping";

    // Listener for gamepad events
    public interface GamepadListener {
        void onGamepadConnected();
        void onGamepadDisconnected();
        void onGamepadButton(int button, boolean pressed);
        void onGamepadAxis(int axis, float value);
        void onGamepadDpad(int dpad, char state);
    }

    // Create GamepadManager
    public GamepadManager(Context context, GamepadListener listener) {
        this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        this.listener = listener;
        loadCustomMapping(context);
    }

    // Set custom mapping, or use default if invalid
    public static void setCustomMapping(int[] mapping, Context context) {
        if (mapping != null && mapping.length == GAMEPAD_BUTTON_COUNT) {
            customMapping = java.util.Arrays.copyOf(mapping, mapping.length);
            saveCustomMapping(context);
        } else {
            customMapping = null;
            clearCustomMapping(context);
        }
    }

    // Get current mapping (custom or default)
    public static int[] getCurrentMapping() {
        return (customMapping != null) ? customMapping : DEFAULT_MAPPING;
    }

    // Load custom mapping from SharedPreferences
    public static void loadCustomMapping(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String csv = prefs.getString(PREFS_KEY_MAPPING, null);
        if (csv != null) {
            String[] parts = csv.split(",");
            if (parts.length == GAMEPAD_BUTTON_COUNT) {
                int[] loaded = new int[GAMEPAD_BUTTON_COUNT];
                try {
                    for (int i = 0; i < GAMEPAD_BUTTON_COUNT; i++) {
                        loaded[i] = Integer.parseInt(parts[i]);
                    }
                    customMapping = loaded;
                } catch (NumberFormatException e) {
                    customMapping = null;
                }
            }
        }
    }

    // Save custom mapping to SharedPreferences
    private static void saveCustomMapping(Context context) {
        if (customMapping == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < customMapping.length; i++) {
            sb.append(customMapping[i]);
            if (i < customMapping.length - 1) sb.append(",");
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY_MAPPING, sb.toString()).apply();
    }

    // Remove saved custom mapping from SharedPreferences
    private static void clearCustomMapping(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREFS_KEY_MAPPING).apply();
    }

    // Register for gamepad device events
    public void register() {
        inputManager.registerInputDeviceListener(this, null);
        // Check on start if a gamepad is already connected
        int[] deviceIds = inputManager.getInputDeviceIds();
        for (int id : deviceIds) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (dev != null && isGamepadDevice(dev)) {
                listener.onGamepadConnected();
                break;
            }
        }
    }

    // Unregister from gamepad device events
    public void unregister() {
        inputManager.unregisterInputDeviceListener(this);
    }

    // True if InputDevice is a gamepad or joystick
    private boolean isGamepadDevice(InputDevice device) {
        int sources = device.getSources();
        return ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice dev = inputManager.getInputDevice(deviceId);
        if (dev != null && isGamepadDevice(dev)) {
            listener.onGamepadConnected();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        int[] deviceIds = inputManager.getInputDeviceIds();
        boolean anyGamepad = false;
        for (int id : deviceIds) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (dev != null && isGamepadDevice(dev)) {
                anyGamepad = true;
                break;
            }
        }
        if (!anyGamepad) {
            listener.onGamepadDisconnected();
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // Not used
    }

    // True if KeyEvent is from a gamepad or joystick
    public boolean isGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD)     == InputDevice.SOURCE_DPAD);
    }

    // True if MotionEvent is from a gamepad or joystick
    public boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD)     == InputDevice.SOURCE_DPAD);
    }

    // Handle KeyEvent as gamepad button if possible
    public boolean handleKeyEvent(KeyEvent event) {
        if (!isGamepadEvent(event)) return false;
        int keyCode = event.getKeyCode();
        boolean isPressed = event.getAction() == KeyEvent.ACTION_DOWN;
        int button = mapKeyCodeToGLFWButton(keyCode);
        if (button >= 0) {
            listener.onGamepadButton(button, isPressed);
            return true;
        }
        return false;
    }

    private boolean dpadUpPressed, dpadRightPressed, dpadDownPressed, dpadLeftPressed;
    // Handle MotionEvent: axes and D-Pad
    public boolean handleMotionEvent(MotionEvent event) {
        if (!isGamepadMotionEvent(event)) return false;
        // Sticks
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        listener.onGamepadAxis(0, lx);
        listener.onGamepadAxis(1, ly);
        listener.onGamepadAxis(2, rx);
        listener.onGamepadAxis(3, ry);
        // Trigger
        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        listener.onGamepadAxis(4, lt);
        listener.onGamepadAxis(5, rt);
        // D-Pad - traditional handling (KeyEvents will handle custom mapping)
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        synthesizeDpadButtons(hatX, hatY); // D-pad
        char dpadState = 0;
        if (hatY < -0.5f) dpadState |= 0x01; // up
        if (hatY > 0.5f) dpadState |= 0x04; // down
        if (hatX < -0.5f) dpadState |= 0x08; // left
        if (hatX > 0.5f) dpadState |= 0x02; // right
        listener.onGamepadDpad(0, dpadState);
        
        return true;
    }

    private void synthesizeDpadButtons(float hatX, float hatY) {
        boolean up    = hatY < -0.5f;
        boolean down  = hatY >  0.5f;
        boolean left  = hatX < -0.5f;
        boolean right = hatX >  0.5f;
    
        if (up    != dpadUpPressed)    { dpadUpPressed    = up;    listener.onGamepadButton(11, up); }
        if (right != dpadRightPressed) { dpadRightPressed = right; listener.onGamepadButton(12, right); }
        if (down  != dpadDownPressed)  { dpadDownPressed  = down;  listener.onGamepadButton(13, down); }
        if (left  != dpadLeftPressed)  { dpadLeftPressed  = left;  listener.onGamepadButton(14, left); }
    }

    // Map Android keycode to logical button index (0-10), or -1 if not found
    private int mapKeyCodeToGLFWButton(int keyCode) {
        int[] mapping = getCurrentMapping();
        for (int i = 0; i < mapping.length; i++) {
            if (mapping[i] == keyCode) return i;
        }
        return -1;
    }
}
