/*
 * macho_test.c - standalone on-device check of the Mach-O loader stack, no JVM, no app.
 *   - loads each dylib named on the command line and resolves a few known exports;
 *   - for libprobe.dylib (built from scratchpad/unwind/probe.cpp) runs the throw/catch tests
 *     through the registered unwind tables;
 *   - exercises the wrapper JNIEnv against a fake JNI function table;
 *   - builds the Apple-ABI bridges for the long LightingJNI signatures.
 * Runs from /data/local/tmp with libc++_shared.so and libzomdroid_macho_eh.so next to it.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#include "macho_loader.h"
#include "macho_jnienv.h"
#include "macho_pathfind.h"
#include <pthread.h>

static int failures = 0;
#define CHECK(cond, ...) do { if (cond) { printf("PASS: " __VA_ARGS__); printf("\n"); } \
                              else { printf("FAIL: " __VA_ARGS__); printf("\n"); failures++; } } while (0)

/* The app's code-page allocator lives in emulation.c (box64). Here: one page per shim. */
void* zomdroid_emulation_install_code(EmulatedLib* lib, const uint32_t* code, int code_size) {
    (void)lib;
    void* m = mmap(NULL, 4096, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (m == MAP_FAILED) return NULL;
    memcpy(m, code, (size_t)code_size);
    __builtin___clear_cache((char*)m, (char*)m + code_size);
    return m;
}

/* ---------------------------------------------------------------- exception tests (libprobe*) */
#include <sys/wait.h>
#include <unistd.h>

/* Each call runs in a forked child, so an abort in one test is reported and the rest still run. */
static void fork_check(macho_lib_t* l, const char* sym, int arg, int expect, const char* what) {
    int (*fn)(int) = macho_dlsym(l, sym);
    if (!fn) { CHECK(0, "%s: export %s missing", what, sym); return; }
    fflush(stdout);
    pid_t pid = fork();
    if (pid == 0) {
        int r = fn(arg);
        _exit(r == expect ? 0 : 100 + (r & 0x7F));
    }
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) CHECK(1, "%s", what);
    else if (WIFEXITED(status)) CHECK(0, "%s: wrong result (code %d)", what, WEXITSTATUS(status));
    else CHECK(0, "%s: died with signal %d", what, WIFSIGNALED(status) ? WTERMSIG(status) : -1);
}

static void run_probe_tests(macho_lib_t* l) {
    fork_check(l, "t_no_throw",       41, 42,  "no-throw path");
    fork_check(l, "t_catch_int",      21, 42,  "throw/catch int in one frame");
    fork_check(l, "t_nested_cleanup",  5, 105, "throw 3 frames down, 4 destructors ran");
    fork_check(l, "t_runtime_error",   0, 7,   "derived class caught by base reference, virtual call");
    fork_check(l, "t_rethrow",         0, 15,  "rethrow from an inner handler");
    fork_check(l, "t_catch_all",       0, 3,   "catch(...) skips the wrong handler");
}

static void run_probe2_tests(macho_lib_t* l) {
    fork_check(l, "p_throw_from_callee",  0, 50, "D: exception from a callee, caught here");
    fork_check(l, "p_throw_in_handler",   0, 20, "A: new throw inside a handler, outer catch");
    fork_check(l, "p_rethrow_via_call",   0, 30, "B: rethrow performed by a called function");
    fork_check(l, "p_rethrow_to_caller",  0, 45, "C: callee catches and rethrows, caller catches");
    fork_check(l, "p_rethrow_same_frame", 0, 65, "E: rethrow in the same frame (original shape)");
}

