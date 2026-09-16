#include <string.h>
#include <stdio.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <malloc.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <pthread.h>
#include "logger.h"
#include "emulation.h"
#include "zomdroid_globals.h"

#include "liblinkernsbypass/android_linker_ns.h"

#include "box64/src/include/librarian.h"
#include "box64/src/include/myalign.h"
#include "box64/src/include/elfloader.h"
#include "box64/src/include/library.h"
#include "box64/src/include/zomdroid_jni_stats.h"
#include "macho_loader.h"
#include "macho_jnienv.h"
#include "macho_pathfind.h"


#define LOG_TAG "zomdroid-linker"

// Diagnostics that have to reach the exported Bug Report. LOGx goes to logcat only, and the
// launcher rotates its logcat capture on every app start - by the time a player exports a report
// the game session's lines are long gone. stdout is mirrored into <game>/native.log by
// monitor_stdio_and_memory() in zomdroid.c, which survives the crash and the restart.
#define LOG_REPORTED(fmt, ...) do {                  \
        LOGI(fmt __VA_OPT__(,) __VA_ARGS__);         \
        printf(fmt "\n" __VA_OPT__(,) __VA_ARGS__);  \
    } while (0)

#define BUF_SIZE 1024

#define JNI_SIG_CACHE_SIZE 64

// JVMS 4.6, method access_flags. GetMethodModifiers returns these; the JNI headers we bundle do
// not declare the constant.
#define JVM_ACC_NATIVE 0x0100

static void* (*loader_dlsym)(void* handle, const char* symbol, const void* caller);

typedef struct {
    char* sym;  // key
    char* sig;  // value (full method signature)
} JniSigCacheEntry;

static JniSigCacheEntry g_jni_sig_cache[JNI_SIG_CACHE_SIZE];
static int g_jni_sig_cache_next = 0;
static pthread_mutex_t g_jni_sig_cache_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_jni_lib_mutex = PTHREAD_MUTEX_INITIALIZER;

// Returns a copy the caller owns and must free(), or NULL on a miss. The copy is made while the
// mutex is still held — this is load-bearing, not style. The cache is a 64-entry ring and PZ
// resolves more natives than that from several threads at once during world load, so an entry can
// be evicted — and its strings freed — the instant the lock is released. The old version returned
// the raw cache pointer and let the caller strdup() it outside the lock: a use-after-free race.
// A signature string corrupted that way becomes a trampoline with shifted argument marshalling,
// which is exactly how LightingJNI.getVisibleRooms(I[J)I kept crashing in GetArrayLength with
// torn array references (low word 1, high word heap garbage) on world load.
static char* jni_sig_cache_get(const char* sym) {
    char* result = NULL;
    pthread_mutex_lock(&g_jni_sig_cache_mutex);
    for (int i = 0; i < JNI_SIG_CACHE_SIZE; i++) {
        if (g_jni_sig_cache[i].sym && strcmp(g_jni_sig_cache[i].sym, sym) == 0) {
            result = strdup(g_jni_sig_cache[i].sig);
            break;
        }
    }
    pthread_mutex_unlock(&g_jni_sig_cache_mutex);
    return result;
}

static jobjectArray JNICALL stub_getAudioDevices(JNIEnv* env, jclass clazz, jint deviceType) {
    LOGD("[stub] getAudioDevices(%d) -> NULL", deviceType);
    (void)env;
    (void)clazz;
    (void)deviceType;
    return NULL;  // пусть FMOD использует дефолтное устройство
}

// Opt-in Android FMOD integration. Keep FMOD's Android runtime on ART, but pass
// the original HotSpot JNIEnv to the game's integration and its Java callbacks.
static jint (JNICALL *native_fmod_create)(JNIEnv*, jclass);
static void** native_fmod_system;
static int (*native_fmod_set_output)(void*, int);

static jint JNICALL native_fmod_create_bridge(JNIEnv* env, jclass clazz) {
    JNIEnv* art = NULL;
    jint status = g_zomdroid_art_vm
        ? (*g_zomdroid_art_vm)->GetEnv(g_zomdroid_art_vm, (void**)&art, JNI_VERSION_1_6)
        : JNI_ERR;
    if (status == JNI_EDETACHED)
        status = (*g_zomdroid_art_vm)->AttachCurrentThread(g_zomdroid_art_vm, (void**)&art, NULL);
    if (status != JNI_OK || !art) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) (*env)->ThrowNew(env, error, "Cannot attach FMOD thread to Android VM");
        return -1;
    }
    jint result = native_fmod_create(env, clazz);
    if ((*env)->ExceptionCheck(env)) return result;
    const char* api = getenv("ZOMDROID_AUDIO_API");
    // Same FMOD_OUTPUTTYPE values as wrappedfmodstudio.c. Apply after create,
    // before the game's separate System_Init, retaining the existing setting.
    int output = api && strstr(api, "AAUDIO") ? 18
               : api && strstr(api, "OPENSL") ? 12 : -1;
    if (result == 0 && *native_fmod_system && output >= 0) {
        int set_result = native_fmod_set_output(*native_fmod_system, output);
        LOG_REPORTED("[fmod-native] audio output=%s result=%d", api, set_result);
    }
    LOG_REPORTED("[fmod-native] System_Create result=%d, ART attached", result);
    return result;
}

static int prepare_native_fmod(void* handle) {
    native_fmod_create = loader_dlsym(handle,
        "Java_fmod_javafmodJNI_FMOD_1System_1Create", __builtin_return_address(0));
    native_fmod_system = loader_dlsym(handle, "globalSystem", __builtin_return_address(0));
    native_fmod_set_output = loader_dlsym(handle,
        "_ZN4FMOD6System9setOutputE15FMOD_OUTPUTTYPE", __builtin_return_address(0));
    // Audited TIS 42.20.0/.4 has no own JNI_OnLoad. Do not silently suppress
    // an initialization routine introduced by a future integration binary.
    void* onload = loader_dlsym(handle, "JNI_OnLoad", __builtin_return_address(0));
    Dl_info owner = {0};
    if (onload && (!dladdr(onload, &owner) || !owner.dli_fname ||
                  strstr(owner.dli_fname, "libfmodintegration"))) return 0;
    return native_fmod_create && native_fmod_system && native_fmod_set_output;
}

