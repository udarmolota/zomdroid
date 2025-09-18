package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Adds:
 * - Robust D-Pad HAT (AXIS_HAT_X/Y) → button synthesis with hysteresis
 * - Flexible trigger axis resolution (LTRIGGER/RTRIGGER, BRAKE/GAS, Z/RZ, split-Z)
 * - Optional mapping for DPAD keycodes and dedicated LT/RT logical buttons
 */
public class GamepadManager implements InputManager.InputDeviceListener {
    // Android input manager
    private final InputManager inputManager;
    // Gamepad event listener
    private final GamepadListener listener;

    // Number of logical gamepad buttons (no D-Pad)
    /* -----------------------------
     * Logical buttons (GLFW-like)
     * -----------------------------
     * We extend from 11 → 17 to include D-Pad (4) + LT/RT as buttons.
     */
    public static final int GAMEPAD_BUTTON_COUNT = 17;

    public static final int BTN_A = 0, BTN_B = 1, BTN_X = 2, BTN_Y = 3;
    public static final int BTN_LB = 4, BTN_RB = 5, BTN_BACK = 6, BTN_START = 7, BTN_GUIDE = 8;
    public static final int BTN_LSTICK = 9, BTN_RSTICK = 10;
    public static final int BTN_DPAD_UP = 11, BTN_DPAD_RIGHT = 12, BTN_DPAD_DOWN = 13, BTN_DPAD_LEFT = 14;
    public static final int BTN_LT = 15, BTN_RT = 16;

    /**
     * Default mapping holds Android KeyCodes for "real" buttons.
     * D-Pad sometimes arrives as AXIS_HAT_*; we still include DPAD keycodes for pads that emit KeyEvents.
     * LT/RT are axis-based on most controllers, so keep them as -1 (no KeyCode).
     */

    // Default mapping: [A, B, X, Y, LB, RB, SELECT, START, GUIDE, LSTICK, RSTICK]
    private static final int[] DEFAULT_MAPPING = {
        KeyEvent.KEYCODE_BUTTON_A,       // 0: A
        KeyEvent.KEYCODE_BUTTON_B,       // 1: B
        KeyEvent.KEYCODE_BUTTON_X,       // 2: X
        KeyEvent.KEYCODE_BUTTON_Y,       // 3: Y
        KeyEvent.KEYCODE_BUTTON_L1,      // 4: LB
        KeyEvent.KEYCODE_BUTTON_R1,      // 5: RB
        KeyEvent.KEYCODE_BUTTON_SELECT,  // 6: BACK/SELECT
        KeyEvent.KEYCODE_BUTTON_START,   // 7: START
        KeyEvent.KEYCODE_BUTTON_MODE,    // 8: GUIDE
        KeyEvent.KEYCODE_BUTTON_THUMBL,  // 9: LSTICK
        KeyEvent.KEYCODE_BUTTON_THUMBR,  // 10: RSTICK
        KeyEvent.KEYCODE_DPAD_UP,        // 11: DPAD UP (if emitted as KeyEvent)
        KeyEvent.KEYCODE_DPAD_RIGHT,     // 12: DPAD RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN,      // 13: DPAD DOWN
        KeyEvent.KEYCODE_DPAD_LEFT,      // 14: DPAD LEFT
        -1,                               // 15: LT (axis, no KeyCode)
        -1                                // 16: RT (axis, no KeyCode)
    };

    private float lastHatX = 0f, lastHatY = 0f;
    private boolean dpadUp=false, dpadRight=false, dpadDown=false, dpadLeft=false;
    
    private static final float HAT_ON = 0.5f, HAT_OFF = 0.3f;
    
    private static final float TRIGGER_ON = 0.55f, TRIGGER_OFF = 0.40f;
    private boolean ltPressed = false, rtPressed = false;
    private float lastLT = 0f, lastRT = 0f;
    
    private AxisResolver axisResolver = new AxisResolver();

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

    // HAT → button state with hysteresis
    private float lastHatX = 0f, lastHatY = 0f;
    private boolean dpadUp = false, dpadRight = false, dpadDown = false, dpadLeft = false;
    private static final float HAT_ON = 0.5f;   // turn-on threshold
    private static final float HAT_OFF = 0.3f;  // turn-off threshold (hysteresis)

    // Trigger → button state with hysteresis
    private static final float TRIGGER_ON = 0.55f;
    private static final float TRIGGER_OFF = 0.40f;
    private boolean ltPressed = false, rtPressed = false;
    private float lastLT = 0f, lastRT = 0f;

    // Axis resolver adapts to current device capabilities
    private AxisResolver axisResolver = new AxisResolver();

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
            try {
                if (parts.length == GAMEPAD_BUTTON_COUNT) {
                    // Exact match (17)
                    int[] loaded = new int[GAMEPAD_BUTTON_COUNT];
                    for (int i = 0; i < GAMEPAD_BUTTON_COUNT; i++) loaded[i] = Integer.parseInt(parts[i]);
                    customMapping = loaded;
                } else if (parts.length == 11 || parts.length == 15) {
                    // Migrate old presets: copy what we have and fill the rest with -1
                    int[] migrated = new int[GAMEPAD_BUTTON_COUNT];
                    for (int i = 0; i < GAMEPAD_BUTTON_COUNT; i++) migrated[i] = -1;
                    int n = Math.min(parts.length, GAMEPAD_BUTTON_COUNT);
                    for (int i = 0; i < n; i++) migrated[i] = Integer.parseInt(parts[i]);
                    customMapping = migrated;
                    // Do not overwrite on disk here; let save happen explicitly when user re-saves.
                } else {
                    customMapping = null; // unknown format → fallback to default
                }
            } catch (NumberFormatException e) {
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
            axisResolver.buildForDevice(dev);
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
        // Rebuild resolver when capabilities change (e.g., different axes available)
        InputDevice dev = inputManager.getInputDevice(deviceId);
        if (dev != null && isGamepadDevice(dev)) {
            axisResolver.buildForDevice(dev);
        }
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
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
    }
}