/* ---------------------------------------------------------------- JNIEnv wrapper test */
static JNIEnv* g_fake_env;
static const char* fake_signature(jmethodID mid) {
    if ((uintptr_t)mid == 0x1001) return "(IFJLjava/lang/String;Z)I";
    if ((uintptr_t)mid == 0x1002) return "(D)V";
    return NULL;
}
static int g_seen_env_ok = 0, g_static_void_called = 0;
static JavaVM* fake_vm;
static jint fake_GetJavaVM(JNIEnv* env, JavaVM** out) { if (env != g_fake_env) return JNI_ERR; *out = fake_vm; return JNI_OK; }
static jint fake_GetEnv(JavaVM* vm, void** out, jint version) {
    if (vm != fake_vm) return JNI_ERR;
    if (version == 0x30010000) { *out = (void*)0x1234; return JNI_OK; }
    if (version != JNI_VERSION_1_6) { *out = NULL; return JNI_EVERSION; }
    *out = g_fake_env; return JNI_OK;
}
static jint fake_Attach(JavaVM* vm, void** out, void* args) { (void)args; return fake_GetEnv(vm, out, JNI_VERSION_1_6); }
static jint fake_Detach(JavaVM* vm) { return vm == fake_vm ? JNI_OK : JNI_ERR; }
static void* worker_vm(void* arg) {
    JavaVM* vm = arg;
    void* env = NULL;
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK || env == g_fake_env
            || macho_jnienv_unwrap(env) != g_fake_env) return (void*)1;
    if ((*vm)->DetachCurrentThread(vm) != JNI_OK) return (void*)2;
    if ((*vm)->AttachCurrentThreadAsDaemon(vm, &env, NULL) != JNI_OK
            || macho_jnienv_unwrap(env) != g_fake_env) return (void*)3;
    return (void*)(intptr_t)((*vm)->DetachCurrentThread(vm) != JNI_OK);
}
static jint JNICALL fake_GetVersion(JNIEnv* env) { g_seen_env_ok = (env == g_fake_env); return 0x00010006; }
static jint JNICALL fake_CallIntMethodA(JNIEnv* env, jobject obj, jmethodID mid, const jvalue* a) {
    if (env != g_fake_env || obj != (jobject)0x2222 || (uintptr_t)mid != 0x1001) return -1;
    if (a[0].i != 7 || a[1].f != 2.5f || a[2].j != 0x123456789abLL || a[3].l != (jobject)0x3333 || a[4].z != JNI_TRUE) return -2;
    return 99;
}
static void JNICALL fake_CallStaticVoidMethodA(JNIEnv* env, jclass cls, jmethodID mid, const jvalue* a) {
    if (env == g_fake_env && cls == (jclass)0x4444 && (uintptr_t)mid == 0x1002 && a[0].d == 3.25) g_static_void_called = 1;
}

static void run_jnienv_tests(void) {
    static struct JNINativeInterface_ table;
    static const struct JNINativeInterface_* fake_env_obj;
    memset(&table, 0, sizeof(table));
    table.GetVersion = fake_GetVersion;
    table.GetJavaVM = fake_GetJavaVM;
    table.CallIntMethodA = fake_CallIntMethodA;
    table.CallStaticVoidMethodA = fake_CallStaticVoidMethodA;
    fake_env_obj = &table;
    g_fake_env = (JNIEnv*)&fake_env_obj;

    macho_jnienv_set_signature_provider(fake_signature);
    JNIEnv* w = macho_jnienv_wrap(g_fake_env);
    CHECK(w && w != g_fake_env, "wrapper env created");
    CHECK(macho_jnienv_unwrap(w) == g_fake_env, "unwrap gives the real env");
    CHECK(macho_jnienv_wrap(w) == w, "wrapping a wrapper is a no-op");
    JNIEnv* w2 = macho_jnienv_wrap(g_fake_env);
    CHECK(w2 == w, "same thread, same wrapper");
    static const struct JNIInvokeInterface_ invoke = {
        .GetEnv = fake_GetEnv, .AttachCurrentThread = fake_Attach,
        .AttachCurrentThreadAsDaemon = fake_Attach, .DetachCurrentThread = fake_Detach
    };
    static const struct JNIInvokeInterface_* real_vm_object;
    real_vm_object = &invoke; fake_vm = (JavaVM*)&real_vm_object;
    JavaVM* vm = NULL;
    CHECK((*w)->GetJavaVM(w, &vm) == JNI_OK && vm != fake_vm, "GetJavaVM returns stable wrapper");
    CHECK(macho_javavm_wrap(fake_vm) == vm && macho_javavm_wrap(vm) == vm, "JavaVM wrapping is idempotent");
    void* returned = NULL;
    CHECK((*vm)->GetEnv(vm, &returned, JNI_VERSION_1_6) == JNI_OK && returned == w, "JavaVM GetEnv wraps JNIEnv");
    CHECK((*vm)->GetEnv(vm, &returned, 0x30010000) == JNI_OK && returned == (void*)0x1234, "JVMTI GetEnv remains untouched");
    CHECK((*vm)->GetEnv(vm, &returned, -1) == JNI_EVERSION && !returned, "GetEnv failure preserved");
    pthread_t thread;
    int created = pthread_create(&thread, NULL, worker_vm, vm);
    void* result = (void*)-1;
    if (!created) pthread_join(thread, &result);
    CHECK(!created && !result, "worker attach/detach/daemon receives wrapped JNIEnv");

    jint v = (*w)->GetVersion(w);
    CHECK(v == 0x00010006 && g_seen_env_ok, "plain entry thunk forwards with the real env");

    /* An Apple va_list: 8-byte slots, ints widened, float promoted to double. */
    uint64_t slots[5];
    slots[0] = 7;
    double d = 2.5; memcpy(&slots[1], &d, 8);
    slots[2] = 0x123456789abULL;
    slots[3] = 0x3333;
    slots[4] = 1;
    jint (*callIntV)(JNIEnv*, jobject, jmethodID, void*) = (void*)(*w)->CallIntMethodV;
    jint r = callIntV(w, (jobject)0x2222, (jmethodID)0x1001, slots);
    CHECK(r == 99, "CallIntMethodV shim unpacked (I F J L Z) from an Apple va_list: %d", r);

    double d2 = 3.25; uint64_t s2[1]; memcpy(&s2[0], &d2, 8);
    void (*callStaticVoidV)(JNIEnv*, jclass, jmethodID, void*) = (void*)(*w)->CallStaticVoidMethodV;
    callStaticVoidV(w, (jclass)0x4444, (jmethodID)0x1002, s2);
    CHECK(g_static_void_called, "CallStaticVoidMethodV shim reached the A entry");

    jint (*noSig)(JNIEnv*, jobject, jmethodID, void*) = (void*)(*w)->CallIntMethodV;
    r = noSig(w, (jobject)0x2222, (jmethodID)0x9999, slots);
    CHECK(r == 0, "unknown method id: call dropped, zero returned: %d", r);
}

