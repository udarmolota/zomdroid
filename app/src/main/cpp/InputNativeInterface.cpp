// InputNativeInterface.cpp
// JNI bridge: forwards Java InputNativeInterface calls to C event queue in zomdroid.c

#include <jni.h>
#include <stdint.h>
#include <stdbool.h>

// Forward declarations of C functions implemented in zomdroid.c.
// Using extern "C" prevents C++ name mangling so the linker can find them.
extern "C" {
    void zomdroid_event_keyboard(int key, bool isPressed);
    void zomdroid_event_cursor_pos(double x, double y);
    void zomdroid_event_mouse_button(int button, bool isPressed);
    void zomdroid_event_joystick_connected(void);
    void zomdroid_event_joystick_axis(int axis, float state);
    void zomdroid_event_joystick_dpad(int dpad, char state);
    void zomdroid_event_joystick_button(int button, bool isPressed);
}

// Helper to convert jboolean to bool
static inline bool toBool(jboolean b) { return b == JNI_TRUE; }

// package: com.zomdroid.input.InputNativeInterface

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendKeyboard
  (JNIEnv* /*env*/, jclass /*clazz*/, jint key, jboolean isPressed)
{
    zomdroid_event_keyboard((int) key, toBool(isPressed));
}

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendCursorPos
  (JNIEnv* /*env*/, jclass /*clazz*/, jdouble x, jdouble y)
{
    zomdroid_event_cursor_pos((double) x, (double) y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendMouseButton
  (JNIEnv* /*env*/, jclass /*clazz*/, jint button, jboolean isPressed)
{
    zomdroid_event_mouse_button((int) button, toBool(isPressed));
}

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickConnected
  (JNIEnv* /*env*/, jclass /*clazz*/)
{
    zomdroid_event_joystick_connected();
}

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickAxis
  (JNIEnv* /*env*/, jclass /*clazz*/, jint axis, jfloat state)
{
    zomdroid_event_joystick_axis((int) axis, (float) state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickDpad
  (JNIEnv* /*env*/, jclass /*clazz*/, jint dpad, jchar state)
{
    // Java 'char' is 16-bit; native expects 8-bit mask (bits: up/right/down/left).
    uint8_t s = (uint8_t) (state & 0xFF);
    zomdroid_event_joystick_dpad((int) dpad, (char) s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickButton
  (JNIEnv* /*env*/, jclass /*clazz*/, jint button, jboolean isPressed)
{
    zomdroid_event_joystick_button((int) button, toBool(isPressed));
}
