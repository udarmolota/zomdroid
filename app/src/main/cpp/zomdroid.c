#include <dlfcn.h>
#include <android/dlext.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include <wait.h>
#include "errno.h"
#include "zomdroid_globals.h"
#include <android/native_window.h>
#include "android_linker_ns.h"
#include <malloc.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <bits/stdatomic.h>
#include <sys/sysinfo.h>
#include <asm-generic/fcntl.h>
#include "logger.h"

#define LOG_TAG "zomdroid-main"

struct android_namespace_t* zomdroid_ns;

JavaVM* g_zomdroid_art_vm;
JavaVM* g_zomdroid_jvm;
__thread JNIEnv* g_zomdroid_jni_env;
jvmtiEnv* g_zomdroid_jvmti_env;
jobject g_zomdroid_main_class_loader;
const char* g_zomdroid_vulkan_driver_name;

ZomdroidSurface g_zomdroid_surface = {.mutex = PTHREAD_MUTEX_INITIALIZER,
                                      .ready_for_destroy_cond = PTHREAD_COND_INITIALIZER};

Renderer g_zomdroid_renderer;

ZomdroidEventQueue g_zomdroid_event_queue;

static long get_mem_available_mb() {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return -1;

    char line[256];
    long memAvailableKb = -1;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemAvailable: %ld kB", &memAvailableKb) == 1) {
            break;
        }
    }
    fclose(f);

    return (memAvailableKb > 0) ? (memAvailableKb / 1024) : -1;
}

__attribute__((noreturn))
static void monitor_stdio_and_memory() {
    int pipefd[2];
    char buffer[8192];

    if (pipe(pipefd) == -1) {
        LOGE("Failed to create pipe for monitoring stdio");
        abort();
    }

    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    close(pipefd[1]);

    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);

    time_t last_mem_check = 0;
    time_t last_mem_log = 0;

    while (1) {
        ssize_t i = read(pipefd[0], buffer, sizeof(buffer) - 1);
        if (i > 0) {
            buffer[i] = '\0';
            // splitting output into individual lines makes it easier for logcat to process and avoids truncation
            char* line = strtok(buffer, "\n");
            while (line) {
                LOGI("%s", line);
                line = strtok(NULL, "\n");
            }
        }

        time_t now = time(NULL);
        if ((now - last_mem_check >= 1) && (now - last_mem_log >= 30)) {
            last_mem_check = now;

            long free_mb = get_mem_available_mb();
            if (free_mb != -1 && free_mb < 300) {
                last_mem_log = now;
                LOGW("Low memory: only %ld MB available", free_mb);
            }
        }

        usleep(10000);
    }
}

void zomdroid_set_art_vm(void* vm) {
    g_zomdroid_art_vm = vm;
}

_Noreturn void handle_abort() {
    signal(SIGABRT, SIG_DFL);

    JNIEnv* jni_env = NULL;
    (*g_zomdroid_art_vm)->AttachCurrentThread(g_zomdroid_art_vm, (void**)&jni_env, NULL);
    if (jni_env == NULL) _exit(1);

    jclass handler_class = (*jni_env)->FindClass(jni_env, "com/zomdroid/CrashHandler");
    if (handler_class == NULL) _exit(1);

    jmethodID handler_method = (*jni_env)->GetStaticMethodID(jni_env, handler_class, "handleAbort", "()V");
    if (handler_method == NULL) _exit(1);

    (*jni_env)->CallStaticVoidMethod(jni_env, handler_class, handler_method);

    pause();
    _exit(1);
}