static void jni_sig_cache_put(const char* sym, const char* sig) {
    if (!sym || !sig) return;
    // store our own copies, because caller frees its return value
    char* sym_copy = strdup(sym);
    char* sig_copy = strdup(sig);
    if (!sym_copy || !sig_copy) {
        free(sym_copy);
        free(sig_copy);
        return;
    }

    pthread_mutex_lock(&g_jni_sig_cache_mutex);

    int idx = g_jni_sig_cache_next;
    g_jni_sig_cache_next = (g_jni_sig_cache_next + 1) % JNI_SIG_CACHE_SIZE;

    free(g_jni_sig_cache[idx].sym);
    free(g_jni_sig_cache[idx].sig);

    g_jni_sig_cache[idx].sym = sym_copy;
    g_jni_sig_cache[idx].sig = sig_copy;

    pthread_mutex_unlock(&g_jni_sig_cache_mutex);
}

static void* (*loader_dlopen)(const char* filename, int flags, const void* caller);

static void* (*loader_android_dlopen_ext)(const char* filename,
                                          int flag,
                                          const android_dlextinfo* extinfo,
                                          const void* caller_addr);
static void* vulkan_driver_handle;
static void* vulkan_loader_handle;

// jassimp64: lets the game-dir library load when no ARM64 build shadows it on java.library.path.
// The game ships jassimp built from the Assimp its models were authored against (B41 = 5.0.1,
// measured), while our ARM64 build tracks 5.4.3 - loading THEIR build through box64 gives each
// game version the importer semantics it expects.
static EmulatedLib jni_libs[] = {{.name = "PZClipper64"}, {.name = "PZBullet64"}, {.name = "PZBulletNoOpenGL64"}, {.name = "Lighting64"}, {.name = "PZPathFind64"}, {.name = "PZPopMan64"}, {.name = "fmodintegration64"}, { .name = "zomdroidtest"}, { .name = "RakNet64"}, { .name = "ZNetNoSteam"}, { .name = "jassimp64"} };
static int jni_lib_count = sizeof (jni_libs) / sizeof (EmulatedLib);
// The path each natively loaded entry was opened from (empty while emulated or not loaded yet).
// Kept beside the table rather than in EmulatedLib: only dlopen() below needs it, to re-open the
// same file through bionic on a cache hit - see the note there.
static char jni_native_path[sizeof (jni_libs) / sizeof (EmulatedLib)][BUF_SIZE];
// The entries currently served by a macOS dylib through our own loader (NULL otherwise). The
// handle in jni_libs[] is then the macho_lib_t*, which is fine: the JVM only ever hands it back
// to dlsym, and a dlclose on it is a harmless "invalid handle" from bionic. jni_native_path
// stays empty for these, so the cache hit above returns the handle without a bionic re-open.
static macho_lib_t* jni_macho[sizeof (jni_libs) / sizeof (EmulatedLib)];

// The libraries the macOS depot has current ARM64 builds of, by their JNI entry name.
// tools/macos-native-libs-brief.md, section 3, is the contract for these names. RakNet is
// deliberately absent: the multiplayer libraries stay on their current path for now. Of the
// rest only Lighting loads today - the others are refused by the loader's libc++ layout
// guard - but the names stay so a future build that passes the guard is picked up.
static const char* macho_dylib_for(const char* jni_name) {
    static const char* const map[][2] = {
        { "Lighting64",   "libLighting.dylib"   },
        { "PZPopMan64",   "libPZPopMan.dylib"   },
        { "PZPathFind64", "libPZPathFind.dylib" },
    };
    for (size_t i = 0; i < sizeof(map) / sizeof(map[0]); i++)
        if (strcmp(jni_name, map[i][0]) == 0) return map[i][1];
    return NULL;
}
// ZOMDROID_MACHO_SKIP: comma-separated JNI names of the macOS libraries the player turned off
// one by one in Settings ("Libraries in use"). A skipped entry takes its regular path.
static int macho_module_skipped(const char* jni_name) {
    const char* skip = getenv("ZOMDROID_MACHO_SKIP");
    if (skip == NULL || *skip == '\0') return 0;
    size_t n = strlen(jni_name);
    for (const char* p = skip; *p != '\0'; ) {
        const char* e = strchr(p, ',');
        size_t len = e ? (size_t)(e - p) : strlen(p);
        if (len == n && strncmp(p, jni_name, n) == 0) return 1;
        if (e == NULL) break;
        p = e + 1;
    }
    return 0;
}

void zomdroid_linker_prepare_pathfind(void) {
    const char* options = getenv("ZOMDROID_MACHO_PATHFIND_OPTIONS");
    const char* enabled = getenv("ZOMDROID_MACHO_LIBS");
    if (!options || !*options || !enabled || strcmp(enabled, "1")) return;
    if (macho_module_skipped("PZPathFind64")) {
        LOG_REPORTED("[macho] PathFind turned off in settings; keeping Java pathfinding");
        return;
    }
    for (int i = 0; i < jni_lib_count; ++i) {
        if (strcmp(jni_libs[i].name, "PZPathFind64")) continue;
        macho_lib_t* ml = jni_macho[i];
        if (!ml) ml = macho_load("macos/libPZPathFind.dylib");
        if (!ml) {
            LOG_REPORTED("[macho] PathFind preflight rejected; keeping Java pathfinding");
            return;
        }
        jni_macho[i] = ml;
        jni_libs[i].handle = (library_t*)ml;
        jni_libs[i].is_emulated = false;
        LOG_REPORTED("[macho] PathFind preflight loaded; UseNativeCode=%s",
                     macho_pathfind_enable_option(options) ? "true" : "false (options update failed)");
        return;
    }
}

static void init_jni_libs() {
    static int initialized = 0;
    if (initialized) return;

    for (int i = 0; i < jni_lib_count; i++) {
        jni_libs[i].handle = NULL;
        jni_libs[i].mapped_pages = NULL;
        jni_libs[i].page_count = 0;
        jni_libs[i].page_code_size = NULL;
        jni_libs[i].is_emulated = false;
    }

    initialized = 1;
}

