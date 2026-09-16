/*
 * macho_jnienv.c - the wrapper JNIEnv for dylibs that call variadic JNI functions.
 * See macho_jnienv.h for the why. Two mechanisms:
 *
 *  - 203 plain entries get a four-instruction thunk each, generated once into an executable
 *    page: replace the env argument with the real one, load the real function from the real
 *    table at the same index, jump. No C code runs, nothing else in the registers moves.
 *  - the 31 *V entries are C functions that read the Apple va_list (a pointer to 8-byte slots,
 *    every argument promoted: ints to 64 bits, floats to double) with the method's Java
 *    signature in hand, build a jvalue array and call the *A entry of the real table.
 */
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>

#include "logger.h"
#include "macho_jnienv.h"

#define LOG_TAG "zomdroid-macho"

#define LOG_REPORTED(fmt, ...) do {                  \
        LOGI(fmt __VA_OPT__(,) __VA_ARGS__);         \
        printf(fmt "\n" __VA_OPT__(,) __VA_ARGS__);  \
    } while (0)

typedef struct { const char* name; int kind; } macho_jni_entry_t;   /* kind: 0 thunk, 1 V shim, 2 reserved */
#include "macho_jni_table.h"

/* The wrapper object. `functions` first so `(*env)->X` works unchanged; `real` at offset 8 is
 * what the thunks load; the magic tells a wrapper from a real env in unwrap(). */
typedef struct {
    const struct JNINativeInterface_* functions;
    JNIEnv* real;
    uint64_t magic;
} macho_env_wrapper_t;

#define MACHO_ENV_MAGIC 0x564e454f4843414dull   /* "MACHOENV" */

static void* g_table[MACHO_JNI_TABLE_ENTRIES];
static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static int g_table_ok = 0;
static __thread macho_env_wrapper_t t_wrapper;

static macho_jnienv_signature_fn g_signature_fn = NULL;

void macho_jnienv_set_signature_provider(macho_jnienv_signature_fn fn) {
    g_signature_fn = fn;
}

JNIEnv* macho_jnienv_unwrap(JNIEnv* env) {
    const macho_env_wrapper_t* w = (const macho_env_wrapper_t*)env;
    if (w && (const void*)w->functions == (const void*)g_table) return w->real;
    return env;
}

/* --- method signatures, cached by jmethodID ------------------------------------------------ */

#define SIG_SLOTS 1024
static struct { jmethodID mid; char* sig; } g_sigs[SIG_SLOTS];
static pthread_mutex_t g_sig_lock = PTHREAD_MUTEX_INITIALIZER;

static const char* signature_for(jmethodID mid) {
    size_t h = (((uintptr_t)mid) >> 3) * 0x9E3779B97F4A7C15ull >> 40;
    pthread_mutex_lock(&g_sig_lock);
    for (size_t probe = 0; probe < SIG_SLOTS; probe++) {
        size_t i = (h + probe) & (SIG_SLOTS - 1);
        if (g_sigs[i].mid == mid) { const char* s = g_sigs[i].sig; pthread_mutex_unlock(&g_sig_lock); return s; }
        if (g_sigs[i].mid == NULL) {
            const char* fresh = g_signature_fn ? g_signature_fn(mid) : NULL;
            if (!fresh) { pthread_mutex_unlock(&g_sig_lock); return NULL; }
            g_sigs[i].mid = mid;
            g_sigs[i].sig = strdup(fresh);
            const char* s = g_sigs[i].sig;
            pthread_mutex_unlock(&g_sig_lock);
            return s;
        }
    }
    pthread_mutex_unlock(&g_sig_lock);
    return NULL;   /* table full: unlikely, the game binds a few hundred methods */
}

#define MACHO_JNI_MAX_ARGS 64