static void create_jvm_and_launch_main(int jvm_argc, const char** jvm_argv, const char* main_class_name, int argc, const char** argv) {
    void* libjvm = linkernsbypass_namespace_dlopen("libjvm.so", RTLD_GLOBAL, zomdroid_ns);
    if (libjvm == NULL) {
        LOGE("%s", dlerror());
        return;
    }

    jint(*JNI_CreateJavaVM)(JavaVM**, void**, void*) = dlsym(libjvm, "JNI_CreateJavaVM");

    JavaVM* jvm;
    JNIEnv* env;
    JavaVMInitArgs vm_args;
    JavaVMOption options[jvm_argc];
    if (jvm_argv != NULL) {
        for (int i = 0; i < jvm_argc; i++) {
            options[i].optionString = (char*) jvm_argv[i];
        }
    }
    vm_args.version = JNI_VERSION_1_6;
    vm_args.options = options;
    vm_args.nOptions = jvm_argc;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    jint res = JNI_CreateJavaVM(&jvm, (void**)&env, &vm_args);
    if (res != JNI_OK) {
        LOGE("Failed to create JVM, error code: %d", res);
        return;
    }

    jvmtiEnv* jvmtiEnv = NULL;
    res = (*jvm)->GetEnv(jvm, (void**)&jvmtiEnv, JVMTI_VERSION_11);
    if (res != JNI_OK) {
        LOGE("Failed to create JVM TI connection, error code: %d", res);
        return;
    }
    g_zomdroid_jvmti_env = jvmtiEnv;

    jvmtiError err;
//    jvmtiCapabilities potentialCaps;
//    err = (*jvmtiEnv)->GetPotentialCapabilities(jvmtiEnv, &potentialCaps);
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to get potential capabilities for JVM TI env, error code: %d", err);
//        return;
//    }
//    if (!potentialCaps.can_generate_native_method_bind_events) {
//        LOGE("JVM TI env doesn't have a required potential capability: can_generate_native_method_bind_events");
//        return;
//    }
//
//    jvmtiCapabilities caps = { 0 };
//    caps.can_generate_native_method_bind_events = 1;
//    err = (*jvmtiEnv)->AddCapabilities(jvmtiEnv, &caps);
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to add necessary capabilities to JVM TI env, error code: %d", err);
//        return;
//    }
//
//
//    jvmtiEventCallbacks callbacks;
//    callbacks.NativeMethodBind = &onNativeMethodBind;
//    err = (*jvmtiEnv)->SetEventCallbacks(jvmtiEnv, &callbacks, sizeof(callbacks));
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to set event callbacks for JVM TI env, error code: %d", err);
//        return;
//    }
//
//    err = (*jvmtiEnv)->SetEventNotificationMode(jvmtiEnv, JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, NULL);
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to enable NATIVE_METHOD_BIND event for JVM TI env, error code: %d", err);
//        return;
//    }

    g_zomdroid_jvm = jvm;

    jclass main_class = (*env)->FindClass(env, main_class_name);
    if (main_class == NULL) {
        LOGE("Failed to load main class");
        goto FINISH;
    }

    jobject classLoader = NULL;
    if ((err = (*jvmtiEnv)->GetClassLoader(jvmtiEnv, main_class, &classLoader)) != JVMTI_ERROR_NONE) {
        LOGE("GetClassLoader() failed, error code %d", err);
        goto FINISH;
    }
    g_zomdroid_main_class_loader = (*env)->NewGlobalRef(env, classLoader);

    jmethodID main_method = (*env)->GetStaticMethodID(env, main_class, "main", "([Ljava/lang/String;)V");
    if (main_method == NULL) {
        LOGE("Failed to locate main method");
        goto FINISH;
    }

    jobjectArray main_class_args = (*env)->NewObjectArray(env, argc, (*env)->FindClass(env, "java/lang/String"), NULL);

    if (argv != NULL) {
        for (int i = 0; i < argc; i++) {
            jstring arg_string = (*env)->NewStringUTF(env, argv[i]);
            (*env)->SetObjectArrayElement(env, main_class_args, i, arg_string);
        }
    }

    (*env)->CallStaticVoidMethod(env, main_class, main_method, main_class_args);

    FINISH:
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        abort();
    }

    (*jvm)->DestroyJavaVM(jvm);
}

static int init_zomdroid_namespace(const char* ld_library_path) {
    if (!linkernsbypass_load_status()) {
        LOGE("linkernsbypass is not loaded");
        return -1;
    }

    zomdroid_ns = android_create_namespace("zomdroid-ns", ld_library_path, ld_library_path,
                                           ANDROID_NAMESPACE_TYPE_SHARED,
                                           NULL, NULL);
    return 0;
}