__attribute__((visibility("default"), used))
void zomdroid_linker_set_vulkan_driver_handle(void* handle) {
    vulkan_driver_handle = handle;
}

__attribute__((visibility("default"), used))
void zomdroid_linker_set_vulkan_loader_handle(void* handle) {
    vulkan_loader_handle = handle;
}

__attribute__((visibility("default"), used))
void zomdroid_linker_set_proc_addrs(void* _loader_dlopen_fn, void* _loader_dlsym_fn,
                                    void* _loader_android_dlopen_ext_fn) {
    loader_dlopen = _loader_dlopen_fn;
    loader_dlsym = _loader_dlsym_fn;
    loader_android_dlopen_ext = _loader_android_dlopen_ext_fn;
}



__attribute__((visibility("default"), used))
// Java signature of a method id, for the wrapper JNIEnv's variadic shims (macho_jnienv.c). JVMTI
// hands out its own copies of the strings; the caller keeps the result, so it gets a plain copy.
static const char* macho_method_signature(jmethodID mid) {
    if (g_zomdroid_jvmti_env == NULL || mid == NULL) return NULL;
    char* name = NULL;
    char* sig = NULL;
    static __thread char copy[1024];
    if ((*g_zomdroid_jvmti_env)->GetMethodName(g_zomdroid_jvmti_env, mid, &name, &sig, NULL) != JVMTI_ERROR_NONE)
        return NULL;
    const char* result = NULL;
    if (sig != NULL && strlen(sig) < sizeof(copy)) {
        strcpy(copy, sig);
        result = copy;
    }
    if (name) (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)name);
    if (sig) (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)sig);
    return result;
}

// Resident set size of this process in MB, from /proc/self/statm; -1 if unreadable.
static long zomdroid_rss_mb(void) {
    FILE* f = fopen("/proc/self/statm", "r");
    if (f == NULL) return -1;
    long size_pages = 0, resident_pages = 0;
    int ok = fscanf(f, "%ld %ld", &size_pages, &resident_pages) == 2;
    fclose(f);
    if (!ok) return -1;
    return resident_pages * (sysconf(_SC_PAGESIZE) / 1024) / 1024;
}

int zomdroid_linker_init() {
    init_jni_libs();
    macho_jnienv_set_signature_provider(macho_method_signature);

    // What box64 costs at startup: context, the x86_64 glibc and libjniwrapper loaded through
    // emulation. Measured to decide whether initialising it only on demand is worth the trouble
    // (it is not free: its signal handlers would then be installed after the JVM's).
    struct timespec t0, t1;
    long rss_before = zomdroid_rss_mb();
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int rc = zomdroid_emulation_init();
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long rss_after = zomdroid_rss_mb();
    double ms = (double)(t1.tv_sec - t0.tv_sec) * 1000.0 + (double)(t1.tv_nsec - t0.tv_nsec) / 1e6;
    LOG_REPORTED("[box64-init] emulation init %s in %.1f ms, RSS %ld -> %ld MB (%+ld MB)",
                 rc == 0 ? "done" : "FAILED", ms, rss_before, rss_after, rss_after - rss_before);
    fflush(stdout);

    if (rc != 0) {
        LOGE("Failed to initialize emulation");
        return -1;
    }

    return 0;
}

// returns next box64 type from jni method signature
/*static char get_next_type_from_sigature(char** sig) {
    char type;
    if (**sig == 0) return 0;
    switch (**sig) {
        case 'B': // jbyte
            type = 'c';
            break;
        case 'C': // jchar
            type = 'W';
            break;
        case 'D': // jdouble
            type = 'd';
            break;
        case 'F': // jfloat
            type = 'f';
            break;
        case 'I': // jint
            type = 'i';
            break;
        case 'J': // jlong
            type = 'I';
            break;
        case 'S': // jshort
            type = 'w';
            break;
        case 'Z': // jboolean
            type = 'C';
            break;
        case 'L': // reference to object
            int escaped = 0;
            (*sig)++;
            while(**sig) {
                if (**sig == '_') {
                    escaped = 1;
                } else if (**sig == '2' && escaped) {
                    type = 'p';
                    break;
                } else {
                    escaped = 0;
                }
                (*sig)++;
            }
            if (**sig == 0) {
                LOGE("Signature string ended before complete class name could be read");
                return -1;
            }
            break;
        case '_': // reference to array
            (*sig)++;
            if (**sig != '3') {
                LOGE("Unexpected character '%c' after _", **sig);
                return -1;
            }
            (*sig)++;
            get_next_type_from_sigature(sig); // skip enclosed type
            (*sig)--;
            type = 'p';
            break;
        default:
            LOGE("Unexpected first character '%c'", **sig);
            return -1;
    }
    (*sig)++;
    return type;
}*/

