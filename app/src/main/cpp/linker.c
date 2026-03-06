#include <string.h>
#include <stdio.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <malloc.h>
#include <unistd.h>
#include <pthread.h>
#include "logger.h"
#include "emulation.h"
#include "zomdroid_globals.h"

#include "liblinkernsbypass/android_linker_ns.h"

#include "box64/src/include/librarian.h"
#include "box64/src/include/myalign.h"
#include "box64/src/include/elfloader.h"
#include "box64/src/include/library.h"


#define LOG_TAG "zomdroid-linker"

#define BUF_SIZE 1024

#define JNI_SIG_CACHE_SIZE 32 //64

typedef struct {
    char* sym;  // key
    char* sig;  // value (full method signature)
} JniSigCacheEntry;

static JniSigCacheEntry g_jni_sig_cache[JNI_SIG_CACHE_SIZE];
static int g_jni_sig_cache_next = 0;
static pthread_mutex_t g_jni_sig_cache_mutex = PTHREAD_MUTEX_INITIALIZER;

static const char* jni_sig_cache_get(const char* sym) {
    const char* result = NULL;
    pthread_mutex_lock(&g_jni_sig_cache_mutex);
    for (int i = 0; i < JNI_SIG_CACHE_SIZE; i++) {
        if (g_jni_sig_cache[i].sym && strcmp(g_jni_sig_cache[i].sym, sym) == 0) {
            result = g_jni_sig_cache[i].sig;
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
static void* (*loader_dlsym)(void* handle, const char* symbol, const void* caller);

static void* (*loader_android_dlopen_ext)(const char* filename,
                                          int flag,
                                          const android_dlextinfo* extinfo,
                                          const void* caller_addr);
static void* vulkan_driver_handle;
static void* vulkan_loader_handle;

static EmulatedLib jni_libs[] = {{.name = "PZClipper64"}, {.name = "PZBullet64"}, {.name = "PZBulletNoOpenGL64"}, {.name = "Lighting64"}, {.name = "PZPathFind64"}, {.name = "PZPopMan64"}, {.name = "fmodintegration64"}, { .name = "zomdroidtest"}, { .name = "RakNet64"}, { .name = "ZNetNoSteam"} };
static int jni_lib_count = sizeof (jni_libs) / sizeof (EmulatedLib);
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
int zomdroid_linker_init() {
    init_jni_libs();
    if (zomdroid_emulation_init() != 0) {
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
    const char* cached = jni_sig_cache_get(sym);
    if (cached) {
        // return a fresh copy because caller will free() it
        return strdup(cached);
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
    for (int a = 0; a < method_count; a++) {
        char* mName = NULL;
        char* mSig = NULL;
        char match = false;
        jvmti_err = (*g_zomdroid_jvmti_env)->GetMethodName(g_zomdroid_jvmti_env, methods[a], &mName, &mSig, NULL);
        if (jvmti_err != JVMTI_ERROR_NONE) {
            LOGW("Failed to get method name, error code %d", jvmti_err);
            continue;
        }
        if (strcmp(method_name, mName) == 0 && (method_sig_short == NULL || strstr(mSig, method_sig_short) != NULL)) {
            target_method = methods[a];
            method_sig = strdup(mSig);
            match = true;
        }
        jvmti_err = (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)mName);
        if (jvmti_err != JVMTI_ERROR_NONE) LOGW("Failed to deallocate JVM TI memory, error code: %d", jvmti_err);
        jvmti_err = (*g_zomdroid_jvmti_env)->Deallocate(g_zomdroid_jvmti_env, (unsigned char*)mSig);
        if (jvmti_err != JVMTI_ERROR_NONE) LOGW("Failed to deallocate JVM TI memory, error code: %d", jvmti_err);
        if (match) break;
    }

    if (target_method == NULL) {
        LOGE("Failed to find method %s with signature %s in class %s", method_name, (method_sig_short == NULL) ? "<null>" : method_sig_short, class_name);
        goto FAIL;
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

        // Already loaded once -> return cached handle.
        // This prevents repeated AddNeededLib/RunDeferredElfInit and reduces instability.
        if (jni_libs[i].handle != NULL) {
            return jni_libs[i].handle;
        }

        //trying to load native library
        if (strcmp(jni_libs[i].name, "fmodintegration64") != 0) { //later I should fix that. Java for some reason didn't see classes inside
            const char* base = strrchr(filename, '/');
            if (base)
                base++;
            else
                base = filename;

            char android_filename[BUF_SIZE] = {0};
            snprintf(android_filename, BUF_SIZE, "android/arm64-v8a/%s", base);

            if (access(android_filename, F_OK) == 0) {
                //LOGD("[linker] Native Android version of %s is found", android_filename);
                jni_libs[i].handle = loader_dlopen(android_filename, flags, __builtin_return_address(0));
                jni_libs[i].is_emulated = false;
                return jni_libs[i].handle;
            }
            LOGW("[linker] Native Android version of %s not found, loading through box64...", android_filename);
        }

        //elsewise loading in box64
        LOGE("[linker] Loading %s in box64...", filename);
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

__attribute__((visibility("default"), used))
void *dlsym(void *handle, const char *sym_name) {
    //LOGE("[linker] dlsym called with filename=%s", sym_name);
    for (int i = 0; i < jni_lib_count; i++) {
        struct library_s* lib = jni_libs[i].handle;
        EmulatedLib* elib = &jni_libs[i];
        if (sym_name == NULL || handle == NULL || lib != handle) continue;

        if (elib->is_emulated) {
            // 1) Stub for getAudioDevices
            if (strcmp(jni_libs[i].name, "fmodintegration64") == 0 &&
                strstr(sym_name, "getAudioDevices")) {

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
            free(method_sig);

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
            return loader_dlsym(handle, sym_name, __builtin_return_address(0));
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