/* Apple va_list -> jvalue[]. Returns the argument count, or -1 when the call cannot be made. */
static int unpack(jmethodID mid, const void* apple_va, jvalue* out, const char* who) {
    const char* sig = signature_for(mid);
    if (!sig || sig[0] != '(') {
        LOG_REPORTED("[macho] %s: no signature for method %p, call dropped", who, (void*)mid);
        return -1;
    }
    const uint64_t* slot = (const uint64_t*)apple_va;
    int n = 0;
    for (const char* p = sig + 1; *p && *p != ')'; ) {
        if (n == MACHO_JNI_MAX_ARGS) {
            LOG_REPORTED("[macho] %s: too many arguments in %s, call dropped", who, sig);
            return -1;
        }
        uint64_t raw = slot[n];
        switch (*p) {
        case 'Z': out[n].z = (jboolean)(raw & 0xFF); p++; break;
        case 'B': out[n].b = (jbyte)raw; p++; break;
        case 'C': out[n].c = (jchar)raw; p++; break;
        case 'S': out[n].s = (jshort)raw; p++; break;
        case 'I': out[n].i = (jint)raw; p++; break;
        case 'J': out[n].j = (jlong)raw; p++; break;
        case 'F': { double d; memcpy(&d, &raw, 8); out[n].f = (jfloat)d; p++; break; }
        case 'D': { double d; memcpy(&d, &raw, 8); out[n].d = d; p++; break; }
        case 'L':
            out[n].l = (jobject)(uintptr_t)raw;
            while (*p && *p != ';') p++;
            if (*p == ';') p++;
            break;
        case '[':
            out[n].l = (jobject)(uintptr_t)raw;
            while (*p == '[') p++;
            if (*p == 'L') { while (*p && *p != ';') p++; if (*p == ';') p++; }
            else if (*p) p++;
            break;
        default:
            LOG_REPORTED("[macho] %s: unexpected '%c' in %s, call dropped", who, *p, sig);
            return -1;
        }
        n++;
    }
    return n;
}

/* --- the 31 V shims -------------------------------------------------------------------- */

#define SHIM_INSTANCE(RET, NAME, ZERO)                                                     \
static RET NAME##V_shim(JNIEnv* wenv, jobject obj, jmethodID mid, void* va) {              \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return ZERO;                                    \
    return (*env)->NAME##A(env, obj, mid, a); }
#define SHIM_INSTANCE_VOID(NAME)                                                           \
static void NAME##V_shim(JNIEnv* wenv, jobject obj, jmethodID mid, void* va) {             \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return;                                         \
    (*env)->NAME##A(env, obj, mid, a); }
#define SHIM_NONVIRTUAL(RET, NAME, ZERO)                                                   \
static RET NAME##V_shim(JNIEnv* wenv, jobject obj, jclass cls, jmethodID mid, void* va) {  \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return ZERO;                                    \
    return (*env)->NAME##A(env, obj, cls, mid, a); }
#define SHIM_NONVIRTUAL_VOID(NAME)                                                         \
static void NAME##V_shim(JNIEnv* wenv, jobject obj, jclass cls, jmethodID mid, void* va) { \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return;                                         \
    (*env)->NAME##A(env, obj, cls, mid, a); }
#define SHIM_STATIC(RET, NAME, ZERO)                                                       \
static RET NAME##V_shim(JNIEnv* wenv, jclass cls, jmethodID mid, void* va) {               \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return ZERO;                                    \
    return (*env)->NAME##A(env, cls, mid, a); }
#define SHIM_STATIC_VOID(NAME)                                                             \
static void NAME##V_shim(JNIEnv* wenv, jclass cls, jmethodID mid, void* va) {              \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return;                                         \
    (*env)->NAME##A(env, cls, mid, a); }

SHIM_STATIC(jobject, NewObject, NULL)
SHIM_INSTANCE(jobject,  CallObjectMethod,  NULL)
SHIM_INSTANCE(jboolean, CallBooleanMethod, JNI_FALSE)
SHIM_INSTANCE(jbyte,    CallByteMethod,    0)
SHIM_INSTANCE(jchar,    CallCharMethod,    0)
SHIM_INSTANCE(jshort,   CallShortMethod,   0)
SHIM_INSTANCE(jint,     CallIntMethod,     0)
SHIM_INSTANCE(jlong,    CallLongMethod,    0)
SHIM_INSTANCE(jfloat,   CallFloatMethod,   0.0f)
SHIM_INSTANCE(jdouble,  CallDoubleMethod,  0.0)
SHIM_INSTANCE_VOID(CallVoidMethod)
SHIM_NONVIRTUAL(jobject,  CallNonvirtualObjectMethod,  NULL)
SHIM_NONVIRTUAL(jboolean, CallNonvirtualBooleanMethod, JNI_FALSE)
SHIM_NONVIRTUAL(jbyte,    CallNonvirtualByteMethod,    0)
SHIM_NONVIRTUAL(jchar,    CallNonvirtualCharMethod,    0)
SHIM_NONVIRTUAL(jshort,   CallNonvirtualShortMethod,   0)
SHIM_NONVIRTUAL(jint,     CallNonvirtualIntMethod,     0)
SHIM_NONVIRTUAL(jlong,    CallNonvirtualLongMethod,    0)
SHIM_NONVIRTUAL(jfloat,   CallNonvirtualFloatMethod,   0.0f)
SHIM_NONVIRTUAL(jdouble,  CallNonvirtualDoubleMethod,  0.0)
SHIM_NONVIRTUAL_VOID(CallNonvirtualVoidMethod)
SHIM_STATIC(jobject,  CallStaticObjectMethod,  NULL)
SHIM_STATIC(jboolean, CallStaticBooleanMethod, JNI_FALSE)
SHIM_STATIC(jbyte,    CallStaticByteMethod,    0)
SHIM_STATIC(jchar,    CallStaticCharMethod,    0)
SHIM_STATIC(jshort,   CallStaticShortMethod,   0)
SHIM_STATIC(jint,     CallStaticIntMethod,     0)
SHIM_STATIC(jlong,    CallStaticLongMethod,    0)
SHIM_STATIC(jfloat,   CallStaticFloatMethod,   0.0f)
SHIM_STATIC(jdouble,  CallStaticDoubleMethod,  0.0)
SHIM_STATIC_VOID(CallStaticVoidMethod)

