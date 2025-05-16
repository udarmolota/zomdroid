#ifndef ZOMDROID_ZOMDROID_GLOBALS_H
#define ZOMDROID_ZOMDROID_GLOBALS_H

#include "android/native_window.h"
#include "openjdk/jni.h"
#include "openjdk/jvmti.h"
#include "stdatomic.h"

typedef enum {
    KEYBOARD,
    CURSOR_POS,
    MOUSE_BUTTON,
    JOYSTICK_CONNECTED,
    JOYSTICK_DISCONNECTED,
    JOYSTICK_AXIS,
    JOYSTICK_DPAD,
    JOYSTICK_BUTTON
} EventType;

typedef enum {
    GL4ES,
    ZINK_OSMESA,
    ZINK_ZFA
} Renderer;

typedef struct {
    EventType type;
    int key;
    bool is_pressed;
} KeyboardEvent;

typedef struct {
    EventType type;
    double x;
    double y;
} CursorPosEvent;

typedef struct {
    EventType type;
    int button;
    bool is_pressed;
} MouseButtonEvent;

typedef struct {
    EventType type;
    const char* joystick_name;
    const char* joystick_guid;
    int axis_count;
    int button_count;
    int hat_count;
} JoystickConnectedEvent;

typedef struct {
    EventType type;
} JoystickDisconnectedEvent;

typedef struct {
    EventType type;
    int axis;
    float state;
} JoystickAxisEvent;

typedef struct {
    EventType type;
    int dpad;
    char state;
} JoystickDpadEvent;

typedef struct {
    EventType type;
    int button;
    bool is_pressed;
} JoystickButtonEvent;

typedef union {
    EventType type;
    KeyboardEvent keyboard;
    CursorPosEvent cursorPos;
    MouseButtonEvent mouseButton;
    JoystickConnectedEvent joystickConnected;
    JoystickDisconnectedEvent joystickDisconnected;
    JoystickAxisEvent joystickAxis;
    JoystickDpadEvent joystickDpad;
    JoystickButtonEvent joystickButton;
} ZomdroidEvent;

#define EVENT_QUEUE_MAX 255
typedef struct {
    ZomdroidEvent buffer[EVENT_QUEUE_MAX + 1];
    atomic_uchar head;
    atomic_uchar tail;
} ZomdroidEventQueue;

extern ZomdroidEventQueue g_zomdroid_event_queue;

extern JavaVM* g_zomdroid_art_vm;
extern JavaVM* g_zomdroid_jvm;
extern __thread JNIEnv* g_zomdroid_jni_env;
extern jvmtiEnv* g_zomdroid_jvmti_env;
extern jobject g_zomdroid_main_class_loader;
extern Renderer g_zomdroid_renderer;
extern const char* g_zomdroid_vulkan_driver_name;

typedef struct {
    ANativeWindow* native_window;
    int width;
    int height;
    /** Used by GLFW for tracking surface changes and sync */
    bool is_dirty;
    /** Set once by GLFW when game render thread calls init. Until this value is set
     * GLFW is not interested in surface changes */
    bool is_used;
    pthread_mutex_t mutex;
    /** If surface is used, Android UI thread should wait for this condition before destroying native window */
    pthread_cond_t ready_for_destroy_cond;
} ZomdroidSurface;
extern ZomdroidSurface g_zomdroid_surface;

#endif //ZOMDROID_ZOMDROID_GLOBALS_H
