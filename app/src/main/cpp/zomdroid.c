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
#include "zomdroid_perf.h"

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

atomic_ullong g_zomdroid_presented_frames;

/** Every renderer presents through glfwSwapBuffers, which calls this after each frame. */
void zomdroid_frame_presented(void) {
    atomic_fetch_add_explicit(&g_zomdroid_presented_frames, 1, memory_order_relaxed);
    zomdroid_perf_frame();
}

// Same routing as [ZMEM]: stdout lands in native.log in timeline order, except in the coop
// process, where fd 1 carries the CoopSlave protocol.
static void perf_emit(const char* line) {
    if (getenv("ZOMDROID_COOP_OUTPUT")) LOGI("%s", line);
    else printf("%s\n", line);
}

unsigned long long zomdroid_presented_frames() {
    return atomic_load_explicit(&g_zomdroid_presented_frames, memory_order_relaxed);
}

atomic_bool g_zomdroid_mouse_captured;

bool zomdroid_mouse_captured() {
    return atomic_load_explicit(&g_zomdroid_mouse_captured, memory_order_relaxed);
}

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

// Our own memory footprint, sampled periodically into native.log.
//
// Performance reports on the stock GL4ES renderer arrive with no telemetry at all: only NG_GL4ES
// writes gl_trace.txt, so for Build 41 there was nothing to read but the reporter's description.
// RSS and swap answer most of what those reports need ("is it memory?") and are cheap to obtain
// from the kernel, without touching the GL layer - so they are sampled here rather than patched
// into gl4es, and they cover every renderer for free.
static void get_self_mem_mb(long* rss_mb, long* swap_mb) {
    *rss_mb = -1;
    *swap_mb = -1;

    // statm's second field is the resident set size in pages.
    FILE* f = fopen("/proc/self/statm", "r");
    if (f) {
        long size_pages = 0, rss_pages = 0;
        if (fscanf(f, "%ld %ld", &size_pages, &rss_pages) == 2) {
            long page_kb = sysconf(_SC_PAGESIZE) / 1024;
            if (page_kb > 0) *rss_mb = (rss_pages * page_kb) / 1024;
        }
        fclose(f);
    }

    // Swap is not in statm; ZRAM makes it the difference between "large" and "being evicted".
    f = fopen("/proc/self/status", "r");
    if (f) {
        char line[256];
        long swap_kb = -1;
        while (fgets(line, sizeof(line), f)) {
            if (sscanf(line, "VmSwap: %ld kB", &swap_kb) == 1) break;
        }
        fclose(f);
        if (swap_kb >= 0) *swap_mb = swap_kb / 1024;
    }
}

// Who holds the memory, by mapping kind, from /proc/self/smaps. [ZMEM] only gives the total,
// and a footprint that runs away during loading (seen with PanVK: rss 0.4 -> 4.9 GB in three
// minutes) can be the GPU driver, malloc (box64, LWJGL, Mesa) or the JVM. Reading smaps walks
// every mapping, so this runs only when rss+swap first crosses each MEM_MAP_THRESHOLDS_MB step,
// a handful of times per run, not on the 30 s timer.
enum { MEM_GPU, MEM_MALLOC, MEM_ART, MEM_ANON, MEM_FILES, MEM_OTHER, MEM_KINDS };
static const char* const MEM_KIND_NAMES[MEM_KINDS] = {"gpu", "malloc", "art", "anon", "files", "other"};
static const long MEM_MAP_THRESHOLDS_MB[] = {2048, 3072, 4096, 5120, 6144};
#define MEM_MAP_TOP 3