static void parse_jni_sym_name(const char* sym_name, char** class_name, char** method_name, char** method_sig_short) {
    // Always initialize outputs, so caller can safely free() them even on failure.
    *class_name = NULL;
    *method_name = NULL;
    *method_sig_short = NULL;

    LOGV("Parsing %s", sym_name);

    char buf[BUF_SIZE];

    if (strncmp(sym_name, "Java_", 5) != 0) {
        LOGE("Name doesn't start with Java_");
        return;
    }

    sym_name += 5;

    int i = 0;
    int sig = 0;
    int method = 0;

    while (*sym_name != '\0') {
        if (i + 1 >= BUF_SIZE) {
            LOGE("Class + method name string is more than %d characters long", BUF_SIZE);
            return;
        }

        switch (*sym_name) {
            case '_':
                sym_name++;
                switch (*sym_name) {
                    case '0':
                    LOGE("Unexpected _0");
                        return;
                    case '1':
                        buf[i++] = '_';
                        sym_name++;
                        break;
                    case '2':
                        if (!sig) {
                            LOGE("Unexpected _2 not in signature");
                            return;
                        }
                        buf[i++] = ';';
                        sym_name++;
                        break;
                    case '3':
                        if (!sig) {
                            LOGE("Unexpected _3 not in signature");
                            return;
                        }
                        buf[i++] = '[';
                        sym_name++;
                        break;
                    case '_':
                        sig = i + 1;
                        buf[i++] = '\0';
                        sym_name++;
                        break;
                    default:
                        if (sig) {
                            buf[i] = '/';
                        } else  {
                            if (method) buf[method - 1] = '/';
                            buf[i] = '\0';
                            method = i + 1;
                        }
                        i++;
                        break;
                }
                break;

            default:
                buf[i++] = *sym_name;
                sym_name++;
                break;
        }
    }

    buf[i++] = '\0';

    if (!method) {
        LOGE("JNI name doesn't contain method name");
        return;
    }

    // class_name is everything before "method" index
    *class_name = (char*)malloc((size_t)method);
    if (!*class_name) {
        LOGE("malloc failed for class_name");
        return;
    }
    strcpy(*class_name, buf);

    // method_name starts at buf[method], ends at (sig-1) if signature exists, otherwise end of buf
    int method_name_len = sig ? (sig - method) : (i - method);
    *method_name = (char*)malloc((size_t)method_name_len);
    if (!*method_name) {
        LOGE("malloc failed for method_name");
        free(*class_name);
        *class_name = NULL;
        return;
    }
    strcpy(*method_name, &buf[method]);

    // signature short is optional
    if (sig) {
        size_t short_len = (size_t)(i - sig + 2); // + "(" + ")" + '\0' already accounted in original logic
        *method_sig_short = (char*)calloc(short_len, sizeof(char));
        if (!*method_sig_short) {
            LOGE("calloc failed for method_sig_short");
            // class_name and method_name remain valid; caller may still use them.
            return;
        }
        strcat(*method_sig_short, "(");
        strcat(*method_sig_short, &buf[sig]);
        strcat(*method_sig_short, ")");
    }

    LOGV("className=%s methodName=%s methodSignatureShort=%s",
         *class_name,
         *method_name,
         (*method_sig_short == NULL) ? "<null>" : *method_sig_short);
}

static int method_signature_to_types(char* sig, char** arg_types, char* return_type) {
    char buf[BUF_SIZE];
    int i = 0;
    buf[i++] = 'p'; // JNIEnv*
    buf[i++] = 'p'; // jobject

    sig++; // skip (

    //char array = false;
    char array = 0;

    while(*sig != ')') {
        if (i + 1 >= BUF_SIZE) {
            LOGE("Method signature is too long");
            return -1;
        }
        while(*sig == '[') {
            array = true;
            sig++;
        }
        switch (*sig) {
            case '\0':
            LOGE("Encountered end of string before )");
                return -1;
            case 'B': // jbyte
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'c';
                break;
            case 'C': // jchar
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'W';
                break;
            case 'D': // jdouble
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'd';
                break;
            case 'F': // jfloat
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'f';
                break;
            case 'I': // jint
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'i';
                break;
            case 'J': // jlong
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'I';
                break;
            case 'S': // jshort
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'w';
                break;
            case 'Z': // jboolean
                if (array) {
                    buf[i++] = 'p';
                    array = 0;
                    break;
                }
                buf[i++] = 'C';
                break;
            case 'L': // reference to object
                (*sig)++;
                while(*sig != ';') {
                    if (*sig == '\0') {
                        LOGE("Encountered end of string before ;");
                        return -1;
                    }
                    sig++;
                }
                buf[i++] ='p';
                array = 0;
                break;
            default:
            LOGE("Unexpected character %c", *sig);
                return -1;
        }
        sig++;
    }

    buf[i] = '\0';

    sig++; // skip )

    while(*sig == '[') {
        array = true;
        sig++;
    }
    switch (*sig) {
        case '\0':
        LOGE("Encountered end of string before return type");
            return -1;
        case 'B': // jbyte
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'c';
            break;
        case 'C': // jchar
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'W';
            break;
        case 'D': // jdouble
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'd';
            break;
        case 'F': // jfloat
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'f';
            break;
        case 'I': // jint
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'i';
            break;
        case 'J': // jlong
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'I';
            break;
        case 'S': // jshort
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'w';
            break;
        case 'Z': // jboolean
            if (array) {
                *return_type = 'p';
                array = 0;
                break;
            }
            *return_type = 'C';
            break;
        case 'L': // reference to object
            (*sig)++;
            while(*sig != ';') {
                if (*sig == '\0') {
                    LOGE("Encountered end of string before ;");
                    return -1;
                }
                sig++;
            }
            *return_type = 'p';
            array = 0;
            break;
        case 'V': // void - only for return type
            *return_type = 'v';
            break;
        default:
        LOGE("Unexpected character %c", *sig);
            return -1;
    }

    *arg_types = malloc(i + 1);
    strcpy(*arg_types, buf);

    return 0;
}