#define SHIM_ENTRY(NAME) { #NAME "V", (void*)NAME##V_shim }
static const struct { const char* name; void* fn; } g_vshims[] = {
    SHIM_ENTRY(NewObject),
    SHIM_ENTRY(CallObjectMethod), SHIM_ENTRY(CallBooleanMethod), SHIM_ENTRY(CallByteMethod),
    SHIM_ENTRY(CallCharMethod), SHIM_ENTRY(CallShortMethod), SHIM_ENTRY(CallIntMethod),
    SHIM_ENTRY(CallLongMethod), SHIM_ENTRY(CallFloatMethod), SHIM_ENTRY(CallDoubleMethod),
    SHIM_ENTRY(CallVoidMethod),
    SHIM_ENTRY(CallNonvirtualObjectMethod), SHIM_ENTRY(CallNonvirtualBooleanMethod),
    SHIM_ENTRY(CallNonvirtualByteMethod), SHIM_ENTRY(CallNonvirtualCharMethod),
    SHIM_ENTRY(CallNonvirtualShortMethod), SHIM_ENTRY(CallNonvirtualIntMethod),
    SHIM_ENTRY(CallNonvirtualLongMethod), SHIM_ENTRY(CallNonvirtualFloatMethod),
    SHIM_ENTRY(CallNonvirtualDoubleMethod), SHIM_ENTRY(CallNonvirtualVoidMethod),
    SHIM_ENTRY(CallStaticObjectMethod), SHIM_ENTRY(CallStaticBooleanMethod),
    SHIM_ENTRY(CallStaticByteMethod), SHIM_ENTRY(CallStaticCharMethod),
    SHIM_ENTRY(CallStaticShortMethod), SHIM_ENTRY(CallStaticIntMethod),
    SHIM_ENTRY(CallStaticLongMethod), SHIM_ENTRY(CallStaticFloatMethod),
    SHIM_ENTRY(CallStaticDoubleMethod), SHIM_ENTRY(CallStaticVoidMethod),
};

/* --- the table: thunks for everything else ----------------------------------------------- */

