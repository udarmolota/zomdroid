#ifndef ZOMDROID_MACHO_LOADER_H
#define ZOMDROID_MACHO_LOADER_H
/*
 * In-process loader for the game's macOS ARM64 dylibs (libLighting.dylib and friends).
 *
 * Why this exists: the JNI libraries Project Zomboid ships for Linux are x86_64 and run through
 * box64; the ARM64 builds TIS puts in android/arm64-v8a are stale (Lighting is an older engine,
 * PopMan cannot save). The only current ARM64 builds are the macOS ones, and they are plain
 * arm64 code with libc/libc++ imports only - no Objective-C, no thread-locals, no pointer
 * authentication. So: map the arm64 slice, apply dyld's rebase/bind opcodes against bionic and
 * libc++_shared, read the export trie, run the static constructors, and the JVM gets the JNI
 * functions as if the file had been a .so. See tools/macos-native-libs-brief.md for the plan
 * and the ABI notes; macho_bridge.c covers the Apple-vs-Linux calling convention differences.
 *
 * Deliberately not supported (each is a one-line "rejected" in the log, never a crash):
 * arm64e, chained fixups, TLV, Objective-C, dependencies other than libc++/libSystem, and any
 * import bionic or libc++_shared cannot provide. C++ exceptions thrown inside a dylib abort the
 * process: the files carry Apple compact unwind info only, which the Android unwinder does not
 * read. They do not throw in normal operation.
 */
#include <stddef.h>
#include <stdint.h>
#include "emulation.h"

typedef struct macho_lib macho_lib_t;

/* Loads the arm64 slice of the dylib at path. Prints its own "[macho] ..." lines (loaded or
 * rejected, with the reason); returns NULL on any rejection. The mapping is never freed: the JVM
 * keeps JNI libraries for the life of the process anyway. */
macho_lib_t* macho_load(const char* path);

/* Export lookup by C name, i.e. without the Mach-O leading underscore: "Java_..." finds
 * "_Java_...". NULL when the dylib does not export it. */
void* macho_dlsym(macho_lib_t* lib, const char* name);

/* File name the library was loaded from (for log lines). */
const char* macho_lib_name(const macho_lib_t* lib);

/* True when per-symbol and per-instruction detail should be logged ([macho] bridge, [jni-bind],
 * LDAPR offsets): ZOMDROID_NATIVE_VERBOSE=1, which the launcher sets for debug builds and for
 * instances with Debug on. Release reports keep the library-level lines - loaded, rejected and
 * why - and lose the dozens of lines per launch that only the loader's author reads. */
int macho_verbose(void);

/* True when the dylib calls variadic JNI functions and must see the wrapper JNIEnv
 * (macho_jnienv.h); the bridge then substitutes it on every entry. */
int macho_lib_wraps_env(const macho_lib_t* lib);

/*
 * Apple-ABI bridge for a JNI entry point. arg_types is the box64-style letter string the linker
 * derives from the Java signature ('p' pointer, 'i' jint, 'I' jlong, 'f' jfloat, 'd' jdouble,
 * 'c' jbyte, 'C' jboolean, 'w' jshort, 'W' jchar), env and class included. Returns target itself
 * when Apple and Linux agree on how this function is called, a generated shim when they do not
 * (stack-passed arguments, sub-32-bit integer arguments that Apple expects extended, or
 * wrap_env set, which substitutes the wrapper JNIEnv for the first argument), and NULL if the
 * shim could not be built. Code pages are owned by lib, like the box64 bridges.
 */
void* macho_jni_bridge(EmulatedLib* lib, void* target, const char* arg_types, const char* sym_name,
                       int wrap_env);

#endif /* ZOMDROID_MACHO_LOADER_H */