// if we don't need return types there is a better and faster way to do this without JVMTI by just parsing JNI long name
// LIAMELUI this function is unoptimized and experimental, should roll back to long name parsing asap or at least cache classes and methods
static char* method_signature_from_symbol_name(const char* sym) {
    char* class_name = NULL;
    char* class_sig = NULL;
    char* method_name = NULL;
    char* method_sig = NULL; // argument types + return type
    char* method_sig_short = NULL; // only argument types
    char* cached = jni_sig_cache_get(sym); // already a fresh copy, made under the cache mutex
    if (cached) {
        return cached;
    }

    jvmtiError jvmti_err = 0;
    jint class_count = 0;
    jclass* classes = NULL;
    jclass target_class = NULL;
    jint method_count = 0;
    jmethodID *methods = NULL;
    jmethodID target_method = NULL;

    parse_jni_sym_name(sym, &class_name, &method_name, &method_sig_short);

    if (class_name == NULL || method_name == NULL) {
        LOGE("Failed to parse JNI symbol name: %s", sym);
        goto FAIL;
    }

    class_sig = calloc(strlen(class_name) + 3, sizeof(char));
    strcat(class_sig, "L");
    strcat(class_sig, class_name);
    strcat(class_sig, ";");


    jvmti_err = (*g_zomdroid_jvmti_env)->GetClassLoaderClasses(g_zomdroid_jvmti_env, g_zomdroid_main_class_loader, &class_count, &classes);
    if (jvmti_err != JVMTI_ERROR_NONE) {
        LOGE("Failed to get class loader classes, error code: %d", jvmti_err);
        goto FAIL;
    }
    for (int a = 0; a < class_count; a++) {
        char* signature = NULL;
        char match = false;
        jvmti_err = (*g_zomdroid_jvmti_env)->GetClassSignature(g_zomdroid_jvmti_env, classes[a], &signature, NULL);
        if (jvmti_err != JVMTI_ERROR_NONE) {
            LOGW("Failed to get class methodSignatureShort, error code %d", jvmti_err);
            continue;
        }
        if (strcmp(signature, class_sig) == 0) {
            target_class = classes[a];
            match = true;
        }
        jvmti_err = (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)signature);
        if (jvmti_err != JVMTI_ERROR_NONE) LOGW("Failed to deallocate JVM TI memory, error code: %d", jvmti_err);
        if (match) break;
    }

    jvmti_err = (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)classes);
    if (jvmti_err != JVMTI_ERROR_NONE) LOGW("Failed to deallocate JVM TI memory, error code: %d", jvmti_err);

    if (target_class == NULL) {
        LOGE("Failed to find class %s by it's signature %s", class_name, class_sig);
        goto FAIL;
    }

    jvmti_err = (*g_zomdroid_jvmti_env)->GetClassMethods(g_zomdroid_jvmti_env, target_class, &method_count, &methods);
    if (jvmti_err != JVMTI_ERROR_NONE) {
        LOGE("Failed to get methods for class %s", class_name);
        goto FAIL;
    }
    // A JNI symbol can only ever bind to a NATIVE method, so anything else must be skipped even
    // when the name matches. Without that filter a plain Java overload can win the lookup and its
    // descriptor is what the trampoline gets built from. zombie.iso.LightingJNI ships both
    //     native int getVisibleRooms(int, long[])  -> (I[J)I                -> "ppip"
    //            ArrayList getVisibleRooms(int)    -> (I)Ljava/util/ArrayList; -> "ppi"
    // and picking the second one leaves RCX - the jlongArray - never written, so the emulated
    // library read whatever the previous emulated call had left there and died inside
    // GetArrayLength on a pointer that was never a JNI reference (GetObjectRefType == INVALID,
    // measured on device 2026-08-01). GetClassMethods does not specify an order, which is exactly
    // why that crash floated: get the good order and the session is clean, get the other one and
    // it dies on the first lighting frame after the world loads.
    // The whole loop now runs to the end instead of breaking on the first hit - that is what lets
    // us notice a genuinely ambiguous symbol instead of silently picking one.
    int native_matches = 0;
    for (int a = 0; a < method_count; a++) {
        char* mName = NULL;
        char* mSig = NULL;
        jvmti_err = (*g_zomdroid_jvmti_env)->GetMethodName(g_zomdroid_jvmti_env, methods[a], &mName, &mSig, NULL);
        if (jvmti_err != JVMTI_ERROR_NONE) {
            LOGW("Failed to get method name, error code %d", jvmti_err);
            continue;
        }
        if (strcmp(method_name, mName) == 0) {
            jint mods = 0;
            jvmtiError mod_err = (*g_zomdroid_jvmti_env)->GetMethodModifiers(g_zomdroid_jvmti_env,
                                                                             methods[a], &mods);
            if (mod_err != JVMTI_ERROR_NONE) {
                LOGW("Failed to get modifiers of %s, error code %d", mName, mod_err);
            } else if (mods & JVM_ACC_NATIVE) {
                // A long JNI name carries the argument list, and it is always a prefix of the
                // full descriptor - match it as one instead of searching anywhere inside, where
                // a class name in a later parameter could produce a false hit.
                if (method_sig_short == NULL
                    || strncmp(mSig, method_sig_short, strlen(method_sig_short)) == 0) {
                    native_matches++;
                    if (target_method == NULL) {
                        target_method = methods[a];
                        method_sig = strdup(mSig);
                    }
                }
            }
        }
        // Single exit path for the JVM TI allocations - no early continue may skip it.
        jvmti_err = (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)mName);
        if (jvmti_err != JVMTI_ERROR_NONE) LOGW("Failed to deallocate JVM TI memory, error code: %d", jvmti_err);
        jvmti_err = (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)mSig);
        if (jvmti_err != JVMTI_ERROR_NONE) LOGW("Failed to deallocate JVM TI memory, error code: %d", jvmti_err);
    }

    if (target_method == NULL) {
        LOGE("Failed to find method %s with signature %s in class %s", method_name, (method_sig_short == NULL) ? "<null>" : method_sig_short, class_name);
        goto FAIL;
    }

    if (native_matches > 1) {
        // Two native methods sharing a name can only be told apart by the long, argument-mangled
        // symbol form; a short name here is unresolvable in principle. Keep the first match so a
        // working setup does not regress, but never let it pass silently.
        LOGE("Ambiguous JNI symbol %s: %d native methods named %s in %s. Using %s - the symbol "
             "needs its long (argument-mangled) form to resolve deterministically.",
             sym, native_matches, method_name, class_name, method_sig);
    }

    free(class_name);
    free(class_sig);
    free(method_name);
    free(method_sig_short);
    jni_sig_cache_put(sym, method_sig);
    return method_sig;
    FAIL:
    free(class_name);
    free(class_sig);
    free(method_name);
    free(method_sig);
    free(method_sig_short);
    return NULL;
}


