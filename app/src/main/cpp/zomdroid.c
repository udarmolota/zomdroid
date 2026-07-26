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
#include <stdio.h>
#include <signal.h>
#include <ucontext.h>
#include <fcntl.h>
#include <unwind.h>
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

// Absolute path to a persistent file that mirrors the game's native stdout/stderr
// (box64 SHOWSEGV/SHOWBT reports, NG [NGG] probes, etc). Unlike logcat it survives the
// crash and app restarts, so diagnostic output always reaches the Bug Report zip.
static char g_native_log_path[1024] = {0};

__attribute__((noreturn))
static void monitor_stdio_and_memory() {
    int pipefd[2];
    char buffer[8192];
    int native_logfd = -1;

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

    if (g_native_log_path[0])
        native_logfd = open(g_native_log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);

    time_t last_mem_check = 0;
    time_t last_mem_log = 0;

    while (1) {
        ssize_t i = read(pipefd[0], buffer, sizeof(buffer) - 1);
        if (i > 0) {
            if (native_logfd >= 0) (void)write(native_logfd, buffer, (size_t)i); // persist raw before strtok
            buffer[i] = '\0';
            // splitting output into individual lines makes it easier for logcat to process and avoids truncation
            char* saveptr;
            char* line = strtok_r(buffer, "\n", &saveptr);
            while (line) {
                LOGI("%s", line);
                line = strtok_r(NULL, "\n", &saveptr);
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

        // Only pause when the pipe is empty. While data keeps arriving (i > 0), loop and keep
        // draining: a fixed 10ms sleep per read caps drain throughput at ~800KB/s and lets the
        // 64KB pipe back up, blocking the emulated game threads in write() (frame hitches).
        if (i <= 0) usleep(10000);
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
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env); // покажет UnsupportedClassVersionError или ClassNotFoundException
            (*env)->ExceptionClear(env);
        }
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

    LOGW("JNI: leaving create_jvm_and_launch_main() WITHOUT DestroyJavaVM");

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

// ---------------------------------------------------------------------------
// Native crash handler.
// The JVM only reports SIGSEGV; box64 clears its own handlers; SIGBUS/SIGILL/
// SIGFPE otherwise go to SIG_DFL and kill the process silently (no hs_err, no
// backtrace) — which is exactly what we saw on the Dimensity NG_GL4ES crash.
// This handler catches those, resolves the fault PC / address to a .so name +
// offset via dladdr, and writes crash.txt into the game dir (bundled by the
// log export). Then it re-raises so the process still dies as before.
// ---------------------------------------------------------------------------
static char g_crash_path[1024] = {0};
static char g_crash_altstack[64 * 1024];

static void cw_str(int fd, const char* s) {
    if (!s) return;
    size_t n = 0; while (s[n]) n++;
    (void)write(fd, s, n);
}

static void cw_hex(int fd, unsigned long v) {
    char buf[18];
    buf[0] = '0'; buf[1] = 'x';
    for (int i = 0; i < 16; i++) {
        int nyb = (int)((v >> ((15 - i) * 4)) & 0xf);
        buf[2 + i] = (char)(nyb < 10 ? ('0' + nyb) : ('a' + nyb - 10));
    }
    (void)write(fd, buf, 18);
}

static void cw_addr(int fd, const char* label, void* addr) {
    cw_str(fd, label);
    cw_hex(fd, (unsigned long)addr);
    Dl_info info;
    if (addr && dladdr(addr, &info) && info.dli_fname) {
        cw_str(fd, "  ");
        cw_str(fd, info.dli_fname);
        cw_str(fd, "+");
        cw_hex(fd, (unsigned long)addr - (unsigned long)info.dli_fbase);
        if (info.dli_sname) { cw_str(fd, " ("); cw_str(fd, info.dli_sname); cw_str(fd, ")"); }
    }
    cw_str(fd, "\n");
}

typedef struct { int fd; int count; int max; } cw_bt_ctx;

static _Unwind_Reason_Code cw_bt_cb(struct _Unwind_Context* ctx, void* arg) {
    cw_bt_ctx* b = (cw_bt_ctx*)arg;
    if (b->count >= b->max) return _URC_END_OF_STACK;
    void* pc = (void*)_Unwind_GetIP(ctx);
    if (pc) {
        cw_str(b->fd, "  #");
        cw_hex(b->fd, (unsigned long)b->count);
        cw_addr(b->fd, " ", pc);
    }
    b->count++;
    return _URC_NO_REASON;
}

static void zomdroid_crash_handler(int sig, siginfo_t* si, void* uctx) {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        cw_str(fd, "=== ZOMDROID NATIVE CRASH ===\n");
        cw_str(fd, "signal: ");
        cw_str(fd, sig == SIGSEGV ? "SIGSEGV" :
                   sig == SIGBUS  ? "SIGBUS"  :
                   sig == SIGILL  ? "SIGILL"  :
                   sig == SIGFPE  ? "SIGFPE"  : "OTHER");
        cw_str(fd, "\n");
        cw_addr(fd, "fault_addr: ", si ? si->si_addr : (void*)0);
        if (uctx) {
            ucontext_t* uc = (ucontext_t*)uctx;
            cw_addr(fd, "pc:         ", (void*)uc->uc_mcontext.pc);
        }
        cw_str(fd, "backtrace:\n");
        cw_bt_ctx b = { fd, 0, 32 };
        _Unwind_Backtrace(cw_bt_cb, &b);
        cw_str(fd, "=== END ===\n");
        close(fd);
    }
    // restore default disposition and re-raise so the process dies as before
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handler(const char* game_dir_path) {
    snprintf(g_crash_path, sizeof(g_crash_path), "%s/crash.txt", game_dir_path);
    // Drop any stale dump from a previous run so an old crash isn't exported as fresh.
    unlink(g_crash_path);

    stack_t ss = {0};
    ss.ss_sp = g_crash_altstack;
    ss.ss_size = sizeof(g_crash_altstack);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    struct sigaction sa = {0};
    sa.sa_sigaction = zomdroid_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    // NOTE: SIGSEGV is deliberately NOT taken here — it is left to Android's debuggerd
    // (see the signal loop above) so a full tombstone is produced. We only back up the
    // signals that would otherwise die silently.
    sigaction(SIGBUS,  &sa, NULL);
    sigaction(SIGILL,  &sa, NULL);
    sigaction(SIGFPE,  &sa, NULL);

    LOGI("crash handler installed (SIGBUS/SIGILL/SIGFPE) -> %s ; SIGSEGV left to debuggerd", g_crash_path);
}

void zomdroid_start_game(const char* game_dir_path, const char* library_dir_path, int jvm_argc,
                         const char** jvm_argv, const char* main_class_name, int argc, const char** argv) {

    signal(SIGABRT, handle_abort);

    // Persist native stdout/stderr to <game>/native.log so box64/NG diagnostic output
    // survives crashes and restarts and reaches the exported Bug Report.
    snprintf(g_native_log_path, sizeof(g_native_log_path), "%s/native.log", game_dir_path);

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

    // We keep our SIGABRT dialog; clear other handlers possibly set by box64.
    // DIAG: leave SIGSEGV at its DEFAULT disposition (do NOT set SIG_IGN). Previously
    // SIG_IGN swallowed every real fault the JVM chained down (UseSignalChaining=true),
    // so driver-thread crashes died silently. With SIG_DFL, Android's debuggerd writes a
    // full tombstone (lib + offset + all-thread backtrace) to logcat -> lastlog.txt.
    struct sigaction sa = { 0 };
    for(int sig = SIGHUP; sig < NSIG; sig++) {
        if(sig == SIGABRT) continue;
        sa.sa_handler = SIG_DFL;   // includes SIGSEGV -> default -> debuggerd tombstone
        sigaction(sig, &sa, NULL);
    }

    // Backup catcher for the signals debuggerd-vs-JVM don't cover here: SIGBUS/SIGILL/SIGFPE
    // -> crash.txt. SIGSEGV is intentionally left to debuggerd (see above).
    install_crash_handler(game_dir_path);

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
    } else if (strcmp(renderer_name, "NG_GL4ES") == 0) {
        g_zomdroid_renderer = NG_GL4ES;
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

// Thread-safe lock-free enqueue using compare-exchange to prevent two threads
// from simultaneously claiming the same slot in the ring buffer.
#define ENQUEUE_EVENT(setup_code)                                                           \
    do {                                                                                    \
        u_char head, next;                                                                  \
        do {                                                                                \
            head = atomic_load_explicit(&g_zomdroid_event_queue.head,                      \
                                        memory_order_relaxed);                              \
            u_char tail = atomic_load_explicit(&g_zomdroid_event_queue.tail,               \
                                               memory_order_acquire);                       \
            next = (head + 1) & EVENT_QUEUE_MAX;                                           \
            if (next == tail) goto enqueue_full;                                            \
        } while (!atomic_compare_exchange_weak_explicit(                                    \
                    &g_zomdroid_event_queue.head,                                           \
                    &head, next,                                                            \
                    memory_order_acquire,                                                   \
                    memory_order_relaxed));                                                 \
        ZomdroidEvent* e = &g_zomdroid_event_queue.buffer[next];                           \
        setup_code                                                                          \
        atomic_thread_fence(memory_order_release);                                         \
        enqueue_full:;                                                                      \
    } while (0)


void zomdroid_event_keyboard(int key, bool isPressed) {
    ENQUEUE_EVENT({
        e->type = KEYBOARD;
        e->keyboard.key = key;
        e->keyboard.is_pressed = isPressed;
    });
}

void zomdroid_event_char(unsigned int codepoint) {
    //LOGI("zomdroid_event_char: codepoint=%u", codepoint);
    ENQUEUE_EVENT({
                      e->type = CHAR_INPUT;
                      e->charInput.codepoint = codepoint;
                      //LOGI("zomdroid_event_char: enqueued at head=%d", next);
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

void zomdroid_event_mouse_scroll(double xoffset, double yoffset) {
    ENQUEUE_EVENT({
          e->type = MOUSE_SCROLL;
          e->mouseScroll.xoffset = xoffset;
          e->mouseScroll.yoffset = yoffset;
    });
}

void zomdroid_event_joystick_connected() {
    ENQUEUE_EVENT({
        e->type = JOYSTICK_CONNECTED;
        // controller is described in GLFW mappings.h
        e->joystickConnected.joystick_name = "Zomdroid Controller";
        e->joystickConnected.joystick_guid = "00000000000000000000000000000000";
        e->joystickConnected.axis_count = 6;
        e->joystickConnected.button_count = 11;
        e->joystickConnected.hat_count = 1;
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