static int mem_kind_of(const char* name) {
    // Mali (kbase, also used by PanVK here), Adreno, DRM render nodes, and buffers shared with
    // the compositor or the GPU.
    if (strncmp(name, "/dev/mali", 9) == 0 || strncmp(name, "/dev/kgsl", 9) == 0
        || strncmp(name, "/dev/dri", 8) == 0 || strstr(name, "dmabuf") || strstr(name, "dma_buf"))
        return MEM_GPU;
    if (strncmp(name, "[anon:scudo", 11) == 0 || strncmp(name, "[anon:libc_malloc", 17) == 0
        || strncmp(name, "[anon:jemalloc", 14) == 0 || strcmp(name, "[heap]") == 0)
        return MEM_MALLOC;
    if (strncmp(name, "[anon:dalvik", 12) == 0) return MEM_ART;
    // Unnamed anonymous memory: the HotSpot heap and metaspace, box64's own allocations, and
    // any mmap a library did not name.
    if (name[0] == '\0' || strncmp(name, "[anon:", 6) == 0) return MEM_ANON;
    if (name[0] == '/') return MEM_FILES;
    return MEM_OTHER;
}

static void mem_map_emit(long rss_mb, long swap_mb, long threshold_mb) {
    FILE* f = fopen("/proc/self/smaps", "r");
    if (!f) return;

    long rss_kb[MEM_KINDS] = {0}, swap_kb[MEM_KINDS] = {0};
    long top_kb[MEM_MAP_TOP] = {0};
    char top_name[MEM_MAP_TOP][64] = {{0}};
    int mappings = 0;

    char line[512];
    char name[256] = "";
    int kind = MEM_OTHER;
    while (fgets(line, sizeof(line), f)) {
        long kb;
        if (sscanf(line, "Rss: %ld kB", &kb) == 1) {
            rss_kb[kind] += kb;
            // Largest single mappings: a lone multi-GB block tells "one big allocation" from
            // "thousands of small ones" without listing them all.
            for (int i = 0; i < MEM_MAP_TOP; i++) {
                if (kb <= top_kb[i]) continue;
                for (int j = MEM_MAP_TOP - 1; j > i; j--) {
                    top_kb[j] = top_kb[j - 1];
                    memcpy(top_name[j], top_name[j - 1], sizeof(top_name[j]));
                }
                top_kb[i] = kb;
                snprintf(top_name[i], sizeof(top_name[i]), "%s", name[0] ? name : "anon");
                break;
            }
        } else if (sscanf(line, "Swap: %ld kB", &kb) == 1) {
            swap_kb[kind] += kb;
        } else if (strchr(line, '-') && line[0] != ' ' && (line[0] < 'A' || line[0] > 'Z')) {
            // Mapping header: "start-end perms offset dev inode [name]"; field lines start with
            // a capitalised key ("Rss:", "VmFlags:"), headers with a lower-case hex address.
            mappings++;
            const char* p = line;
            for (int field = 0; field < 5 && *p; field++) {
                while (*p && *p != ' ') p++;
                while (*p == ' ') p++;
            }
            snprintf(name, sizeof(name), "%s", p);
            size_t n = strlen(name);
            while (n > 0 && (name[n - 1] == '\n' || name[n - 1] == ' ')) name[--n] = '\0';
            kind = mem_kind_of(name);
        }
    }
    fclose(f);

    char out[640];
    int len = snprintf(out, sizeof(out), "[ZMEM-MAP] past %ldMB: rss=%ldMB swap=%ldMB mappings=%d |",
                       threshold_mb, rss_mb, swap_mb, mappings);
    for (int k = 0; k < MEM_KINDS && len < (int)sizeof(out); k++)
        len += snprintf(out + len, sizeof(out) - len, " %s=%ld+%ldMB", MEM_KIND_NAMES[k],
                        rss_kb[k] / 1024, swap_kb[k] / 1024);
    if (len < (int)sizeof(out)) len += snprintf(out + len, sizeof(out) - len, " | largest:");
    for (int i = 0; i < MEM_MAP_TOP && top_kb[i] > 0 && len < (int)sizeof(out); i++)
        len += snprintf(out + len, sizeof(out) - len, " %ldMB %s", top_kb[i] / 1024, top_name[i]);
    perf_emit(out);
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
    time_t last_mem_sample = 0;
    time_t last_perf_sample = 0;
    time_t last_mem_map_check = 0;
    int next_mem_map = 0;

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

        if (now - last_perf_sample >= 10) {
            last_perf_sample = now;
            zomdroid_perf_sample(perf_emit);
        }

        // statm and status are cheap; the smaps breakdown runs once per threshold crossed.
        if (next_mem_map < (int)(sizeof(MEM_MAP_THRESHOLDS_MB) / sizeof(MEM_MAP_THRESHOLDS_MB[0]))
                && now - last_mem_map_check >= 2) {
            last_mem_map_check = now;
            long rss_mb = -1, swap_mb = -1;
            get_self_mem_mb(&rss_mb, &swap_mb);
            long total_mb = rss_mb + (swap_mb > 0 ? swap_mb : 0);
            if (rss_mb >= 0 && total_mb >= MEM_MAP_THRESHOLDS_MB[next_mem_map]) {
                long threshold_mb = MEM_MAP_THRESHOLDS_MB[next_mem_map];
                // A jump over several steps at once is reported once, at the highest step passed.
                while (next_mem_map < (int)(sizeof(MEM_MAP_THRESHOLDS_MB) / sizeof(MEM_MAP_THRESHOLDS_MB[0]))
                       && total_mb >= MEM_MAP_THRESHOLDS_MB[next_mem_map])
                    threshold_mb = MEM_MAP_THRESHOLDS_MB[next_mem_map++];
                mem_map_emit(rss_mb, swap_mb, threshold_mb);
            }
        }

        // Printed to stdout on purpose: this loop is what mirrors stdout into native.log, so the
        // sample lands in the bug report next to the renderer's own output, in timeline order.
        if (now - last_mem_sample >= 30) {
            last_mem_sample = now;

            long rss_mb = -1, swap_mb = -1;
            get_self_mem_mb(&rss_mb, &swap_mb);
            if (rss_mb >= 0) {
                if (getenv("ZOMDROID_COOP_OUTPUT")) {
                    // fd 1 carries CoopSlave protocol; diagnostics must not enter it.
                    LOGI("[ZMEM] rss=%ldMB swap=%ldMB avail=%ldMB",
                         rss_mb, swap_mb, get_mem_available_mb());
                } else {
                    printf("[ZMEM] rss=%ldMB swap=%ldMB avail=%ldMB\n",
                           rss_mb, swap_mb, get_mem_available_mb());
                }
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

/*
 * The private libvulkan.so copy and a custom driver load into zomdroid-ns, which sees only our own
 * libraries and /system/lib64. Some phones' libvulkan.so needs a library from elsewhere
 * (libgpud_sys.so on Android 16 MediaTek Xiaomi, 2026-10). Share just the library the error names
 * with zomdroid-ns - from the default namespace and from the vendor's sphal - so nothing else
 * box64, the JRE or the game resolve changes. Returns false when there is nothing new to share.
 */
static bool share_missing_library(const char* error) {
    static char last[256];
    const char* start = error ? strstr(error, "library \"") : NULL;
    if (!start) return false;
    start += strlen("library \"");
    const char* end = strchr(start, '"');
    if (!end || end == start || end - start >= (long) sizeof(last)) return false;
    char name[sizeof(last)];
    memcpy(name, start, end - start);
    name[end - start] = '\0';
    if (strcmp(name, last) == 0) return false; // already shared, and it did not help
    strcpy(last, name);

    static struct android_namespace_t* default_copy;
    if (!default_copy)
        default_copy = android_create_namespace_escape("zomdroid-default-copy", NULL, NULL,
                                                       ANDROID_NAMESPACE_TYPE_SHARED, NULL, NULL);
    struct android_namespace_t* sphal =
            android_get_exported_namespace ? android_get_exported_namespace("sphal") : NULL;
    bool shared = false;
    if (android_link_namespaces && default_copy && android_link_namespaces(zomdroid_ns, default_copy, name))
        shared = true;
    if (android_link_namespaces && sphal && android_link_namespaces(zomdroid_ns, sphal, name))
        shared = true;
    LOGI("Vulkan: %s %s with zomdroid-ns", name, shared ? "shared" : "could not be shared");
    return shared;
}

/*
 * Last resort when sharing the named libraries one by one did not get the loader and driver in:
 * make the whole default namespace and the vendor's whole sphal visible to zomdroid-ns, as
 * xdroid360's private loader does (that is how PanVK loads there). zomdroid-ns still searches its
 * own path first, so what box64, the JRE and the game already resolve stays the same; only
 * libraries zomdroid-ns lacks are now found outside. Done once, and only for a custom driver.
 */
static bool share_all_libraries(void) {
    static bool done;
    if (done) return false;
    done = true;
    bool shared = linkernsbypass_link_namespace_to_default_all_libs(zomdroid_ns);
    struct android_namespace_t* sphal =
            android_get_exported_namespace ? android_get_exported_namespace("sphal") : NULL;
    if (android_link_namespaces_all_libs && sphal && android_link_namespaces_all_libs(zomdroid_ns, sphal))
        shared = true;
    LOGI("Vulkan: all default%s libraries %s with zomdroid-ns", sphal ? " and sphal" : "",
         shared ? "shared" : "could not be shared");
    return shared;
}

static void (*prepare_macho_pathfind)(void);
static int load_linker_hook() {
    void* zomdroid_linker = linkernsbypass_namespace_dlopen("libzomdroidlinker.so", RTLD_LOCAL, zomdroid_ns);
    if (!zomdroid_linker) {
        LOGE("%s", dlerror());
        return -1;
    }
    prepare_macho_pathfind = dlsym(zomdroid_linker, "zomdroid_linker_prepare_pathfind");
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
        // A library the loader or driver cannot find is shared in (share_missing_library), a few
        // times at most, since one missing library can hide the next; after that, everything in
        // the default and sphal namespaces is shared once (share_all_libraries). If they still do
        // not load, the game starts on the phone's own driver instead of a black screen: with no handles
        // set, libzomdroidlinker leaves libvulkan.so and the driver lookup to the system.
        void* vulkan_loader = NULL;
        void* vulkan_driver = NULL;
        for (int i = 0; i < 5 && !vulkan_loader; i++) {
            vulkan_loader = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so",
                                                                   getenv("ZOMDROID_CACHE_DIR"), RTLD_LOCAL, zomdroid_ns);
            if (vulkan_loader) break;
            const char* error = dlerror();
            LOGW("Vulkan: private libvulkan.so did not load: %s", error);
            if (!share_missing_library(error) && !share_all_libraries()) break;
        }
        for (int i = 0; i < 5 && vulkan_loader && !vulkan_driver; i++) {
            vulkan_driver = linkernsbypass_namespace_dlopen(g_zomdroid_vulkan_driver_name, RTLD_LOCAL, zomdroid_ns);
            if (vulkan_driver) break;
            const char* error = dlerror();
            LOGW("Vulkan: driver %s did not load: %s", g_zomdroid_vulkan_driver_name, error);
            if (!share_missing_library(error) && !share_all_libraries()) break;
        }
        if (vulkan_driver && !dlsym(vulkan_driver, "HMI")) {
            LOGW("Vulkan: %s has no HMI entry point, not an Android Vulkan driver", g_zomdroid_vulkan_driver_name);
            vulkan_driver = NULL;
        }
        if (vulkan_loader && vulkan_driver) {
            zomdroid_linker_set_vulkan_loader_handle(vulkan_loader);
            zomdroid_linker_set_vulkan_driver_handle(vulkan_driver);
        } else {
            LOGE("Vulkan: driver %s is unusable here, falling back to the phone's own driver",
                 g_zomdroid_vulkan_driver_name);
        }
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
    snprintf(g_crash_path, sizeof(g_crash_path), "%s/%s", game_dir_path,
             getenv("ZOMDROID_SERVER_PROBE") ? "server-crash.txt" : "crash.txt");
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
    snprintf(g_native_log_path, sizeof(g_native_log_path), "%s/%s", game_dir_path,
             getenv("ZOMDROID_SERVER_PROBE") ? "server-native.log" : "native.log");
    const char* coop_output = getenv("ZOMDROID_COOP_OUTPUT");
    if (coop_output) snprintf(g_native_log_path, sizeof(g_native_log_path), "%s", coop_output);

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
    if (prepare_macho_pathfind) prepare_macho_pathfind();

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
    } else if (strcmp(renderer_name, "MOBILEGLUES_EXPERIMENTAL") == 0) {
        g_zomdroid_renderer = MOBILEGLUES_EXPERIMENTAL;
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