/* ---------------------------------------------------------------- bridge shims (Lighting) */
static void run_bridge_tests(macho_lib_t* l) {
    static const struct { const char* sym; const char* types; } sigs[] = {
        { "stateEndFrame",              "ppfffffffCfi" },
        { "playerSet",                  "ppfffffCCCCfff" },
        { "squareSet",                  "ppiCCCiIIiC" },
        { "squareSetLightTransmission", "ppffffffffffffffffffff" },
        { "addLight",                   "ppiiiiifffiC" },
        { "addTempLight",               "ppiiiiifffi" },
        { "addRoomLight",               "ppiIIiiiiiC" },
        { "updateTorch",                "ppiffffffffffCfi" },
        { "getDarkMulti",               "ppi" },
    };
    void* target = macho_dlsym(l, "Java_zombie_iso_LightingJNI_stateEndFrame");
    if (!target) return;
    EmulatedLib dummy;
    memset(&dummy, 0, sizeof(dummy));
    int built = 0;
    for (size_t k = 0; k < sizeof(sigs) / sizeof(sigs[0]); k++) {
        void* s = macho_jni_bridge(&dummy, target, sigs[k].types, sigs[k].sym, 0);
        if (s) built++;
        void* s2 = macho_jni_bridge(&dummy, target, sigs[k].types, sigs[k].sym, 1);
        if (s2 && s2 != target) built++;
    }
    CHECK(built == 18, "Lighting bridges built with and without env wrapping: %d/18", built);
}

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "usage: macho_test <dylib>...\n"); return 2; }
    setvbuf(stdout, NULL, _IONBF, 0);

    run_jnienv_tests();
    const char* option_path = "pathfind-options-test.ini";
    FILE* option = fopen(option_path, "w");
    if (!option) return 2;
    fputs("Version=1\nOther=true\nPathfind.UseNativeCode=false\n", option); fclose(option);
    CHECK(macho_pathfind_enable_option(option_path), "PathFind option published atomically");
    option = fopen(option_path, "r");
    char content[256] = {0};
    if (option) { fread(content, 1, sizeof(content)-1, option); fclose(option); }
    CHECK(strstr(content, "Other=true") && strstr(content, "Pathfind.UseNativeCode=true"), "PathFind update preserves unrelated options");
    CHECK(!macho_pathfind_enable_option("missing-directory/options.ini"), "missing options cannot enable native PathFind");

    for (int i = 1; i < argc; i++) {
        printf("--- %s\n", argv[i]);
        macho_lib_t* l = macho_load(argv[i]);
        if (!l) { CHECK(0, "required test library rejected: %s", argv[i]); continue; }
        if (strstr(argv[i], "libPZPopMan") || strstr(argv[i], "libPZPathFind"))
            CHECK(macho_lib_wraps_env(l), "local JNIEnv symbols detected: %s", argv[i]);
        static const char* const probes[] = {
            "Java_zombie_iso_LightingJNI_stateEndFrame",
            "Java_zombie_popman_ZombiePopulationManager_n_1saveCell",
            "Java_zombie_ai_astar_AStarPathFinder_n_1init",
            "Java_zombie_pathfind_PathfindNative_n_1init",
            "t_catch_int",
        };
        for (size_t k = 0; k < sizeof(probes) / sizeof(probes[0]); k++) {
            void* a = macho_dlsym(l, probes[k]);
            if (a) printf("  %-58s %p\n", probes[k], a);
        }
        if (strstr(argv[i], "libprobe2")) run_probe2_tests(l);
        else if (strstr(argv[i], "libprobe")) run_probe_tests(l);
        if (strstr(argv[i], "libLighting")) run_bridge_tests(l);
    }
    printf("done, %d failure(s)\n", failures);
    return failures ? 1 : 0;
}
