package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;
import android.util.Log;

import com.zomdroid.input.KeyCodes;
import com.zomdroid.GameActivity;

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
  private static final String LOG_TAG = AbstractControlElement.class.getName();
  private boolean lastKeyboardPresent = false;

  // Listener interface for keyboard events
  public interface KeyboardListener {
    void onKeyboardConnected();
    void onKeyboardDisconnected();
    void onKeyboardKey(int glfwCode, boolean pressed);
  }

  // Create KeyboardManager
  public KeyboardManager(Context context, KeyboardListener listener) {
    //this.context = context;
    this.context = context.getApplicationContext();
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
          //anyKeyboard = true;
        }
      }
      //if (anyKeyboard)
      //  listener.onKeyboardConnected();
      //else
      //  listener.onKeyboardDisconnected();

      // Force sync + notify
      boolean now = !goodKeyboards.isEmpty();
      lastKeyboardPresent = !now; // <-- маленький трюк: гарантируем, что notify сработает
      notifyIfStateChanged();
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
    //InputDevice device = event.getDevice();
    //if (device == null || !device.supportsSource(InputDevice.SOURCE_KEYBOARD))  return false;
    final int src = event.getSource();
    if ((src & InputDevice.SOURCE_KEYBOARD) == 0 && src != 0) { return false; }

    boolean isPressed = event.getAction() == KeyEvent.ACTION_DOWN;

    //int glfwCode = mapAndroidKeyCodeToGLFW(androidCode);
    GLFWBinding b = KeyCodes.fromAndroid(androidCode);
    if (b == null) return false;
    //Toast.makeText(context, "[KB] glfw " + b.code + " , androidCode=" + androidCode, Toast.LENGTH_SHORT).show();
    //Log.d(LOG_TAG, "[KB] glfw " + b.code + " , androidCode=" + androidCode);
    if (listener != null) {
        listener.onKeyboardKey(b.code, isPressed);
        if (isPressed) {
            int unicode = event.getUnicodeChar(event.getMetaState() &
                    (KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON));
            //Log.d("ZomdroidChar", "unicode=" + unicode + " for androidKey=" + androidCode);
            if (unicode > 0) {
                InputNativeInterface.sendChar(unicode);
                //Log.d("ZomdroidChar", "sendChar called with " + unicode);
            }
        }
    }
    return true;
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
        //boolean wasEmpty = goodKeyboards.isEmpty();
        goodKeyboards.add(dk);
        //if (wasEmpty) listener.onKeyboardConnected();
        notifyIfStateChanged();
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

      notifyIfStateChanged();
    }
    @Override
    public void onInputDeviceChanged(int deviceId) {
      //.makeText(context, "onInputDeviceChanged", Toast.LENGTH_SHORT).show();
      InputDevice d = inputManager.getInputDevice(deviceId);
      if (d == null) {
            // устройство исчезло — считаем его removed
            DevKey oldKey = idToKey.remove(deviceId);
            if (oldKey != null) {
                goodKeyboards.remove(oldKey);
                quarantined.remove(oldKey);
            }
            notifyIfStateChanged();
            return;
      }

      //DevKey key = new DevKey(d);
      DevKey newKey = new DevKey(d);
      DevKey oldKey = idToKey.get(deviceId);

       // если был старый ключ — удаляем его из сетов, чтобы не оставлять "хвосты"
       if (oldKey != null && !oldKey.equals(newKey)) {
            goodKeyboards.remove(oldKey);
            quarantined.remove(oldKey);
       }

        if (isPhysicalKeyboard(d)) {
            idToKey.put(deviceId, newKey);
            quarantined.remove(newKey);
            goodKeyboards.add(newKey);
        } else {
            if (oldKey != null) {
                goodKeyboards.remove(oldKey);
                quarantined.remove(oldKey);
                idToKey.remove(deviceId);
            }
        }
        notifyIfStateChanged();
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

    private void notifyIfStateChanged() {
        boolean nowPresent = !goodKeyboards.isEmpty();
        if (nowPresent == lastKeyboardPresent) return;

        lastKeyboardPresent = nowPresent;
        if (nowPresent) listener.onKeyboardConnected();
        else listener.onKeyboardDisconnected();
    }
  }