__attribute__((visibility("default"), used))
void *dlopen(const char* filename, int flags) {
    //LOGE("[linker] dlopen called with filename=%s flags=%d", filename, flags);

    if (filename == NULL) return loader_dlopen(NULL, flags, __builtin_return_address(0));

    for (int i = 0; i < jni_lib_count; i++) {
        if (!strstr(filename, jni_libs[i].name)) continue;
        const char* bullet_diag_env = getenv("ZOMDROID_BULLET_DIAGNOSTIC");
        int bullet_diag = bullet_diag_env && strcmp(bullet_diag_env, "1") == 0
                && strcmp(jni_libs[i].name, "PZBullet64") == 0;
        if (bullet_diag) {
            LOG_REPORTED("[bullet-diag] dlopen requested=%s flags=%x cached=%p emulated=%d",
                         filename, flags, jni_libs[i].handle, jni_libs[i].is_emulated);
        }

        // Already loaded once -> return cached handle.
        // This prevents repeated AddNeededLib/RunDeferredElfInit and reduces instability.
        if (jni_libs[i].handle != NULL) {
            // For an EMULATED entry the cached handle is the whole story. For a NATIVE one it is
            // not: bionic counts opens against closes, and a handle handed out from here without
            // an open behind it leaves that count one short. Seen 2026-09-10 on PZBullet64: the
            // JVM opened it once, then something on the Java side dlopen'ed the same file again
            // with RTLD_NOLOAD while LWJGL was loading libzfa and got this cached handle; its
            // matching dlclose took bionic's count from one to zero and unmapped Bullet under the
            // JVM. Every later dlsym on the still-cached handle then returned NULL - which read as
            // "defineVehicleScript missing" although the file exports it, and killed the game at
            // script load. So re-open the same file through bionic: it finds the loaded library,
            // returns the same handle, and the count balances. dlclose is deliberately not
            // interposed, so this is the only place the count can be kept honest.
            if (!jni_libs[i].is_emulated && jni_native_path[i][0] != '\0') {
                void* again = loader_dlopen(jni_native_path[i], flags | RTLD_NODELETE,
                                            __builtin_return_address(0));
                if (again != NULL) return again;
                const char* dl_msg = dlerror();
                LOG_REPORTED("[linker] re-open of %s failed (%s), answering from the cache",
                             jni_native_path[i], dl_msg ? dl_msg : "no error reported");
            }
            return jni_libs[i].handle;
        }

        // First option: the macOS ARM64 build through our Mach-O loader, when the player turned
        // the option on and the downloader put the file next to the game's own android/ folder.
        // A rejection is logged by the loader and we simply go on to the next option - a dylib
        // can never take the launch down. See tools/macos-native-libs-brief.md.
        int force_box64 = 0;
        const char* macho_env = getenv("ZOMDROID_MACHO_LIBS");
        const char* dylib_name = (macho_env != NULL && strcmp(macho_env, "1") == 0)
                                 ? macho_dylib_for(jni_libs[i].name) : NULL;
        if (dylib_name != NULL && macho_module_skipped(jni_libs[i].name)) {
            LOG_REPORTED("[macho] %s turned off in settings, taking the regular path", jni_libs[i].name);
            dylib_name = NULL;
        }
        if (dylib_name != NULL) {
            force_box64 = strcmp(jni_libs[i].name, "PZPathFind64") == 0
                       || strcmp(jni_libs[i].name, "PZPopMan64") == 0;
            char dylib_path[BUF_SIZE];
            snprintf(dylib_path, BUF_SIZE, "macos/%s", dylib_name);
            if (access(dylib_path, F_OK) == 0) {
                macho_lib_t* ml = macho_load(dylib_path);
                if (ml != NULL) {
                    jni_macho[i] = ml;
                    jni_libs[i].handle = (library_t*)ml;
                    jni_libs[i].is_emulated = false;
                    return ml;
                }
            } else {
                LOG_REPORTED("[macho] no %s, trying the next option", dylib_path);
            }
        }

        // jassimp64 only: the launcher can point at a specific importer build to load instead of
        // whatever the search order would find. Nothing sets this any more - GameLauncher clears
        // it on every launch, and 42.12+ keeps the game's own ARM64 build - but the hook stays,
        // because it is the cheap way to A/B an importer without touching the game's files. When
        // it IS set, the game's own ARM64 build is skipped even if the override fails to load:
        // the point of an override is to replace that library, so falling back to it would defeat
        // the experiment; the fallback is the x86_64 build through box64, the same route every
        // pre-42.12 build already uses. Unset or empty = behaviour unchanged.
        if (strcmp(jni_libs[i].name, "jassimp64") == 0) {
            const char* override_path = getenv("ZOMDROID_JASSIMP64_OVERRIDE");
            if (override_path != NULL && override_path[0] != '\0') {
                void* override_handle = loader_dlopen(override_path, flags | RTLD_NODELETE,
                                                      __builtin_return_address(0));
                if (override_handle != NULL) {
                    jni_libs[i].handle = override_handle;
                    jni_libs[i].is_emulated = false;
                    snprintf(jni_native_path[i], BUF_SIZE, "%s", override_path);
                    // Size, because the file name is fixed and says nothing about which build is
                    // actually in it - a hand-swapped importer looked identical in the log during
                    // the 2026-08-21 A/B and only the tester knew what had been put there.
                    struct stat ov_st;
                    long long ov_size = (stat(override_path, &ov_st) == 0) ? (long long)ov_st.st_size : -1;
                    LOG_REPORTED("[linker] jassimp source=override size=%lld (%s)", ov_size, override_path);
                    return override_handle;
                }
                const char* dl_msg = dlerror();
                LOG_REPORTED("[linker] jassimp override %s failed to load (%s), falling back to box64",
                             override_path, dl_msg ? dl_msg : "no error reported");
                force_box64 = 1;
            }
        }

        //trying to load native library
        const int is_fmod = strcmp(jni_libs[i].name, "fmodintegration64") == 0;
        const char* native_fmod = getenv("ZOMDROID_NATIVE_FMOD");
        if (!force_box64 && (!is_fmod || (native_fmod && strcmp(native_fmod, "1") == 0))) {
            const char* base = strrchr(filename, '/');
            if (base)
                base++;
            else
                base = filename;

            char android_filename[BUF_SIZE] = {0};
            snprintf(android_filename, BUF_SIZE, "android/arm64-v8a/%s", base);

            if (access(android_filename, F_OK) == 0) {
                // RTLD_NODELETE: a JNI library the JVM holds must never be unmapped by an
                // unbalanced dlclose from anyone else - the count fix above is the cure, this is
                // the belt to its braces.
                void* native_handle = loader_dlopen(android_filename, flags | RTLD_NODELETE,
                                                    __builtin_return_address(0));
                if (native_handle && is_fmod && !prepare_native_fmod(native_handle)) {
                    LOG_REPORTED("[fmod-native] compatibility checks failed; falling back to box64");
                    dlclose(native_handle);
                    native_handle = NULL;
                }
                if (native_handle != NULL) {
                    jni_libs[i].handle = native_handle;
                    jni_libs[i].is_emulated = false;
                    snprintf(jni_native_path[i], BUF_SIZE, "%s", android_filename);
                    LOG_REPORTED("[linker] %s loaded natively", android_filename);
                    if (bullet_diag) {
                        const char* probe_name = "Java_zombie_core_physics_Bullet_defineVehicleScript";
                        void* probe = loader_dlsym(native_handle, probe_name, __builtin_return_address(0));
                        LOG_REPORTED("[bullet-diag] native handle=%p direct lookup %s=%p",
                                     native_handle, probe_name, probe);
                    }
                    return native_handle;
                }
                // A file that is present but will not load must never be fatal. The game's ARM64
                // folder has a track record - three of its libraries ship incomplete and we
                // disable them by hand - and a copy truncated during install fails the Android
                // linker's own bounds check ("invalid shdr offset/size"), which is what killed
                // startup for players on 1.4.8. Returning NULL here left the JVM with no library
                // at all; the game's x86_64 build under box64 is a working equivalent, so fall
                // through to it instead of giving up.
                const char* dl_msg = dlerror();
                LOG_REPORTED("[linker] %s exists but dlopen failed (%s), falling back to box64",
                             android_filename, dl_msg ? dl_msg : "no error reported");
            } else {
                LOG_REPORTED("[linker] no native %s, loading through box64...", android_filename);
            }
        }

        //elsewise loading in box64
        LOG_REPORTED("[linker] Loading %s in box64...", filename);
        needed_libs_t* needed_lib = new_neededlib(1);
        needed_lib->names[0] = strdup(filename);
        int bindnow = (flags & 0x2) ? 1 : 0;
        int islocal = (flags & 0x100) ? 0 : 1;
        // int deepbind = (flags & 0x8) ? 1 : 0;
        if (AddNeededLib(NULL, islocal, bindnow, 1, needed_lib, NULL, my_context, thread_get_emu()) != 0) {
            LOGE("[linker] Failed to load %s in box64", jni_libs[i].name);
            RemoveNeededLib(NULL, islocal, needed_lib, my_context, thread_get_emu());
            free_neededlib(needed_lib);
            return NULL;
        } else {
            //LOGE("[linker] box64 AddNeededLib: trying to load %s", filename);
        }
        jni_libs[i].handle = needed_lib->libs[0];
        jni_libs[i].is_emulated = true;

        free_neededlib(needed_lib);

        int old_deferredInit = my_context->deferredInit;
        my_context->deferredInit = 1;
        elfheader_t** old_deferredInitList = my_context->deferredInitList;
        my_context->deferredInitList = NULL;
        int old_deferredInitSz = my_context->deferredInitSz;
        int old_deferredInitCap = my_context->deferredInitCap;
        my_context->deferredInitSz = my_context->deferredInitCap = 0;
        RunDeferredElfInit(thread_get_emu());
        my_context->deferredInit = old_deferredInit;
        my_context->deferredInitList = old_deferredInitList;
        my_context->deferredInitSz = old_deferredInitSz;
        my_context->deferredInitCap = old_deferredInitCap;
        return jni_libs[i].handle;
    }

    if (strcmp(filename, "libvulkan.so") == 0 && vulkan_loader_handle) {
        return vulkan_loader_handle;
    }

    return loader_dlopen(filename, flags, __builtin_return_address(0));
}

