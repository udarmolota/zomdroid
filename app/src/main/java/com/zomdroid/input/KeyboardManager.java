package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;
import android.util.Log;

/**
 * Manages physical keyboard connection and input mapping for Android.
 */
public class KeyboardManager implements InputManager.InputDeviceListener {
  // Android input manager
  private final InputManager inputManager;
  // Keyboard event listener
  private final KeyboardListener listener;
  // Onscreen controls always shown
  private static boolean touchOverride = false;
  private final Context context;
  private final Set<DevKey> quarantined = new HashSet<>();
  private final Set<DevKey> goodKeyboards = new HashSet<>();
  private final java.util.Map<Integer, DevKey> idToKey = new java.util.HashMap<>();

  // Listener interface for keyboard events
  public interface KeyboardListener {
    void onKeyboardConnected();
    void onKeyboardDisconnected();
    void onKeyboardKey(int glfwCode, boolean pressed);
  }

  // Create KeyboardManager
  public KeyboardManager(Context context, KeyboardListener listener) {
    this.context = context;
    this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
    this.listener = listener;
  }

  // Register for keyboard device events
  public void register() {
      inputManager.registerInputDeviceListener(this, null);
      // Clear the "memory" of kb devices
      goodKeyboards.clear();
      quarantined.clear();
      idToKey.clear();

      // Check on start if a kb is already connected
      boolean anyKeyboard = false;
      for (int id : inputManager.getInputDeviceIds()) {
        InputDevice dev = inputManager.getInputDevice(id);
        if (dev != null && isPhysicalKeyboard(dev)) {
          DevKey dk = new DevKey(dev);
          goodKeyboards.add(dk);
          idToKey.put(id, dk);
          anyKeyboard = true;
        }
      }
      if (anyKeyboard)
        listener.onKeyboardConnected();
      else
        listener.onKeyboardDisconnected();
  }

  // Unregister from keyboard device events
  public void unregister() {
    inputManager.unregisterInputDeviceListener(this);
  }

  public static void setTouchOverride(boolean override) {
    touchOverride = override;
  }

  public static boolean isTouchOverrideEnabled() {
    return touchOverride;
  }

  // Handle KeyEvent from physical keyboard
  public boolean handleKeyEvent(KeyEvent event) {
    int androidCode = event.getKeyCode();
    InputDevice device = event.getDevice();
    if (!device.supportsSource(InputDevice.SOURCE_KEYBOARD)) return false;
    boolean isPressed = event.getAction() == KeyEvent.ACTION_DOWN;

    int glfwCode = mapAndroidKeyCodeToGLFW(androidCode);
    //Toast.makeText(context, "glfw=" + glfwCode + ", aCode=" + androidCode, Toast.LENGTH_SHORT).show();
    if (glfwCode >= 0) {
        listener.onKeyboardKey(glfwCode, isPressed);
        return true;
    } else {
        //Toast.makeText(context, "Unmapped key", Toast.LENGTH_SHORT).show();
        return false;
    }

  }

