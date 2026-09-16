#ifndef ZOMDROID_MACHO_JNIENV_H
#define ZOMDROID_MACHO_JNIENV_H
/*
 * A JNIEnv the macOS dylibs can call variadic JNI functions through.
 *
 * PopMan, PathFind, Clipper and Bullet use the C++ JNIEnv_ wrappers (env->CallIntMethod(obj,
 * mid, ...)), which are inline in jni.h: they va_start and call the *V entry of the function
 * table with a va_list. On Apple arm64 a va_list is a plain pointer to the variadic arguments
 * laid out as 8-byte slots on the stack; on Linux it is a five-field struct describing the
 * register save area. HotSpot's CallIntMethodV expects the Linux one, so what the dylib hands it
 * is garbage. Lighting does not use these wrappers, which is why it works without any of this.
 *
 * The fix: hand such a dylib a wrapper JNIEnv whose function table forwards every entry to the
 * real environment - with the real env pointer substituted, since HotSpot derives its thread
 * from it - except the 31 *V entries, which read the Apple va_list themselves, using the Java
 * method's signature to know each argument's type, and call the *A entry with a jvalue array.
 */
#include "openjdk/jni.h"

/* The wrapper for this thread's real env; created on first use, reused afterwards. */
JNIEnv* macho_jnienv_wrap(JNIEnv* real);
int macho_jnienv_ready(void);
JavaVM* macho_javavm_wrap(JavaVM* real);

/* The real env behind a wrapper (or the argument itself when it is not one of ours). */
JNIEnv* macho_jnienv_unwrap(JNIEnv* env);

/* Where method signatures come from. The linker installs a JVMTI-backed provider at init; the
 * standalone harness installs a fake. Must return "(...)R" or NULL; the string is copied. */
typedef const char* (*macho_jnienv_signature_fn)(jmethodID mid);
void macho_jnienv_set_signature_provider(macho_jnienv_signature_fn fn);

#endif /* ZOMDROID_MACHO_JNIENV_H */