typedef struct macho_vm_wrapper {
    const struct JNIInvokeInterface_* functions;
    JavaVM* real;
    struct macho_vm_wrapper* next;
} macho_vm_wrapper;
static macho_vm_wrapper* g_vms;
static pthread_mutex_t g_vm_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM* real_vm(JavaVM* vm) { return ((macho_vm_wrapper*)vm)->real; }
static jint vm_destroy(JavaVM* vm) { JavaVM* r = real_vm(vm); return (*r)->DestroyJavaVM(r); }
static jint vm_detach(JavaVM* vm) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->DetachCurrentThread(r);
    if (result == JNI_OK) memset(&t_wrapper, 0, sizeof(t_wrapper));
    return result;
}
static jint vm_getenv(JavaVM* vm, void** output, jint version) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->GetEnv(r, output, version);
    /* JVMTI and other GetEnv interfaces are not JNIEnv objects. */
    if (result == JNI_OK && ((uint32_t)version >> 16) < 0x3000)
        *output = macho_jnienv_wrap((JNIEnv*)*output);
    return result;
}
static jint vm_attach(JavaVM* vm, void** output, void* args) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->AttachCurrentThread(r, output, args);
    if (result == JNI_OK) *output = macho_jnienv_wrap((JNIEnv*)*output);
    return result;
}
static jint vm_attach_daemon(JavaVM* vm, void** output, void* args) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->AttachCurrentThreadAsDaemon(r, output, args);
    if (result == JNI_OK) *output = macho_jnienv_wrap((JNIEnv*)*output);
    return result;
}
static const struct JNIInvokeInterface_ g_vm_table = {
    .DestroyJavaVM = vm_destroy, .AttachCurrentThread = vm_attach,
    .DetachCurrentThread = vm_detach, .GetEnv = vm_getenv,
    .AttachCurrentThreadAsDaemon = vm_attach_daemon
};
JavaVM* macho_javavm_wrap(JavaVM* real) {
    if (!real || *real == &g_vm_table) return real;
    pthread_mutex_lock(&g_vm_lock);
    macho_vm_wrapper* w = g_vms;
    while (w && w->real != real) w = w->next;
    if (!w) {
        w = calloc(1, sizeof(*w));
        if (w) { w->functions = &g_vm_table; w->real = real; w->next = g_vms; g_vms = w; }
    }
    pthread_mutex_unlock(&g_vm_lock);
    return (JavaVM*)w;
}
static jint GetJavaVM_shim(JNIEnv* wrapped, JavaVM** output) {
    JNIEnv* real = macho_jnienv_unwrap(wrapped);
    JavaVM* vm = NULL;
    jint result = (*real)->GetJavaVM(real, &vm);
    if (result != JNI_OK) return result;
    *output = macho_javavm_wrap(vm);
    return *output ? JNI_OK : JNI_ENOMEM;
}

static void build_table(void) {
    long page = sysconf(_SC_PAGESIZE);
    if (page < 4096) page = 4096;
    size_t need = (size_t)MACHO_JNI_TABLE_ENTRIES * 16;
    size_t size = (need + (size_t)page - 1) & ~((size_t)page - 1);
    uint32_t* code = mmap(NULL, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (code == MAP_FAILED) {
        LOG_REPORTED("[macho] JNIEnv wrapper: no executable page");
        return;
    }
    for (int i = 0; i < MACHO_JNI_TABLE_ENTRIES; i++) {
        const macho_jni_entry_t* e = &macho_jni_table[i];
        if (e->kind == 2) { g_table[i] = NULL; continue; }
        if (strcmp(e->name, "GetJavaVM") == 0) { g_table[i] = (void*)GetJavaVM_shim; continue; }
        if (e->kind == 1) {
            void* fn = NULL;
            for (size_t k = 0; k < sizeof(g_vshims) / sizeof(g_vshims[0]); k++)
                if (strcmp(g_vshims[k].name, e->name) == 0) { fn = g_vshims[k].fn; break; }
            if (!fn) { LOG_REPORTED("[macho] JNIEnv wrapper: no shim for %s", e->name); return; }
            g_table[i] = fn;
            continue;
        }
        /* ldr x0, [x0, #8] ; ldr x16, [x0] ; ldr x16, [x16, #i*8] ; br x16 */
        uint32_t* t = code + i * 4;
        t[0] = 0xF9400400u;
        t[1] = 0xF9400010u;
        t[2] = 0xF9400210u | ((uint32_t)i << 10);
        t[3] = 0xD61F0200u;
        g_table[i] = t;
    }
    __builtin___clear_cache((char*)code, (char*)code + size);
    g_table_ok = 1;
    LOGI("[macho] JNIEnv wrapper ready: %d entries, %zu V shims", MACHO_JNI_TABLE_ENTRIES,
         sizeof(g_vshims) / sizeof(g_vshims[0]));
}

JNIEnv* macho_jnienv_wrap(JNIEnv* real) {
    if (!real) return NULL;
    if ((const void*)*real == (const void*)g_table) return real;
    pthread_once(&g_once, build_table);
    if (!g_table_ok) return NULL;
    t_wrapper.functions = (const struct JNINativeInterface_*)g_table;
    t_wrapper.real = real;
    t_wrapper.magic = MACHO_ENV_MAGIC;
    return (JNIEnv*)&t_wrapper;
}

int macho_jnienv_ready(void) {
    pthread_once(&g_once, build_table);
    return g_table_ok;
}
