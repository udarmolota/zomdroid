package com.zomdroid.input;

import android.util.SparseArray;
import android.view.KeyEvent;
import java.util.EnumMap;
import javax.annotation.Nullable;

/**
 * Single source of truth: Android KeyEvent codes <-> GLFWBinding.
 * Forward map (Android -> GLFW) задаём вручную (по твоему свитчу),
 * обратную (GLFW -> Android) строим автоматически.
 */
public final class KeyCodes {

  public static final SparseArray<GLFWBinding> ANDROID_TO_GLFW = new SparseArray<>();
  public static final EnumMap<GLFWBinding, Integer> GLFW_TO_ANDROID = new EnumMap<>(GLFWBinding.class);

  static {
    // ===== A-Z =====
    put(KeyEvent.KEYCODE_A, GLFWBinding.KEY_A);
    put(KeyEvent.KEYCODE_B, GLFWBinding.KEY_B);
    put(KeyEvent.KEYCODE_C, GLFWBinding.KEY_C);
    put(KeyEvent.KEYCODE_D, GLFWBinding.KEY_D);
    put(KeyEvent.KEYCODE_E, GLFWBinding.KEY_E);
    put(KeyEvent.KEYCODE_F, GLFWBinding.KEY_F);
    put(KeyEvent.KEYCODE_G, GLFWBinding.KEY_G);
    put(KeyEvent.KEYCODE_H, GLFWBinding.KEY_H);
    put(KeyEvent.KEYCODE_I, GLFWBinding.KEY_I);
    put(KeyEvent.KEYCODE_J, GLFWBinding.KEY_J);
    put(KeyEvent.KEYCODE_K, GLFWBinding.KEY_K);
    put(KeyEvent.KEYCODE_L, GLFWBinding.KEY_L);
    put(KeyEvent.KEYCODE_M, GLFWBinding.KEY_M);
    put(KeyEvent.KEYCODE_N, GLFWBinding.KEY_N);
    put(KeyEvent.KEYCODE_O, GLFWBinding.KEY_O);
    put(KeyEvent.KEYCODE_P, GLFWBinding.KEY_P);
    put(KeyEvent.KEYCODE_Q, GLFWBinding.KEY_Q);
    put(KeyEvent.KEYCODE_R, GLFWBinding.KEY_R);
    put(KeyEvent.KEYCODE_S, GLFWBinding.KEY_S);
    put(KeyEvent.KEYCODE_T, GLFWBinding.KEY_T);
    put(KeyEvent.KEYCODE_U, GLFWBinding.KEY_U);
    put(KeyEvent.KEYCODE_V, GLFWBinding.KEY_V);
    put(KeyEvent.KEYCODE_W, GLFWBinding.KEY_W);
    put(KeyEvent.KEYCODE_X, GLFWBinding.KEY_X);
    put(KeyEvent.KEYCODE_Y, GLFWBinding.KEY_Y);
    put(KeyEvent.KEYCODE_Z, GLFWBinding.KEY_Z);

    // ===== Numbers (top row) =====
    put(KeyEvent.KEYCODE_0, GLFWBinding.KEY_0);
    put(KeyEvent.KEYCODE_1, GLFWBinding.KEY_1);
    put(KeyEvent.KEYCODE_2, GLFWBinding.KEY_2);
    put(KeyEvent.KEYCODE_3, GLFWBinding.KEY_3);
    put(KeyEvent.KEYCODE_4, GLFWBinding.KEY_4);
    put(KeyEvent.KEYCODE_5, GLFWBinding.KEY_5);
    put(KeyEvent.KEYCODE_6, GLFWBinding.KEY_6);
    put(KeyEvent.KEYCODE_7, GLFWBinding.KEY_7);
    put(KeyEvent.KEYCODE_8, GLFWBinding.KEY_8);
    put(KeyEvent.KEYCODE_9, GLFWBinding.KEY_9);

    // ===== Modifiers =====
    put(KeyEvent.KEYCODE_SHIFT_LEFT,  GLFWBinding.KEY_LEFT_SHIFT);
    put(KeyEvent.KEYCODE_SHIFT_RIGHT, GLFWBinding.KEY_RIGHT_SHIFT);
    put(KeyEvent.KEYCODE_CTRL_LEFT,   GLFWBinding.KEY_LEFT_CONTROL);
    put(KeyEvent.KEYCODE_CTRL_RIGHT,  GLFWBinding.KEY_RIGHT_CONTROL);
    put(KeyEvent.KEYCODE_ALT_LEFT,    GLFWBinding.KEY_LEFT_ALT);
    put(KeyEvent.KEYCODE_ALT_RIGHT,   GLFWBinding.KEY_RIGHT_ALT);
    put(KeyEvent.KEYCODE_META_LEFT,   GLFWBinding.KEY_LEFT_SUPER);
    put(KeyEvent.KEYCODE_META_RIGHT,  GLFWBinding.KEY_RIGHT_SUPER);

    // ===== System =====
    put(KeyEvent.KEYCODE_ENTER,        GLFWBinding.KEY_ENTER);
    put(KeyEvent.KEYCODE_ESCAPE,       GLFWBinding.KEY_ESCAPE);
    // BACK намеренно НЕ маппим на ESC (это был твой баг у геймпадов)
    put(KeyEvent.KEYCODE_DEL,          GLFWBinding.KEY_BACKSPACE);
    put(KeyEvent.KEYCODE_FORWARD_DEL,  GLFWBinding.KEY_DELETE);
    put(KeyEvent.KEYCODE_TAB,          GLFWBinding.KEY_TAB);
    put(KeyEvent.KEYCODE_INSERT,       GLFWBinding.KEY_INSERT);
    put(KeyEvent.KEYCODE_CAPS_LOCK,    GLFWBinding.KEY_CAPS_LOCK);
    put(KeyEvent.KEYCODE_NUM_LOCK,     GLFWBinding.KEY_NUM_LOCK);
    put(KeyEvent.KEYCODE_SCROLL_LOCK,  GLFWBinding.KEY_SCROLL_LOCK);
    put(KeyEvent.KEYCODE_BREAK,        GLFWBinding.KEY_PAUSE);
    put(KeyEvent.KEYCODE_SYSRQ,        GLFWBinding.KEY_PRINT_SCREEN);

    // ===== Symbols =====
    put(KeyEvent.KEYCODE_SPACE,          GLFWBinding.KEY_SPACE);
    put(KeyEvent.KEYCODE_MINUS,          GLFWBinding.KEY_MINUS);
    put(KeyEvent.KEYCODE_EQUALS,         GLFWBinding.KEY_EQUAL);
    put(KeyEvent.KEYCODE_LEFT_BRACKET,   GLFWBinding.KEY_LEFT_BRACKET);
    put(KeyEvent.KEYCODE_RIGHT_BRACKET,  GLFWBinding.KEY_RIGHT_BRACKET);
    put(KeyEvent.KEYCODE_BACKSLASH,      GLFWBinding.KEY_BACKSLASH);
    put(KeyEvent.KEYCODE_SEMICOLON,      GLFWBinding.KEY_SEMICOLON);
    put(KeyEvent.KEYCODE_APOSTROPHE,     GLFWBinding.KEY_APOSTROPHE);
    put(KeyEvent.KEYCODE_COMMA,          GLFWBinding.KEY_COMMA);
    put(KeyEvent.KEYCODE_PERIOD,         GLFWBinding.KEY_PERIOD);
    put(KeyEvent.KEYCODE_SLASH,          GLFWBinding.KEY_SLASH);
    put(KeyEvent.KEYCODE_GRAVE,          GLFWBinding.KEY_GRAVE_ACCENT);

    // ===== Numpad =====
    put(KeyEvent.KEYCODE_NUMPAD_0,       GLFWBinding.KEY_KP_0);
    put(KeyEvent.KEYCODE_NUMPAD_1,       GLFWBinding.KEY_KP_1);
    put(KeyEvent.KEYCODE_NUMPAD_2,       GLFWBinding.KEY_KP_2);
    put(KeyEvent.KEYCODE_NUMPAD_3,       GLFWBinding.KEY_KP_3);
    put(KeyEvent.KEYCODE_NUMPAD_4,       GLFWBinding.KEY_KP_4);
    put(KeyEvent.KEYCODE_NUMPAD_5,       GLFWBinding.KEY_KP_5);
    put(KeyEvent.KEYCODE_NUMPAD_6,       GLFWBinding.KEY_KP_6);
    put(KeyEvent.KEYCODE_NUMPAD_7,       GLFWBinding.KEY_KP_7);
    put(KeyEvent.KEYCODE_NUMPAD_8,       GLFWBinding.KEY_KP_8);
    put(KeyEvent.KEYCODE_NUMPAD_9,       GLFWBinding.KEY_KP_9);
    put(KeyEvent.KEYCODE_NUMPAD_ENTER,   GLFWBinding.KEY_KP_ENTER);
    put(KeyEvent.KEYCODE_NUMPAD_ADD,     GLFWBinding.KEY_KP_ADD);
    put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT,GLFWBinding.KEY_KP_SUBTRACT);
    put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY,GLFWBinding.KEY_KP_MULTIPLY);
    put(KeyEvent.KEYCODE_NUMPAD_DIVIDE,  GLFWBinding.KEY_KP_DIVIDE);
    put(KeyEvent.KEYCODE_NUMPAD_DOT,     GLFWBinding.KEY_KP_DECIMAL);

    // ===== Function keys =====
    put(KeyEvent.KEYCODE_F1,  GLFWBinding.KEY_F1);
    put(KeyEvent.KEYCODE_F2,  GLFWBinding.KEY_F2);
    put(KeyEvent.KEYCODE_F3,  GLFWBinding.KEY_F3);
    put(KeyEvent.KEYCODE_F4,  GLFWBinding.KEY_F4);
    put(KeyEvent.KEYCODE_F5,  GLFWBinding.KEY_F5);
    put(KeyEvent.KEYCODE_F6,  GLFWBinding.KEY_F6);
    put(KeyEvent.KEYCODE_F7,  GLFWBinding.KEY_F7);
    put(KeyEvent.KEYCODE_F8,  GLFWBinding.KEY_F8);
    put(KeyEvent.KEYCODE_F9,  GLFWBinding.KEY_F9);
    put(KeyEvent.KEYCODE_F10, GLFWBinding.KEY_F10);
    put(KeyEvent.KEYCODE_F11, GLFWBinding.KEY_F11);
    put(KeyEvent.KEYCODE_F12, GLFWBinding.KEY_F12);

    // ===== Arrows / navigation =====
    put(KeyEvent.KEYCODE_DPAD_UP,     GLFWBinding.KEY_UP);
    put(KeyEvent.KEYCODE_DPAD_DOWN,   GLFWBinding.KEY_DOWN);
    put(KeyEvent.KEYCODE_DPAD_LEFT,   GLFWBinding.KEY_LEFT);
    put(KeyEvent.KEYCODE_DPAD_RIGHT,  GLFWBinding.KEY_RIGHT);

    put(KeyEvent.KEYCODE_PAGE_UP,     GLFWBinding.KEY_PAGE_UP);
    put(KeyEvent.KEYCODE_PAGE_DOWN,   GLFWBinding.KEY_PAGE_DOWN);
    put(KeyEvent.KEYCODE_MOVE_HOME,   GLFWBinding.KEY_HOME);
    put(KeyEvent.KEYCODE_MOVE_END,    GLFWBinding.KEY_END);

    // Android system nav → стрелки (как у тебя было)
    put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,     GLFWBinding.KEY_UP);
    put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,   GLFWBinding.KEY_DOWN);
    put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,   GLFWBinding.KEY_LEFT);
    put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,  GLFWBinding.KEY_RIGHT);

    // Навигация (next/previous) → TAB (как у тебя)
    put(KeyEvent.KEYCODE_NAVIGATE_NEXT,     GLFWBinding.KEY_TAB);
    put(KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, GLFWBinding.KEY_TAB);
  }

  private static void put(int androidKey, GLFWBinding glfw) {
    ANDROID_TO_GLFW.put(androidKey, glfw);
    // Обратная таблица: берём только первый встретившийся Android код
    GLFW_TO_ANDROID.putIfAbsent(glfw, androidKey);
  }

  @Nullable
  public static GLFWBinding fromAndroid(int androidKey) {
    return ANDROID_TO_GLFW.get(androidKey);
  }

  @Nullable
  public static Integer toAndroid(GLFWBinding glfw) {
    return GLFW_TO_ANDROID.get(glfw);
  }

  private KeyCodes() {}
}