  // Map Android keycode to GLFWBinding code
  private int mapAndroidKeyCodeToGLFW(int androidCode) {
      switch (androidCode) {
        // A-Z
        case KeyEvent.KEYCODE_A: return GLFWBinding.KEY_A.code;
        case KeyEvent.KEYCODE_B: return GLFWBinding.KEY_B.code;
        case KeyEvent.KEYCODE_C: return GLFWBinding.KEY_C.code;
        case KeyEvent.KEYCODE_D: return GLFWBinding.KEY_D.code;
        case KeyEvent.KEYCODE_E: return GLFWBinding.KEY_E.code;
        case KeyEvent.KEYCODE_F: return GLFWBinding.KEY_F.code;
        case KeyEvent.KEYCODE_G: return GLFWBinding.KEY_G.code;
        case KeyEvent.KEYCODE_H: return GLFWBinding.KEY_H.code;
        case KeyEvent.KEYCODE_I: return GLFWBinding.KEY_I.code;
        case KeyEvent.KEYCODE_J: return GLFWBinding.KEY_J.code;
        case KeyEvent.KEYCODE_K: return GLFWBinding.KEY_K.code;
        case KeyEvent.KEYCODE_L: return GLFWBinding.KEY_L.code;
        case KeyEvent.KEYCODE_M: return GLFWBinding.KEY_M.code;
        case KeyEvent.KEYCODE_N: return GLFWBinding.KEY_N.code;
        case KeyEvent.KEYCODE_O: return GLFWBinding.KEY_O.code;
        case KeyEvent.KEYCODE_P: return GLFWBinding.KEY_P.code;
        case KeyEvent.KEYCODE_Q: return GLFWBinding.KEY_Q.code;
        case KeyEvent.KEYCODE_R: return GLFWBinding.KEY_R.code;
        case KeyEvent.KEYCODE_S: return GLFWBinding.KEY_S.code;
        case KeyEvent.KEYCODE_T: return GLFWBinding.KEY_T.code;
        case KeyEvent.KEYCODE_U: return GLFWBinding.KEY_U.code;
        case KeyEvent.KEYCODE_V: return GLFWBinding.KEY_V.code;
        case KeyEvent.KEYCODE_W: return GLFWBinding.KEY_W.code;
        case KeyEvent.KEYCODE_X: return GLFWBinding.KEY_X.code;
        case KeyEvent.KEYCODE_Y: return GLFWBinding.KEY_Y.code;
        case KeyEvent.KEYCODE_Z: return GLFWBinding.KEY_Z.code;

        // Numbers
        case KeyEvent.KEYCODE_0: return GLFWBinding.KEY_0.code;
        case KeyEvent.KEYCODE_1: return GLFWBinding.KEY_1.code;
        case KeyEvent.KEYCODE_2: return GLFWBinding.KEY_2.code;
        case KeyEvent.KEYCODE_3: return GLFWBinding.KEY_3.code;
        case KeyEvent.KEYCODE_4: return GLFWBinding.KEY_4.code;
        case KeyEvent.KEYCODE_5: return GLFWBinding.KEY_5.code;
        case KeyEvent.KEYCODE_6: return GLFWBinding.KEY_6.code;
        case KeyEvent.KEYCODE_7: return GLFWBinding.KEY_7.code;
        case KeyEvent.KEYCODE_8: return GLFWBinding.KEY_8.code;
        case KeyEvent.KEYCODE_9: return GLFWBinding.KEY_9.code;

        // Modificatros
        case KeyEvent.KEYCODE_SHIFT_LEFT: return GLFWBinding.KEY_LEFT_SHIFT.code;
        case KeyEvent.KEYCODE_SHIFT_RIGHT: return GLFWBinding.KEY_RIGHT_SHIFT.code;
        case KeyEvent.KEYCODE_CTRL_LEFT: return GLFWBinding.KEY_LEFT_CONTROL.code;
        case KeyEvent.KEYCODE_CTRL_RIGHT: return GLFWBinding.KEY_RIGHT_CONTROL.code;
        case KeyEvent.KEYCODE_ALT_LEFT: return GLFWBinding.KEY_LEFT_ALT.code;
        case KeyEvent.KEYCODE_ALT_RIGHT: return GLFWBinding.KEY_RIGHT_ALT.code;
        case KeyEvent.KEYCODE_META_LEFT: return GLFWBinding.KEY_LEFT_SUPER.code;
        case KeyEvent.KEYCODE_META_RIGHT: return GLFWBinding.KEY_RIGHT_SUPER.code;

        // System
        case KeyEvent.KEYCODE_ENTER: return GLFWBinding.KEY_ENTER.code;
        case KeyEvent.KEYCODE_ESCAPE: return GLFWBinding.KEY_ESCAPE.code;
        case KeyEvent.KEYCODE_BACK: return GLFWBinding.KEY_ESCAPE.code;
        case KeyEvent.KEYCODE_DEL: return GLFWBinding.KEY_BACKSPACE.code;
        case KeyEvent.KEYCODE_FORWARD_DEL: return GLFWBinding.KEY_DELETE.code;
        case KeyEvent.KEYCODE_TAB: return GLFWBinding.KEY_TAB.code;
        case KeyEvent.KEYCODE_INSERT: return GLFWBinding.KEY_INSERT.code;
        case KeyEvent.KEYCODE_CAPS_LOCK: return GLFWBinding.KEY_CAPS_LOCK.code;
        case KeyEvent.KEYCODE_NUM_LOCK: return GLFWBinding.KEY_NUM_LOCK.code;
        case KeyEvent.KEYCODE_SCROLL_LOCK: return GLFWBinding.KEY_SCROLL_LOCK.code;
        case KeyEvent.KEYCODE_BREAK: return GLFWBinding.KEY_PAUSE.code;
        case KeyEvent.KEYCODE_SYSRQ: return GLFWBinding.KEY_PRINT_SCREEN.code;

        // Symbols
        case KeyEvent.KEYCODE_SPACE: return GLFWBinding.KEY_SPACE.code;
        case KeyEvent.KEYCODE_MINUS: return GLFWBinding.KEY_MINUS.code;
        case KeyEvent.KEYCODE_EQUALS: return GLFWBinding.KEY_EQUAL.code;
        case KeyEvent.KEYCODE_LEFT_BRACKET: return GLFWBinding.KEY_LEFT_BRACKET.code;
        case KeyEvent.KEYCODE_RIGHT_BRACKET: return GLFWBinding.KEY_RIGHT_BRACKET.code;
        case KeyEvent.KEYCODE_BACKSLASH: return GLFWBinding.KEY_BACKSLASH.code;
        case KeyEvent.KEYCODE_SEMICOLON: return GLFWBinding.KEY_SEMICOLON.code;
        case KeyEvent.KEYCODE_APOSTROPHE: return GLFWBinding.KEY_APOSTROPHE.code;
        case KeyEvent.KEYCODE_COMMA: return GLFWBinding.KEY_COMMA.code;
        case KeyEvent.KEYCODE_PERIOD: return GLFWBinding.KEY_PERIOD.code;
        case KeyEvent.KEYCODE_SLASH: return GLFWBinding.KEY_SLASH.code;
        case KeyEvent.KEYCODE_GRAVE: return GLFWBinding.KEY_GRAVE_ACCENT.code;

        // Numpad
        case KeyEvent.KEYCODE_NUMPAD_0: return GLFWBinding.KEY_KP_0.code;
        case KeyEvent.KEYCODE_NUMPAD_1: return GLFWBinding.KEY_KP_1.code;
        case KeyEvent.KEYCODE_NUMPAD_2: return GLFWBinding.KEY_KP_2.code;
        case KeyEvent.KEYCODE_NUMPAD_3: return GLFWBinding.KEY_KP_3.code;
        case KeyEvent.KEYCODE_NUMPAD_4: return GLFWBinding.KEY_KP_4.code;
        case KeyEvent.KEYCODE_NUMPAD_5: return GLFWBinding.KEY_KP_5.code;
        case KeyEvent.KEYCODE_NUMPAD_6: return GLFWBinding.KEY_KP_6.code;
        case KeyEvent.KEYCODE_NUMPAD_7: return GLFWBinding.KEY_KP_7.code;
        case KeyEvent.KEYCODE_NUMPAD_8: return GLFWBinding.KEY_KP_8.code;
        case KeyEvent.KEYCODE_NUMPAD_9: return GLFWBinding.KEY_KP_9.code;
        case KeyEvent.KEYCODE_NUMPAD_ENTER: return GLFWBinding.KEY_KP_ENTER.code;
        case KeyEvent.KEYCODE_NUMPAD_ADD: return GLFWBinding.KEY_KP_ADD.code;
        case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return GLFWBinding.KEY_KP_SUBTRACT.code;
        case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return GLFWBinding.KEY_KP_MULTIPLY.code;
        case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return GLFWBinding.KEY_KP_DIVIDE.code;
        case KeyEvent.KEYCODE_NUMPAD_DOT: return GLFWBinding.KEY_KP_DECIMAL.code;

        // Functional
        case KeyEvent.KEYCODE_F1: return GLFWBinding.KEY_F1.code;
        case KeyEvent.KEYCODE_F2: return GLFWBinding.KEY_F2.code;
        case KeyEvent.KEYCODE_F3: return GLFWBinding.KEY_F3.code;
        case KeyEvent.KEYCODE_F4: return GLFWBinding.KEY_F4.code;
        case KeyEvent.KEYCODE_F5: return GLFWBinding.KEY_F5.code;
        case KeyEvent.KEYCODE_F6: return GLFWBinding.KEY_F6.code;
        case KeyEvent.KEYCODE_F7: return GLFWBinding.KEY_F7.code;
        case KeyEvent.KEYCODE_F8: return GLFWBinding.KEY_F8.code;
        case KeyEvent.KEYCODE_F9: return GLFWBinding.KEY_F9.code;
        case KeyEvent.KEYCODE_F10: return GLFWBinding.KEY_F10.code;
        case KeyEvent.KEYCODE_F11: return GLFWBinding.KEY_F11.code;
        case KeyEvent.KEYCODE_F12: return GLFWBinding.KEY_F12.code;

        // Arrows
        case KeyEvent.KEYCODE_DPAD_UP: return GLFWBinding.KEY_UP.code;
        case KeyEvent.KEYCODE_DPAD_DOWN: return GLFWBinding.KEY_DOWN.code;
        case KeyEvent.KEYCODE_DPAD_LEFT: return GLFWBinding.KEY_LEFT.code;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return GLFWBinding.KEY_RIGHT.code;

        case KeyEvent.KEYCODE_PAGE_UP:    return GLFWBinding.KEY_PAGE_UP.code;
        case KeyEvent.KEYCODE_PAGE_DOWN:  return GLFWBinding.KEY_PAGE_DOWN.code;
        case KeyEvent.KEYCODE_MOVE_HOME:  return GLFWBinding.KEY_HOME.code;
        case KeyEvent.KEYCODE_MOVE_END:   return GLFWBinding.KEY_END.code;

        case KeyEvent.KEYCODE_NAVIGATE_NEXT:     return GLFWBinding.KEY_TAB.code;
        case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS: return GLFWBinding.KEY_TAB.code;

        default: return -1;
      }
  }