// A JNI lookup against a macOS dylib: the export, wrapped in an Apple-ABI shim when the Java
// signature calls for one (macho_bridge.c). Non-JNI names (the JVM's JNI_OnLoad probe) come
// back as they are.
static void* macho_jni_lookup(int i, const char* sym_name) {
    void* target = macho_dlsym(jni_macho[i], sym_name);
    if (target == NULL) return NULL;
    if (strncmp(sym_name, "Java_", 5) != 0) return target;

    char* method_sig = method_signature_from_symbol_name(sym_name);
    if (method_sig == NULL) {
        if (macho_lib_wraps_env(jni_macho[i])) {
            LOG_REPORTED("[macho] %s: missing signature; refusing an unwrapped JNI entry", sym_name);
            return NULL;
        }
        // Without the signature the argument layout is unknown. The plain symbol is right for
        // every function with at most eight arguments per register class and no small ints,
        // which is most of them; the log line is there for the day it is not.
        LOG_REPORTED("[macho] %s: no Java signature found, handing out the symbol unshimmed", sym_name);
        return target;
    }
    char* arg_types = NULL;
    char ret_type = 0;
    if (method_signature_to_types(method_sig, &arg_types, &ret_type) != 0) {
        free(method_sig);
        if (macho_lib_wraps_env(jni_macho[i])) {
            LOG_REPORTED("[macho] %s: invalid signature; refusing an unwrapped JNI entry", sym_name);
            return NULL;
        }
        LOG_REPORTED("[macho] %s: signature not understood, handing out the symbol unshimmed", sym_name);
        return target;
    }
    LOGI("[jni-bind] %s -> %s -> %s%c (macho)", sym_name, method_sig, arg_types, ret_type);
    free(method_sig);
    void* sym = macho_jni_bridge(&jni_libs[i], target, arg_types, sym_name,
                                 macho_lib_wraps_env(jni_macho[i]));
    free(arg_types);
    return sym;
}

