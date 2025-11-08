package com.zomdroid.input;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.util.Log;
import android.view.MotionEvent;
import android.view.InputDevice;

import java.lang.ref.WeakReference;

public final class InputDispatch {
  private static final String TAG = "InputDispatch";
  private static float tapAnchorX = Float.NaN;
  private static float tapAnchorY = Float.NaN;

  private static WeakReference<Activity> activityRef = new WeakReference<>(null);
  private static WeakReference<View>     targetRef   = new WeakReference<>(null);

  // Колбэк: активити должна на короткое время переключить UI в "клавиатуру"
  private static Runnable keyboardHint;

  private InputDispatch() {}

  /** Куда диспатчить системные KeyEvent (уровень Activity) */
  public static void setActivity(Activity a) {
    activityRef = new WeakReference<>(a);
  }

  /** Фолбэк-приёмник (обычно gameSv) */
  public static void setTarget(View v) {
    targetRef = new WeakReference<>(v);
  }

  /** Установить хинт для временного включения клавиатурного UI */
  public static void setKeyboardHint(Runnable r) {
    keyboardHint = r;
  }

  /** Дёрнуть хинт (из MNK-ветки) */
  public static void hintKeyboardUI() {
    final Activity a = activityRef.get();
    final Runnable r = keyboardHint;
    Log.v(TAG, "hintKeyboardUI() fire; activity=" + (a != null) + " runnable=" + (r != null));
    if (a != null && r != null) {
      a.runOnUiThread(r);
    }
  }

  /** Удобно для ветвлений/логов */
  public static boolean hasActivity() { return activityRef.get() != null; }
  public static boolean hasTarget()   { return targetRef.get()   != null; }

  /** Сформировать и отправить KeyEvent (DOWN/UP) */
  public static void dispatchKey(int androidKeyCode, boolean isPressed) {
    final Activity a = activityRef.get();
    final View v = targetRef.get();

    Log.v(TAG, "dispatchKey code=" + androidKeyCode
      + " pressed=" + isPressed
      + " hasActivity=" + (a != null)
      + " hasTarget=" + (v != null));

    long now = SystemClock.uptimeMillis();
    KeyEvent ev = new KeyEvent(
      now, now,
      isPressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
      androidKeyCode,
      0,                             // repeat
      0,                             // meta
      KeyCharacterMap.VIRTUAL_KEYBOARD, // deviceId (виртуальная клавиатура)
      0,                             // scancode
      0,                             // flags
      InputDevice.SOURCE_KEYBOARD
    );

    if (a != null) {
      // Проводим через Activity → попадёт в onKeyDown/onKeyUp
      a.runOnUiThread(() -> a.dispatchKeyEvent(ev));
      return;
    }

    if (v != null) {
      // Фолбэк в View
      v.post(() -> v.dispatchKeyEvent(ev));
    }
  }

  public static void dispatchMouseTapNoAB(float x, float y) {
    final View v = targetRef.get();
    if (v == null) return;

    long t = SystemClock.uptimeMillis();

    MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
    MotionEvent.PointerCoords[]     coords = new MotionEvent.PointerCoords[1];

    MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
    pp.id = 0;
    pp.toolType = MotionEvent.TOOL_TYPE_MOUSE; // именно мышь
    props[0] = pp;

    MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
    pc.x = x; pc.y = y;
    pc.pressure = 1f; pc.size = 1f;
    coords[0] = pc;

    // HOVER_ENTER (некоторые игры «просыпаются» от ховера)
    MotionEvent hoverEnter = MotionEvent.obtain(
      t, t, MotionEvent.ACTION_HOVER_ENTER,
      1, props, coords, 0, 0,
      1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);

    // HOVER_MOVE — микродвижение «мыши»
    long t1 = t + 8;
    MotionEvent.PointerCoords pcMove = new MotionEvent.PointerCoords();
    pcMove.x = x + 0.5f; pcMove.y = y + 0.5f;
    pcMove.pressure = 1f; pcMove.size = 1f;
    MotionEvent.PointerCoords[] coordsMove = new MotionEvent.PointerCoords[]{ pcMove };

    MotionEvent hoverMove = MotionEvent.obtain(
      t, t1, MotionEvent.ACTION_HOVER_MOVE,
      1, props, coordsMove, 0, 0,
      1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);

    // DOWN (важно: передаём buttonState = PRIMARY)
    long t2 = t1 + 8;
    MotionEvent down = MotionEvent.obtain(
      t, t2, MotionEvent.ACTION_DOWN,
      1, props, coords, 0, MotionEvent.BUTTON_PRIMARY,
      1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);

    // UP
    long t3 = t2 + 16;
    MotionEvent up = MotionEvent.obtain(
      t, t3, MotionEvent.ACTION_UP,
      1, props, coords, 0, 0,
      1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);

    // HOVER_EXIT (не обязательно, но красиво «закрывает»)
    long t4 = t3 + 8;
    MotionEvent hoverExit = MotionEvent.obtain(
      t, t4, MotionEvent.ACTION_HOVER_EXIT,
      1, props, coords, 0, 0,
      1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);

    Log.v("[InputDispatch]", "dispatchMouseTapNoAB at ("+x+","+y+") as SOURCE_MOUSE");
    v.post(() -> {
      v.dispatchGenericMotionEvent(hoverEnter);
      v.dispatchGenericMotionEvent(hoverMove);
      v.dispatchTouchEvent(down);
      v.dispatchTouchEvent(up);
      v.dispatchGenericMotionEvent(hoverExit);
    });
  }

  public static void dispatchMouseMove(float x, float y) {
    final Activity a = activityRef.get();
    final View v = targetRef.get();
    if (v == null) return;

    long now = SystemClock.uptimeMillis();

    MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
    MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
    pp.id = 0;
    pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
    props[0] = pp;

    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
    MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
    pc.x = x; pc.y = y; pc.pressure = 0f; pc.size = 1f;
    coords[0] = pc;

    MotionEvent hoverEnter = MotionEvent.obtain(
      now, now,
      MotionEvent.ACTION_HOVER_ENTER,
      1, props, coords,
      0, 0, 1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);
    hoverEnter.setSource(InputDevice.SOURCE_MOUSE);

    MotionEvent hoverMove = MotionEvent.obtain(
      now, now,
      MotionEvent.ACTION_HOVER_MOVE,
      1, props, coords,
      0, 0, 1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0);
    hoverMove.setSource(InputDevice.SOURCE_MOUSE);

    Runnable send = () -> {
      v.dispatchGenericMotionEvent(hoverEnter);
      v.dispatchGenericMotionEvent(hoverMove);
    };
    if (a != null) a.runOnUiThread(send); else v.post(send);

    hoverEnter.recycle();
    hoverMove.recycle();

    Log.v("[InputDispatch]", "dispatchMouseMove to (" + x + "," + y + ")");
  }

  public static void setMouseTapAnchor(float x, float y) {
    tapAnchorX = x;
    tapAnchorY = y;
  }

  private static void resolveAnchorIfNeeded() {
    if (!Float.isNaN(tapAnchorX) && !Float.isNaN(tapAnchorY)) return;
    final View v = targetRef.get();
    if (v != null) {
      tapAnchorX = v.getWidth()  * 0.5f;
      tapAnchorY = v.getHeight() * 0.5f;
    } else {
      tapAnchorX = 5f; tapAnchorY = 5f; // безопасный дефолт
    }
  }

  public static float getMouseTapX() { resolveAnchorIfNeeded(); return tapAnchorX; }
  public static float getMouseTapY() { resolveAnchorIfNeeded(); return tapAnchorY; }
}
