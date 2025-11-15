package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.util.Log;
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

    // Onscreen controls always shown
    private static boolean touchOverride = false;

    // Number of logical gamepad buttons (no D-Pad)
    public static final int GAMEPAD_BUTTON_COUNT = 11; // 11, D-Pad not included

    // --- NEW: storage indices for triggers in the mapping array ---
    // These positions are used by the Mapper UI to store LT/RT bindings.
    private static final int STORE_IDX_LT = 15; // must match Fragment
    private static final int STORE_IDX_RT = 16; // must match Fragment

    // --- NEW: sentinel encoding to distinguish axis-vs-button trigger bindings ---
    // Encoding format: (TYPE << 24) | VALUE
    private static final int TYPE_AXIS   = 0x01; // VALUE = target axis index (we target a4/a5 => 4/5)
    private static final int TYPE_BUTTON = 0x02; // VALUE = Android keyCode (e.g. KEYCODE_BUTTON_L2 / R2)
    private static final int TYPE_MASK   = 0xFF000000;
    private static final int VALUE_MASK  = 0x00FFFFFF;

    // --- Target axis indices in our virtual controller / GLFW mapping ---
    private static final int AXIS_LT_INDEX = 4; // a4
    private static final int AXIS_RT_INDEX = 5; // a5

    // Default mapping: [A, B, X, Y, LB, RB, SELECT, START, GUIDE, LSTICK, RSTICK]
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
        KeyEvent.KEYCODE_BUTTON_THUMBR  // 10: RSTICK
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
    }

    // --- CHANGED: accept variable-length mapping (>= GAMEPAD_BUTTON_COUNT) ---
    // We keep the entire array so that indices 15/16 can store LT/RT sentinel values.
    public static void setCustomMapping(int[] mapping, Context context) {
        if (mapping != null && mapping.length >= GAMEPAD_BUTTON_COUNT) {
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

    public static void setTouchOverride(boolean override) {
        touchOverride = override;
    }

    public static boolean isTouchOverrideEnabled() {
        return touchOverride;
    }

    // Load custom mapping from SharedPreferences
    // --- CHANGED: load mapping of any length (>= GAMEPAD_BUTTON_COUNT) ---
    public static void loadCustomMapping(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String csv = prefs.getString(PREFS_KEY_MAPPING, null);
        if (csv != null && !csv.isEmpty()) {
            String[] parts = csv.split(",");
            if (parts.length >= GAMEPAD_BUTTON_COUNT) {
                int[] loaded = new int[parts.length];
                try {
                    for (int i = 0; i < parts.length; i++) {
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
    public static boolean isGamepadDevice(InputDevice device) {
        //Log.d("[keyboadrd]", "isGamepadDevice: " + device);
        if (isTouchOverrideEnabled()) {
            return false;
        }
        if (device == null) return false;

        // Check if the device reports itself as a gamepad or joystick
        int sources = device.getSources();
        boolean isGamepadSource = ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);

        // Check if the device has motion ranges (e.g., analog sticks or triggers)
        boolean hasMotion = device.getMotionRanges() != null && !device.getMotionRanges().isEmpty();

        // Final decision: must be gamepad-like, have motion and keys, be physical, and not ghost
        return isGamepadSource && hasMotion;
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
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    // True if MotionEvent is from a gamepad or joystick
    public boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    // Handle KeyEvent as gamepad button or trigger-as-button (synthesize axis)
    public boolean handleKeyEvent(KeyEvent event) {
        if (!isGamepadEvent(event)) return false;

        int keyCode = event.getKeyCode();
        boolean isPressed = event.getAction() == KeyEvent.ACTION_DOWN;

        // --- NEW: trigger-as-button handling based on sentinel mapping ---
        if (isTriggerButton(keyCode, true)) {
            // LT is configured as a button: synthesize axis a4 (1.0 on down, 0.0 on up)
            listener.onGamepadAxis(AXIS_LT_INDEX, isPressed ? 1.0f : 0.0f);
            return true; // consume
        }
        if (isTriggerButton(keyCode, false)) {
            // RT is configured as a button: synthesize axis a5
            listener.onGamepadAxis(AXIS_RT_INDEX, isPressed ? 1.0f : 0.0f);
            return true; // consume
        }

        // Regular buttons mapping (A,B,X,Y,LB,RB,SELECT,START,GUIDE,L3,R3)
        int button = mapKeyCodeToGLFWButton(keyCode);
        if (button >= 0) {
            listener.onGamepadButton(button, isPressed);
            return true;
        }
        return false;
    }

    // Handle MotionEvent: sticks, triggers (axis mode), and D-Pad (hat)
    public boolean handleMotionEvent(MotionEvent event) {
        if (!isGamepadMotionEvent(event)) return false;

        // Sticks (keep legacy mapping: X/Y = left, Z/RZ = right)
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        listener.onGamepadAxis(0, lx);
        listener.onGamepadAxis(1, ly);
        listener.onGamepadAxis(2, rx);
        listener.onGamepadAxis(3, ry);

        // --- NEW: triggers in axis mode only ---
        // If a trigger is configured as AXIS, read analog value (with fallbacks) and send to a4/a5.
        if (isTriggerAxisMode(true)) {
            float lt = readTriggerAxis(event, true);
            listener.onGamepadAxis(AXIS_LT_INDEX, clamp01(lt));
        }
        if (isTriggerAxisMode(false)) {
            float rt = readTriggerAxis(event, false);
            listener.onGamepadAxis(AXIS_RT_INDEX, clamp01(rt));
        }

        // D-Pad (hat) — unchanged
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        char dpadState = 0;
        if (hatY < -0.5f) dpadState |= 0x01; // up
        if (hatY > 0.5f) dpadState |= 0x04; // down
        if (hatX < -0.5f) dpadState |= 0x08; // left
        if (hatX > 0.5f) dpadState |= 0x02; // right
        listener.onGamepadDpad(0, dpadState);

        return true;
    }

    // Map Android keycode to logical button index (0-10), or -1 if not found
    private int mapKeyCodeToGLFWButton(int keyCode) {
        int[] mapping = getCurrentMapping();
        // IMPORTANT: only check the first 11 logical button slots; ignore extended LT/RT storage.
        int len = Math.min(mapping.length, GAMEPAD_BUTTON_COUNT);
        for (int i = 0; i < len; i++) {
            if (mapping[i] == keyCode) return i;
        }
        return -1;
    }

     /* ========================= NEW: helpers for triggers ========================= */

    // Returns true if LT/RT is configured as TYPE_BUTTON matching this keyCode.
    private boolean isTriggerButton(int keyCode, boolean left) {
        int code = getTriggerConfig(left);
        if (!isSentinel(code)) return false;
        int type = (code & TYPE_MASK) >>> 24;
        int val  = (code & VALUE_MASK);
        // If TYPE is BUTTON, check if this keyCode matches the configured one.
        return (type == TYPE_BUTTON) && (val == keyCode);
    }

    // Returns true if LT/RT is configured as TYPE_AXIS (we then read analog input and send to a4/a5).
    private boolean isTriggerAxisMode(boolean left) {
        int code = getTriggerConfig(left);
        if (!isSentinel(code)) return true; // no custom config => default to axis mode
        int type = (code & TYPE_MASK) >>> 24;
        return type == TYPE_AXIS;
    }

    // Get raw stored value at STORE_IDX_LT/RT; returns 0 if no custom/too short.
    private int getTriggerConfig(boolean left) {
        int[] m = getCurrentMapping();
        int idx = left ? STORE_IDX_LT : STORE_IDX_RT;
        if (m == DEFAULT_MAPPING) return 0; // default has no trigger entries; treat as axis mode
        if (m != null && idx < m.length) return m[idx];
        return 0;
    }

    // Whether code looks like a sentinel (TYPE in high byte)
    private boolean isSentinel(int code) {
        int type = (code & TYPE_MASK) >>> 24;
        return (type == TYPE_AXIS) || (type == TYPE_BUTTON);
    }

    // Read trigger axis with capability checks and short fallbacks.
    // Uses getMotionRange(axis, source) (and plain getMotionRange(axis) as a backup)
    // because getAxisValue() often returns 0.0 for unsupported axes.
    private float readTriggerAxis(MotionEvent ev, boolean left) {
        InputDevice d = ev.getDevice();
        if (d == null) return 0f;

        // Preferred axis first, then two common fallbacks
        final int[] axes = left
                ? new int[]{ MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_Z,     MotionEvent.AXIS_BRAKE }
                : new int[]{ MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_RZ,    MotionEvent.AXIS_GAS   };

        final int src = ev.getSource();
        for (int ax : axes) {
            // Check support: with source (more strict) and without (broader)
            if (d.getMotionRange(ax, src) != null || d.getMotionRange(ax) != null) {
                float v = ev.getAxisValue(ax);
                // Normalize to [0..1]; some devices report [-1..1]
                if (v < 0f) v = -v;
                if (v > 1f) v = 1f;
                return v;
            }
        }
        return 0f;
    }

    private float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
    /* ======================= end of NEW helpers ======================= */
}
