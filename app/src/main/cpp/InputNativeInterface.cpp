// app/src/main/cpp/InputNativeInterface.cpp
#include <jni.h>
#include <android/log.h>

// Include GLFW public and internal headers.
// Adjust include paths in CMakeLists.txt if needed.
#include <GLFW/glfw3.h>
#include "glfw/src/internal.h"

#define LOG_TAG "JNI-Input"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// Java signature: com.zomdroid.input.InputNativeInterface.sendJoystickDpad(int, char)
extern "C"
JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickDpad(JNIEnv* /*env*/,
                                                              jclass /*clazz*/,
                                                              jint hatIndex,
                                                              jchar state16)
{
    // Only use the low nibble: 0x01 up, 0x02 right, 0x04 down, 0x08 left
    const char state = (char)(state16 & 0x0F);

    // Send to the first connected joystick (same policy as other input paths)
    for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; ++jid)
    {
        _GLFWjoystick* js = &_glfw.joysticks[jid];
        if (!js->connected)
            continue;

        if (js->hatCount <= 0)
        {
            // Nothing to do; platform-side allocation did not expose any HATs.
            LOGW("DPAD ignored: hatCount==0 for jid=%d", jid);
            continue;
        }

        const int idx = (hatIndex >= 0 && hatIndex < js->hatCount) ? (int)hatIndex : 0;
        _glfwInputJoystickHat(js, idx, state);
        break; // done for the first connected joystick
    }
}