static int load_linker_hook() {
    void* zomdroid_linker = linkernsbypass_namespace_dlopen("libzomdroidlinker.so", RTLD_LOCAL, zomdroid_ns);
    if (!zomdroid_linker) {
        LOGE("%s", dlerror());
        return -1;
    }
    void (*zomdroid_linker_set_proc_addrs)(void*, void*, void*) =
            dlsym(zomdroid_linker, "zomdroid_linker_set_proc_addrs");
    int (*zomdroid_linker_init)() =
            dlsym(zomdroid_linker, "zomdroid_linker_init");
    void (*zomdroid_linker_set_vulkan_loader_handle)(void*) =
            dlsym(zomdroid_linker, "zomdroid_linker_set_vulkan_loader_handle");
    void (*zomdroid_linker_set_vulkan_driver_handle)(void*) =
            dlsym(zomdroid_linker, "zomdroid_linker_set_vulkan_driver_handle");
    if (!zomdroid_linker_init || !zomdroid_linker_set_proc_addrs ||
            !zomdroid_linker_set_vulkan_loader_handle || !zomdroid_linker_set_vulkan_driver_handle) {
        dlerror();
        LOGE("Failed to locate symbols for libzomdroidlinker.so");
        return -1;
    }

    void* libdl = dlopen("libdl.so", RTLD_LAZY);
    if (!libdl) {
        LOGE("%s", dlerror());
        return -1;
    }
    void* _loader_dlopen_fn = dlsym(libdl, "__loader_dlopen");
    void* _loader_dlsym_fn = dlsym(libdl, "__loader_dlsym");
    void* _loader_android_dlopen_ext_fn = dlsym(libdl, "__loader_android_dlopen_ext");
    if (!_loader_dlopen_fn || !_loader_dlsym_fn || ! _loader_android_dlopen_ext_fn) {
        dlclose(libdl);
        LOGE("Failed to locate symbols for libdl.so");
        return -1;
    }

    zomdroid_linker_set_proc_addrs(_loader_dlopen_fn, _loader_dlsym_fn, _loader_android_dlopen_ext_fn);
    if (zomdroid_linker_init() != 0 ) {
        LOGE("Failed to initialise zomdroid linker");
        return -1;
    }

    if (g_zomdroid_vulkan_driver_name != NULL) {
        void* vulkan_loader = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so",
                                                               getenv("ZOMDROID_CACHE_DIR"), RTLD_LOCAL, zomdroid_ns);
        if (!vulkan_loader) {
            LOGE("%s", dlerror());
            return -1;
        }
        zomdroid_linker_set_vulkan_loader_handle(vulkan_loader);

        void* vulkan_driver = linkernsbypass_namespace_dlopen(g_zomdroid_vulkan_driver_name, RTLD_LOCAL, zomdroid_ns);
        if (!vulkan_driver) {
            LOGE("%s", dlerror());
            return -1;
        }
        zomdroid_linker_set_vulkan_driver_handle(vulkan_driver);
    }

    return 0;
}

void zomdroid_start_game(const char* game_dir_path, const char* library_dir_path, int jvm_argc,
                         const char** jvm_argv, const char* main_class_name, int argc, const char** argv) {

    signal(SIGABRT, handle_abort);

    pthread_t logging_thread;
    if (pthread_create(&logging_thread, NULL, (void *(*)(void *)) &monitor_stdio_and_memory, NULL) != 0) {
        LOGW("Failed to create stdout logging thread");
    } else {
        pthread_detach(logging_thread);
    }

    if (init_zomdroid_namespace(library_dir_path) != 0) {
        LOGE("Failed to initialize zomdroid namespace");
        return;
    }

    if (load_linker_hook() != 0) {
        LOGE("Failed to load linker hook");
        return;
    }

    if (chdir(game_dir_path) != 0) {
        LOGE("Failed to change cwd with error: %s", strerror(errno));
        return;
    }

    // we handle abort, jvm handles segfault, clear other handlers possibly set by box64
    struct sigaction sa = { 0 };
    for(int sig = SIGHUP; sig < NSIG; sig++) {
        if(sig == SIGSEGV) sa.sa_handler = SIG_IGN;
        else if(sig == SIGABRT) continue;
        else sa.sa_handler = SIG_DFL;
        sigaction(sig, &sa, NULL);
    }

    create_jvm_and_launch_main(jvm_argc, jvm_argv, main_class_name, argc, argv);
}


void zomdroid_deinit() {

}

int zomdroid_init() {
    const char* renderer_name = getenv("ZOMDROID_RENDERER");
    if (renderer_name == NULL) {
        LOGE("Renderer env var is not set");
        exit(1);
    } else if (strcmp(renderer_name, "ZINK_ZFA") == 0) {
        g_zomdroid_renderer = ZINK_ZFA;
    } else if (strcmp(renderer_name, "ZINK_OSMESA") == 0) {
        g_zomdroid_renderer = ZINK_OSMESA;
    } else if (strcmp(renderer_name, "GL4ES") == 0) {
        g_zomdroid_renderer = GL4ES;
    } else {
        LOGE("Unrecognized renderer %s", renderer_name);
        exit(1);
    }
    g_zomdroid_vulkan_driver_name = getenv("ZOMDROID_VULKAN_DRIVER_NAME");
    return 0;
}

