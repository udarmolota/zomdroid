#include <jni.h>

extern "C"
JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_1sendJoystickDpad(
        JNIEnv* /*env*/, jclass /*clazz*/, jint /*dpad*/, jchar /*state*/) {
    // no-op
}