__attribute__((visibility("default"), used))
void *dlsym(void *handle, const char *sym_name) {
    //LOGE("[linker] dlsym called with filename=%s", sym_name);
    for (int i = 0; i < jni_lib_count; i++) {
        struct library_s* lib = jni_libs[i].handle;
        EmulatedLib* elib = &jni_libs[i];
        if (sym_name == NULL || handle == NULL || lib != handle) continue;

        if (jni_macho[i] != NULL) return macho_jni_lookup(i, sym_name);

        if (elib->is_emulated) {
            // 1) Stub for getAudioDevices
            if (strcmp(jni_libs[i].name, "fmodintegration64") == 0 &&
                strstr(sym_name, "getAudioDevices")) {

                if (zomdroid_jni_stats_on)
                    zomdroid_jni_stats_register((uintptr_t)stub_getAudioDevices, jni_libs[i].name, strdup(sym_name));
                void* sym = zomdroid_emulation_bridge_jni_symbol(
                        &jni_libs[i],
                        (uintptr_t)stub_getAudioDevices,
                        "ppi",  // JNIEnv*, jclass, jint
                        'p'     // возвращаем jobjectArray
                );
                return sym;
            }

            // 2) The regular x86 way
            struct lib_s* maplib = GetMaplib(lib);
            uintptr_t box64_sym = FindGlobalSymbol(maplib, sym_name, -1, NULL, 0);
            if (box64_sym == 0) {
                return NULL;
            }

            // On Android FMOD relies on Java for initialization, so we need to attach the game audio thread to ART VM
            if (strcmp(sym_name, "Java_fmod_javafmodJNI_FMOD_1System_1Create") == 0) {
                JNIEnv* art_jni_env = NULL;
                (*g_zomdroid_art_vm)->GetEnv(g_zomdroid_art_vm, (void **) &art_jni_env, JNI_VERSION_1_6) ;
                if (art_jni_env == NULL){
                    (*g_zomdroid_art_vm)->AttachCurrentThread(g_zomdroid_art_vm,
                                                              (void **) &art_jni_env, NULL);
                }
                if (art_jni_env == NULL) {
                    LOGE("Failed to attach game FMOD thread to ART VM");
                } else {
                    //LOGD("Successfully attached game FMOD thread to ART VM");
                }
            }



            char* method_sig = method_signature_from_symbol_name(sym_name);

            if (method_sig == NULL) return NULL;

            char* arg_types = NULL;
            char ret_type = 0;
            if (method_signature_to_types(method_sig, &arg_types, &ret_type) != 0) {
                free(method_sig);
                return NULL;
            }
            // One line per resolved symbol (~50 a session, resolution is cached afterwards).
            // This is what makes an overload mix-up visible in a bug report instead of only
            // showing up later as a crash: the box64 type string must have one letter per
            // argument, e.g. getVisibleRooms(I[J)I -> "ppip" and never "ppi".
            LOGI("[jni-bind] %s -> %s -> %s%c", sym_name, method_sig, arg_types, ret_type);
            free(method_sig);

            // The stats table keys on the emulated address; only this place knows the name.
            // Registered only while the diagnostic is on, so normal sessions pay nothing.
            if (zomdroid_jni_stats_on)
                zomdroid_jni_stats_register((uintptr_t)box64_sym, jni_libs[i].name, strdup(sym_name));
            void* sym = zomdroid_emulation_bridge_jni_symbol(&jni_libs[i], box64_sym,
                                                             arg_types, ret_type);
            if (sym == NULL) {
                LOGE("Failed to create emulation bridge for jni symbol %s", sym_name);
                free(arg_types);
                return NULL;
            }
            free(arg_types);
            //LOGD("Successfully created emulation bridge for jni symbol %s at %p (target=%ld)", sym_name, sym, box64_sym);
            return sym;
        } else {
            if (strcmp(elib->name, "fmodintegration64") == 0) {
                // dlsym searches dependencies: without this guard HotSpot calls
                // libfmod.so's Android JNI_OnLoad and replaces its ART context.
                if (strcmp(sym_name, "JNI_OnLoad") == 0 || strcmp(sym_name, "JNI_OnUnload") == 0) {
                    LOG_REPORTED("[fmod-native] skipped dependency %s (FMOD initialized on ART)", sym_name);
                    return NULL;
                }
                if (strcmp(sym_name, "Java_fmod_javafmodJNI_FMOD_1System_1Create") == 0)
                    return native_fmod_create_bridge;
                if (strstr(sym_name, "getAudioDevices")) return stub_getAudioDevices;
            }
            void* resolved = loader_dlsym(handle, sym_name, __builtin_return_address(0));
            const char* diag = getenv("ZOMDROID_BULLET_DIAGNOSTIC");
            if (diag && strcmp(diag, "1") == 0 && strcmp(elib->name, "PZBullet64") == 0) {
                // On a miss, bionic's own words tell an absent symbol ("undefined symbol") from
                // a library that is no longer there ("invalid handle") - the distinction this
                // whole diagnostic exists for.
                const char* dl_msg = resolved ? NULL : dlerror();
                LOG_REPORTED("[bullet-diag] JVM lookup handle=%p symbol=%s resolved=%p native=1%s%s",
                             handle, sym_name, resolved,
                             dl_msg ? " dlerror=" : "", dl_msg ? dl_msg : "");
            }
            return resolved;
        }
    }

    return loader_dlsym(handle, sym_name, __builtin_return_address(0));
}

__attribute__((visibility("default"), used))
void *android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *extinfo) {
    //LOGE("android_dlopen_ext(filename=%s)", filename);
    if(strstr(filename, "vulkan.") && vulkan_driver_handle) {
        return vulkan_driver_handle;
    }
    return loader_android_dlopen_ext(filename, flags, extinfo, &android_dlopen_ext);
}

__attribute__((visibility("default"), used))
void *android_load_sphal_library(const char *filename, int flags) {
    //LOGD("android_load_sphal_library(filename=%s)", filename);
    if(strstr(filename, "vulkan.") && vulkan_driver_handle) {
        return vulkan_driver_handle;
    }
    char* ns_names[] = {"sphal", "vendor", "default"};
    struct android_namespace_t* sphal_ns = NULL;
    int i = 0;
    for (i = 0; i < sizeof(ns_names) / sizeof (char*); i++) {
        sphal_ns = android_get_exported_namespace(ns_names[i]);
        if (sphal_ns) break;
    }
    android_dlextinfo info;
    info.flags = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = sphal_ns;
    return android_dlopen_ext(filename, flags, &info);
}