  @Override
  public void onInputDeviceAdded(int deviceId) {
      //Toast.makeText(context, "onInputDeviceAdded", Toast.LENGTH_SHORT).show();
      InputDevice d = inputManager.getInputDevice(deviceId);
      if (d == null) return;

      if (isPhysicalKeyboard(d)) {
        //Toast.makeText(context, "DeviceAdded KB true", Toast.LENGTH_SHORT).show();
        DevKey dk = new DevKey(d);
        idToKey.put(deviceId, dk);
        boolean wasEmpty = goodKeyboards.isEmpty();
        goodKeyboards.add(dk);
        if (wasEmpty) listener.onKeyboardConnected();
      }
  }

  @Override
  public void onInputDeviceRemoved(int deviceId) {
      DevKey dk = idToKey.remove(deviceId);
      if (dk != null) {
        goodKeyboards.remove(dk);
        quarantined.remove(dk);
      }

      //Toast.makeText(context, "onInputDeviceRemoved", Toast.LENGTH_SHORT).show();

      // State recalculation
      if (goodKeyboards.isEmpty())
        listener.onKeyboardDisconnected();
      else
        listener.onKeyboardConnected();
    }
    @Override
    public void onInputDeviceChanged(int deviceId) {
      //.makeText(context, "onInputDeviceChanged", Toast.LENGTH_SHORT).show();
      InputDevice d = inputManager.getInputDevice(deviceId);
      if (d == null) {
        // Device already disappeared — recalculate overall state
        boolean anyKeyboard = hasAnyKeyboard();
        if (anyKeyboard) listener.onKeyboardConnected();
        else listener.onKeyboardDisconnected();
        return;
      }
      DevKey key = new DevKey(d);

      if (isPhysicalKeyboard(d)) {
        quarantined.remove(key);
        if (goodKeyboards.add(key)) {
          // new - wasn't before
          //Toast.makeText(context, "new device added", Toast.LENGTH_SHORT).show();
          listener.onKeyboardConnected();
        }
      } else {
        // if it was in goodKeyboards will remove
        boolean removed = goodKeyboards.remove(key);
        if (removed && !hasAnyKeyboard()) {
          //Toast.makeText(context, "device removed and disconnected", Toast.LENGTH_SHORT).show();
          listener.onKeyboardDisconnected();
        }
      }
    }

