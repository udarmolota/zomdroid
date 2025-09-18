package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Minimal D-Pad improvement:
 * - Convert HAT axes (AXIS_HAT_X/Y) into D-Pad button presses with hysteresis
 * - Extend logical buttons by 4 (UP/RIGHT/DOWN/LEFT)
 * - Keep everything else unchanged
 */
public class GamepadManager implements InputManager.InputDeviceListener {
    // Android input manager
    private final InputManager inputManager;
    // Gamepad event listener
    private final GamepadListener listener;

    // Number of logical gamepad buttons (now includes D-Pad)
    public static final int GAMEPAD_BUTTON_COUNT = 15; // was 11

    // Default mapping: [A, B, X, Y, LB, RB, SELECT, START, GUIDE, LSTICK, RSTICK, DPAD_UP, DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT]
    private static final int[] DEFAULT_MAPPING = {
        KeyEvent.KEYCODE_BUTTON_A,      // 0: A
        KeyEvent.KEYCODE_BUTTON_B,      // 1: B
        KeyEvent.KEYCODE_BUTTON_X,      // 2: X
        KeyEvent.KEYCODE_BUTTON_Y,      // 3: Y
        KeyEvent.KEYCODE_BUTTON_L1,     // 4: LB
        KeyEvent.KEYCODE_BUTTON_R1,     // 5: RB
        KeyEvent.KEYCODE_BUTTON_SELECT, // 6: SELECT/BACK
        KeyEvent.KEYCODE_BUTTON_START,  // 7: START
        KeyEvent.KEYCODE_BUTTON_MODE,   // 8: GUIDE
        KeyEvent.KEYCODE_BUTTON_THUMBL, // 9: LSTICK
        KeyEvent.KEYCODE_BUTTON_THUMBR, // 10: RSTICK
        KeyEvent.KEYCODE_DPAD_UP,       // 11: DPAD UP (if controller emits key events)
        KeyEvent.KEYCODE_DPAD_RIGHT,    // 12: DPAD RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN,     // 13: DPAD DOWN
        KeyEvent.KEYCODE_DPAD_LEFT      // 14: DPAD LEFT
    };

    // Optional custom mapping (persisted)
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

    // HAT → buttons state with hysteresis
    private boolean dpadUp = false, dpadRight = false, dpadDown = false, dpadLeft = false;
    private static final float HAT_ON  = 0.5f;  // press threshold
    private static final float HAT_OFF = 0.3f;  // release threshold (hysteresis)

    // Create GamepadManager
    public GamepadManager(Context context, GamepadListener listener) {
        this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        this.listener = listener;
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
            } else {
                // If old mapping (length 11) is stored, ignore it to keep things minimal.
                customMapping = null;
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
        // Not used (kept minimal)
    }

    // True if KeyEvent is from a gamepad or joystick
    public boolean isGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
            || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    // True if MotionEvent is from a gamepad or joystick
    public boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
            || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
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

    // Handle MotionEvent: sticks and D-Pad
    public boolean handleMotionEvent(MotionEvent event) {
        if (!isGamepadMotionEvent(event)) return false;

        // Sticks (unchanged)
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        listener.onGamepadAxis(0, lx);
        listener.onGamepadAxis(1, ly);
        listener.onGamepadAxis(2, rx);
        listener.onGamepadAxis(3, ry);

        // Triggers (unchanged pass-through as axes)
        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        listener.onGamepadAxis(4, lt);
        listener.onGamepadAxis(5, rt);

        // D-Pad via HAT → synthesize button presses (new)
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        emitHatAsButtons(hatX, hatY);

        // Keep legacy D-Pad state callback (unchanged behavior for existing consumers)
        char dpadState = 0;
        if (hatY < -0.5f) dpadState |= 0x01; // up
        if (hatY >  0.5f) dpadState |= 0x04; // down
        if (hatX < -0.5f) dpadState |= 0x08; // left
        if (hatX >  0.5f) dpadState |= 0x02; // right
        listener.onGamepadDpad(0, dpadState);

        return true;
    }

    // Map Android keycode to logical button index (0..14), or -1 if not found
    private int mapKeyCodeToGLFWButton(int keyCode) {
        int[] mapping = getCurrentMapping();
        for (int i = 0; i < mapping.length; i++) {
            if (mapping[i] == keyCode) return i;
        }
        return -1;
    }

    // HAT (AXIS_HAT_X/Y) → DPAD buttons with hysteresis
    private void emitHatAsButtons(float hatX, float hatY) {
        boolean upNow    = hatY < -HAT_ON;
        boolean downNow  = hatY >  HAT_ON;
        boolean leftNow  = hatX < -HAT_ON;
        boolean rightNow = hatX >  HAT_ON;

        // Hysteresis for releases
        if (!upNow   && dpadUp)    upNow    = (hatY < -HAT_OFF);
        if (!downNow && dpadDown)  downNow  = (hatY >  HAT_OFF);
        if (!leftNow && dpadLeft)  leftNow  = (hatX < -HAT_OFF);
        if (!rightNow&& dpadRight) rightNow = (hatX >  HAT_OFF);

        if (upNow    != dpadUp)    { dpadUp    = upNow;    listener.onGamepadButton(11, upNow); }
        if (rightNow != dpadRight) { dpadRight = rightNow; listener.onGamepadButton(12, rightNow); }
        if (downNow  != dpadDown)  { dpadDown  = downNow;  listener.onGamepadButton(13, downNow); }
        if (leftNow  != dpadLeft)  { dpadLeft  = leftNow;  listener.onGamepadButton(14, leftNow); }
    }
}