/*void jvm_pause_all_threads() {
    jint thread_count;
    jthread* threads;
    jvmtiEnv* jvmti = g_zomdroid_jvmti_env;
    jvmtiError err;
    if ((err = (*jvmti)->GetAllThreads(jvmti, &thread_count, &threads)) != JVMTI_ERROR_NONE) {
        LOGE("GetAllThreads() failed, error code: %d", err);
        return;
    }
    (*jvmti)->SuspendThreadList(jvmti, thread_count, threads, &err);
    if (err != JVMTI_ERROR_NONE) {
        LOGE("SuspendThreadList() failed, error code: %d", err);
        return;
    }
}
void jvm_resume_all_threads() {
    jint thread_count;
    jthread* threads;
    jvmtiEnv* jvmti = g_zomdroid_jvmti_env;
    jvmtiError err;
    if ((err = (*jvmti)->GetAllThreads(jvmti, &thread_count, &threads)) != JVMTI_ERROR_NONE) {
        LOGE("GetAllThreads() failed, error code: %d", err);
        return;
    }
    (*jvmti)->ResumeThreadList(jvmti, thread_count, threads, &err);
    if (err != JVMTI_ERROR_NONE) {
        LOGE("SuspendThreadList() failed, error code: %d", err);
        return;
    }
}*/

void zomdroid_surface_deinit() {
    pthread_mutex_lock(&g_zomdroid_surface.mutex);
    g_zomdroid_surface.native_window = NULL;
    g_zomdroid_surface.width = 0;
    g_zomdroid_surface.height = 0;
    if (g_zomdroid_surface.is_used)
        g_zomdroid_surface.is_dirty = true;

    if (g_zomdroid_surface.is_used)
        pthread_cond_wait(&g_zomdroid_surface.ready_for_destroy_cond, &g_zomdroid_surface.mutex);

    pthread_mutex_unlock(&g_zomdroid_surface.mutex);
}

void zomdroid_surface_init(ANativeWindow* wnd, int width, int height) {
    pthread_mutex_lock(&g_zomdroid_surface.mutex);

    if (g_zomdroid_surface.native_window != NULL && g_zomdroid_surface.native_window != wnd) {
        LOGW("Called init on already initialized surface");
    }
    g_zomdroid_surface.native_window = wnd;
    g_zomdroid_surface.width = width;
    g_zomdroid_surface.height = height;

    if (g_zomdroid_surface.is_used)
        g_zomdroid_surface.is_dirty = true;

    pthread_mutex_unlock(&g_zomdroid_surface.mutex);
}

#define ENQUEUE_EVENT(setup_code)                                     \
    do {                                                              \
        u_char head = atomic_load_explicit(&g_zomdroid_event_queue.head, memory_order_relaxed);  \
        u_char tail = atomic_load_explicit(&g_zomdroid_event_queue.tail, memory_order_acquire);  \
        u_char next = (head + 1) & EVENT_QUEUE_MAX;                   \
        if (next == tail) {                                           \
            break;                                                    \
        }                                                             \
        ZomdroidEvent* e = &g_zomdroid_event_queue.buffer[next];      \
        setup_code                                                    \
        atomic_store_explicit(&g_zomdroid_event_queue.head, next, memory_order_release); \
    } while (0)


void zomdroid_event_keyboard(int key, bool isPressed) {
    ENQUEUE_EVENT({
        e->type = KEYBOARD;
        e->keyboard.key = key;
        e->keyboard.is_pressed = isPressed;
    });
}

void zomdroid_event_cursor_pos(double x, double y) {
    ENQUEUE_EVENT({
        e->type = CURSOR_POS;
        e->cursorPos.x = x;
        e->cursorPos.y = y;
    });
}

void zomdroid_event_mouse_button(int button, bool isPressed) {
    ENQUEUE_EVENT({
        e->type = MOUSE_BUTTON;
        e->mouseButton.button = button;
        e->mouseButton.is_pressed = isPressed;
    });
}

void zomdroid_event_joystick_connected() {
    ENQUEUE_EVENT({
        e->type = JOYSTICK_CONNECTED;
        // controller is described in GLFW mappings.h
        e->joystickConnected.joystick_name = "Zomdroid Controller";
        e->joystickConnected.joystick_guid = "00000000000000000000000000000000";
        e->joystickConnected.axis_count = 6;
        e->joystickConnected.button_count = 15;
        e->joystickConnected.hat_count = 0;
    });
}

void zomdroid_event_joystick_axis(int axis, float state) {
    ENQUEUE_EVENT({
        e->type = JOYSTICK_AXIS;
        e->joystickAxis.axis = axis;
        e->joystickAxis.state = state;
    });
}

void zomdroid_event_joystick_dpad(int dpad, char state) {
    ENQUEUE_EVENT({
        e->type = JOYSTICK_DPAD;
        e->joystickDpad.dpad = dpad;
        e->joystickDpad.state = state;
    });
}

void zomdroid_event_joystick_button(int button, bool is_pressed) {
    ENQUEUE_EVENT({
        e->type = JOYSTICK_BUTTON;
        e->joystickButton.button = button;
        e->joystickButton.is_pressed = is_pressed;
    });
}