    // True if InputDevice is a physical keyboard
    private boolean isPhysicalKeyboard(InputDevice device) {
      if (device == null) return false;
      boolean hasKeyboard = false;

      // The launcher 'Always onscreen buttons' is on/off
      if (isTouchOverrideEnabled()) { return false; }

      // The source is Keyboard
      if (!device.supportsSource(InputDevice.SOURCE_KEYBOARD)) { return false; }

      // Checking the device type
      int type = device.getKeyboardType();
      if (type != InputDevice.KEYBOARD_TYPE_ALPHABETIC && type != InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) return false;

      //  Quick name-based filter (exclude mouse, touchpads, gamepads)
      String name = device.getName().toLowerCase();
      //Toast.makeText(context, "isPhysicalKeyboard name "+name, Toast.LENGTH_SHORT).show();
      if (name.contains("mouse") || name.contains("touch") || name.contains("touchpad") ||
        name.contains("remote") || name.contains("gamepad") || name.contains("controller"))
        return false;

      // KB keys test
      try {
        boolean[] out = device.hasKeys(
          KeyEvent.KEYCODE_A,
          KeyEvent.KEYCODE_ENTER,
          KeyEvent.KEYCODE_SPACE
        );
        int hits = 0;
        for (boolean b : out) if (b) hits++;
        hasKeyboard = hits >= 2;
        //return hits >= 2;
      } catch (Throwable t) {
          //Toast.makeText(context, "physical keyboard "+hasKeyboard, Toast.LENGTH_SHORT).show();
          return false;
      }
      return hasKeyboard;
    }

    private boolean hasAnyKeyboard() {
      // Fast path: if goodKeyboards is not empty — we already have one
      if (!goodKeyboards.isEmpty()) return true;

      // Fallback: rescan the inventory (in case the set got out of sync)
      for (int id : inputManager.getInputDeviceIds()) {
        InputDevice dev = inputManager.getInputDevice(id);
        if (dev != null && isPhysicalKeyboard(dev)) {
          return true;
        }
      }
      return false;
    }
  }
