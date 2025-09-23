#include <jni.h>
#include "zomdroid.h"
#include <stdlib.h>
#include <string.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <unistd.h>
#include "logger.h"
#define LOG_TAG "zomdroid-jni"


JNIEXPORT void JNICALL
Java_com_zomdroid_GameLauncher_startGame(JNIEnv *env, jobject clazz, jstring j_game_dir_path, jstring j_library_dir_path, jobjectArray j_jvm_args, jstring j_main_class_name, jobjectArray j_args) {
     JavaVM* jvm = NULL;
     (*env)->GetJavaVM(env, &jvm);
     zomdroid_set_art_vm(jvm);

    const char* game_dir_path = (*env)->GetStringUTFChars(env, j_game_dir_path, NULL);
    const char* library_dir_path = (*env)->GetStringUTFChars(env, j_library_dir_path, NULL);
    const char* main_class_name = (*env)->GetStringUTFChars(env, j_main_class_name, NULL);

    int jvm_argc = (*env)->GetArrayLength(env, j_jvm_args);
    char** jvm_argv = NULL;
    if (jvm_argc > 0) {
        jvm_argv = malloc(jvm_argc * sizeof(char*));
        for (int i = 0; i < (*env)->GetArrayLength(env, j_jvm_args); i++) {
            jstring arg_string = (*env)->GetObjectArrayElement(env, j_jvm_args, i);
            const char* arg = (*env)->GetStringUTFChars(env, arg_string, NULL);
            jvm_argv[i] = strdup(arg);
            (*env)->ReleaseStringUTFChars(env, arg_string, arg);
        }
    }

    int argc = (*env)->GetArrayLength(env, j_args);
    char** argv = NULL;
    if (argc > 0) {
        argv = malloc(argc * sizeof(char*));
        for (int i = 0; i < (*env)->GetArrayLength(env, j_args); i++) {
            jstring arg_string = (*env)->GetObjectArrayElement(env, j_args, i);
            const char* arg = (*env)->GetStringUTFChars(env, arg_string, NULL);
            argv[i] = strdup(arg);
            (*env)->ReleaseStringUTFChars(env, arg_string, arg);
        }
    }

    zomdroid_start_game(game_dir_path, library_dir_path, jvm_argc, (const char **) jvm_argv,
                        main_class_name, argc,
                        (const char **) argv);

    (*env)->ReleaseStringUTFChars(env, j_game_dir_path, game_dir_path);
    (*env)->ReleaseStringUTFChars(env, j_library_dir_path, library_dir_path);
    (*env)->ReleaseStringUTFChars(env, j_main_class_name, main_class_name);

    if (argv != NULL) {
        for (int i = 0; i < argc; i++) {
            free(argv[i]);
        }
        free(argv);
    }

    if (jvm_argv != NULL) {
        for (int i = 0; i < jvm_argc; i++) {
            free(jvm_argv[i]);
        }
        free(jvm_argv);
    }
}

JNIEXPORT void JNICALL
Java_com_zomdroid_GameLauncher_destroyZomdroidWindow(JNIEnv *env, jobject clazz) {
    zomdroid_deinit();
}

JNIEXPORT jint JNICALL
Java_com_zomdroid_GameLauncher_initZomdroidWindow(JNIEnv *env, jobject clazz) {
    return zomdroid_init();
}

JNIEXPORT void JNICALL
Java_com_zomdroid_GameLauncher_destroySurface(JNIEnv *env, jobject clazz) {
    zomdroid_surface_deinit();
}

JNIEXPORT jint JNICALL
Java_com_zomdroid_GameLauncher_setSurface(JNIEnv *env, jobject clazz, jobject surface, jint width, jint height) {
    ANativeWindow* wnd = ANativeWindow_fromSurface(env, surface);
    zomdroid_surface_init(wnd, width, height);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendKeyboard(JNIEnv *env, jclass clazz, jint key,
                                                          jboolean is_pressed) {
    zomdroid_event_keyboard(key, is_pressed);
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendCursorPos(JNIEnv *env, jobject clazz, jdouble x, jdouble y) {
    zomdroid_event_cursor_pos(x, y);
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendMouseButton(JNIEnv *env, jobject clazz, jint button, jboolean isPressed) {
    zomdroid_event_mouse_button(button, isPressed);
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickAxis(JNIEnv *env, jclass clazz, jint axis, jfloat state) {
    zomdroid_event_joystick_axis(axis, state);
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickAxis(JNIEnv *env, jclass clazz, jint axis, jfloat state) {
    zomdroid_event_joystick_axis(axis, state);
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickButton(JNIEnv *env, jclass clazz, jint button, jboolean pressed) {
    zomdroid_event_joystick_button(button, pressed);
}

JNIEXPORT void JNICALL
Java_com_zomdroid_input_InputNativeInterface_sendJoystickConnected(JNIEnv *env, jclass clazz) {
    zomdroid_event_joystick_connected();
}
