#include <stdlib.h>

#include "openjdk/jni.h"
#include "zomdroid_globals.h"
#include "wrapped_jni.h"
#include "logger.h"

#include "box64/src/include/myalign.h"

#define LOG_TAG "zomdroid-wrapped-jni"

static void ensure_env() {
    if (g_zomdroid_jni_env == NULL) {
        jint res = (*g_zomdroid_jvm)->GetEnv(g_zomdroid_jvm, (void**)&g_zomdroid_jni_env, JNI_VERSION_1_6);
        if (g_zomdroid_jni_env == NULL) {
            LOGE("Failed to get JNIEnv*, error code: %d", res);
            abort();
        }
    }
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_GetVersion(__attribute__((unused)) JNIEnv *env) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetVersion(g_zomdroid_jni_env);
}

__attribute__((visibility("default"), used))
jclass zomdroid_jni_DefineClass(__attribute__((unused)) JNIEnv *env, const char *name, jobject loader, const jbyte *buf, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->DefineClass(g_zomdroid_jni_env, name, loader, buf, len);
}

__attribute__((visibility("default"), used))
jclass zomdroid_jni_FindClass(__attribute__((unused)) JNIEnv *env, const char *name) {
    ensure_env();
    return (*g_zomdroid_jni_env)->FindClass(g_zomdroid_jni_env, name);
}

__attribute__((visibility("default"), used))
jmethodID zomdroid_jni_FromReflectedMethod(__attribute__((unused)) JNIEnv *env, jobject method) {
    ensure_env();
    return (*g_zomdroid_jni_env)->FromReflectedMethod(g_zomdroid_jni_env, method);
}

__attribute__((visibility("default"), used))
jfieldID zomdroid_jni_FromReflectedField(__attribute__((unused)) JNIEnv *env, jobject field) {
    ensure_env();
    return (*g_zomdroid_jni_env)->FromReflectedField(g_zomdroid_jni_env, field);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_ToReflectedMethod(__attribute__((unused)) JNIEnv *env, jclass cls, jmethodID methodID, jboolean isStatic) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ToReflectedMethod(g_zomdroid_jni_env, cls, methodID, isStatic);
}

__attribute__((visibility("default"), used))
jclass zomdroid_jni_GetSuperclass(__attribute__((unused)) JNIEnv *env, jclass sub) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetSuperclass(g_zomdroid_jni_env, sub);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_IsAssignableFrom(__attribute__((unused)) JNIEnv *env, jclass sub, jclass sup) {
    ensure_env();
    return (*g_zomdroid_jni_env)->IsAssignableFrom(g_zomdroid_jni_env, sub, sup);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_ToReflectedField(__attribute__((unused)) JNIEnv *env, jclass cls, jfieldID fieldID, jboolean isStatic) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ToReflectedField(g_zomdroid_jni_env, cls, fieldID, isStatic);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_Throw(__attribute__((unused)) JNIEnv *env, jthrowable obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->Throw(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_ThrowNew(__attribute__((unused)) JNIEnv *env, jclass clazz, const char *msg) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ThrowNew(g_zomdroid_jni_env, clazz, msg);
}

__attribute__((visibility("default"), used))
jthrowable zomdroid_jni_ExceptionOccurred(__attribute__((unused)) JNIEnv *env) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ExceptionOccurred(g_zomdroid_jni_env);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ExceptionDescribe(__attribute__((unused)) JNIEnv *env) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ExceptionDescribe(g_zomdroid_jni_env);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ExceptionClear(__attribute__((unused)) JNIEnv *env) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ExceptionClear(g_zomdroid_jni_env);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_FatalError(__attribute__((unused)) JNIEnv *env, const char *msg) {
    ensure_env();
    return (*g_zomdroid_jni_env)->FatalError(g_zomdroid_jni_env, msg);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_PushLocalFrame(__attribute__((unused)) JNIEnv *env, jint capacity) {
    ensure_env();
    return (*g_zomdroid_jni_env)->PushLocalFrame(g_zomdroid_jni_env, capacity);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_PopLocalFrame(__attribute__((unused)) JNIEnv *env, jobject result) {
    ensure_env();
    return (*g_zomdroid_jni_env)->PopLocalFrame(g_zomdroid_jni_env, result);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_NewGlobalRef(__attribute__((unused)) JNIEnv *env, jobject lobj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewGlobalRef(g_zomdroid_jni_env, lobj);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_DeleteGlobalRef(__attribute__((unused)) JNIEnv *env, jobject gref) {
    ensure_env();
    return (*g_zomdroid_jni_env)->DeleteGlobalRef(g_zomdroid_jni_env, gref);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_DeleteLocalRef(__attribute__((unused)) JNIEnv *env, jobject obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->DeleteLocalRef(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_IsSameObject(__attribute__((unused)) JNIEnv *env, jobject obj1, jobject obj2) {
    ensure_env();
    return (*g_zomdroid_jni_env)->IsSameObject(g_zomdroid_jni_env, obj1, obj2);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_NewLocalRef(__attribute__((unused)) JNIEnv *env, jobject ref) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewLocalRef(g_zomdroid_jni_env, ref);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_EnsureLocalCapacity(__attribute__((unused)) JNIEnv *env, jint capacity) {
    ensure_env();
    return (*g_zomdroid_jni_env)->EnsureLocalCapacity(g_zomdroid_jni_env, capacity);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_AllocObject(__attribute__((unused)) JNIEnv *env, jclass clazz) {
    ensure_env();
    return (*g_zomdroid_jni_env)->AllocObject(g_zomdroid_jni_env, clazz);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_NewObjectV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->NewObjectV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_NewObjectA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewObjectA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jclass zomdroid_jni_GetObjectClass(__attribute__((unused)) JNIEnv *env, jobject obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetObjectClass(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_IsInstanceOf(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz) {
    ensure_env();
    return (*g_zomdroid_jni_env)->IsInstanceOf(g_zomdroid_jni_env, obj, clazz);
}

__attribute__((visibility("default"), used))
jmethodID zomdroid_jni_GetMethodID(__attribute__((unused)) JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetMethodID(g_zomdroid_jni_env, clazz, name, sig);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_CallObjectMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallObjectMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_CallObjectMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallObjectMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_CallBooleanMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallBooleanMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_CallBooleanMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallBooleanMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_CallByteMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallByteMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_CallByteMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallByteMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_CallCharMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallCharMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_CallCharMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallCharMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_CallShortMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallShortMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_CallShortMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallShortMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_CallIntMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallIntMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_CallIntMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallIntMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_CallLongMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallLongMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_CallLongMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallLongMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_CallFloatMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallFloatMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_CallFloatMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallFloatMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_CallDoubleMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallDoubleMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_CallDoubleMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallDoubleMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_CallVoidMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallVoidMethodV(g_zomdroid_jni_env, obj, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_CallVoidMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallVoidMethodA(g_zomdroid_jni_env, obj, methodID, args);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_CallNonvirtualObjectMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualObjectMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_CallNonvirtualObjectMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualObjectMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_CallNonvirtualBooleanMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualBooleanMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_CallNonvirtualBooleanMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualBooleanMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_CallNonvirtualByteMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualByteMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_CallNonvirtualByteMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualByteMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_CallNonvirtualCharMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualCharMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_CallNonvirtualCharMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualCharMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_CallNonvirtualShortMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualShortMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_CallNonvirtualShortMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualShortMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_CallNonvirtualIntMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualIntMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_CallNonvirtualIntMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualIntMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_CallNonvirtualLongMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualLongMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_CallNonvirtualLongMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualLongMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_CallNonvirtualFloatMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualFloatMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_CallNonvirtualFloatMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualFloatMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_CallNonvirtualDoubleMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualDoubleMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_CallNonvirtualDoubleMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualDoubleMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_CallNonvirtualVoidMethodV(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallNonvirtualVoidMethodV(g_zomdroid_jni_env, obj, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_CallNonvirtualVoidMethodA(__attribute__((unused)) JNIEnv *env, jobject obj, jclass clazz, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallNonvirtualVoidMethodA(g_zomdroid_jni_env, obj, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jfieldID zomdroid_jni_GetFieldID(__attribute__((unused)) JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetFieldID(g_zomdroid_jni_env, clazz, name, sig);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_GetObjectField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetObjectField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_GetBooleanField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetBooleanField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_GetByteField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetByteField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_GetCharField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetCharField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_GetShortField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetShortField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_GetIntField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetIntField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_GetLongField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetLongField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_GetFloatField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetFloatField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_GetDoubleField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetDoubleField(g_zomdroid_jni_env, obj, fieldID);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetObjectField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jobject val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetObjectField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetBooleanField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jboolean val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetBooleanField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetByteField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jbyte val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetByteField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetCharField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jchar val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetCharField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetShortField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jshort val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetShortField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetIntField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jint val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetIntField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetLongField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jlong val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetLongField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetFloatField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jfloat val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetFloatField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetDoubleField(__attribute__((unused)) JNIEnv *env, jobject obj, jfieldID fieldID, jdouble val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetDoubleField(g_zomdroid_jni_env, obj, fieldID, val);
}

__attribute__((visibility("default"), used))
jmethodID zomdroid_jni_GetStaticMethodID(__attribute__((unused)) JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticMethodID(g_zomdroid_jni_env, clazz, name, sig);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_CallStaticObjectMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticObjectMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_CallStaticObjectMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticObjectMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_CallStaticBooleanMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticBooleanMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_CallStaticBooleanMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticBooleanMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_CallStaticByteMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticByteMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_CallStaticByteMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticByteMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_CallStaticCharMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticCharMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_CallStaticCharMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticCharMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_CallStaticShortMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticShortMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_CallStaticShortMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticShortMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_CallStaticIntMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticIntMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_CallStaticIntMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticIntMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_CallStaticLongMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticLongMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_CallStaticLongMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticLongMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_CallStaticFloatMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticFloatMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_CallStaticFloatMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticFloatMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_CallStaticDoubleMethodV(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticDoubleMethodV(g_zomdroid_jni_env, clazz, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_CallStaticDoubleMethodA(__attribute__((unused)) JNIEnv *env, jclass clazz, jmethodID methodID, const jvalue *args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticDoubleMethodA(g_zomdroid_jni_env, clazz, methodID, args);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_CallStaticVoidMethodV(__attribute__((unused)) JNIEnv *env, jclass cls, jmethodID methodID, x64_va_list_t args) {
    ensure_env();
    CONVERT_VALIST(args)
    return (*g_zomdroid_jni_env)->CallStaticVoidMethodV(g_zomdroid_jni_env, cls, methodID, VARARGS);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_CallStaticVoidMethodA(__attribute__((unused)) JNIEnv *env, jclass cls, jmethodID methodID, const jvalue * args) {
    ensure_env();
    return (*g_zomdroid_jni_env)->CallStaticVoidMethodA(g_zomdroid_jni_env, cls, methodID, args);
}

__attribute__((visibility("default"), used))
jfieldID zomdroid_jni_GetStaticFieldID(__attribute__((unused)) JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticFieldID(g_zomdroid_jni_env, clazz, name, sig);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_GetStaticObjectField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticObjectField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_GetStaticBooleanField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticBooleanField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jbyte zomdroid_jni_GetStaticByteField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticByteField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jchar zomdroid_jni_GetStaticCharField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticCharField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jshort zomdroid_jni_GetStaticShortField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticShortField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_GetStaticIntField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticIntField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_GetStaticLongField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticLongField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jfloat zomdroid_jni_GetStaticFloatField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticFloatField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
jdouble zomdroid_jni_GetStaticDoubleField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStaticDoubleField(g_zomdroid_jni_env, clazz, fieldID);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticObjectField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jobject value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticObjectField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticBooleanField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jboolean value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticBooleanField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticByteField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jbyte value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticByteField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticCharField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jchar value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticCharField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticShortField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jshort value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticShortField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticIntField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jint value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticIntField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticLongField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jlong value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticLongField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticFloatField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jfloat value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticFloatField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetStaticDoubleField(__attribute__((unused)) JNIEnv *env, jclass clazz, jfieldID fieldID, jdouble value) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetStaticDoubleField(g_zomdroid_jni_env, clazz, fieldID, value);
}

__attribute__((visibility("default"), used))
jstring zomdroid_jni_NewString(__attribute__((unused)) JNIEnv *env, const jchar *unicode, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewString(g_zomdroid_jni_env, unicode, len);
}

__attribute__((visibility("default"), used))
jsize zomdroid_jni_GetStringLength(__attribute__((unused)) JNIEnv *env, jstring str) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringLength(g_zomdroid_jni_env, str);
}

__attribute__((visibility("default"), used))
const jchar * zomdroid_jni_GetStringChars(__attribute__((unused)) JNIEnv *env, jstring str, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringChars(g_zomdroid_jni_env, str, isCopy);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseStringChars(__attribute__((unused)) JNIEnv *env, jstring str, const jchar *chars) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseStringChars(g_zomdroid_jni_env, str, chars);
}

__attribute__((visibility("default"), used))
jstring zomdroid_jni_NewStringUTF(__attribute__((unused)) JNIEnv *env, const char *utf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewStringUTF(g_zomdroid_jni_env, utf);
}

__attribute__((visibility("default"), used))
jsize zomdroid_jni_GetStringUTFLength(__attribute__((unused)) JNIEnv *env, jstring str) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringUTFLength(g_zomdroid_jni_env, str);
}

__attribute__((visibility("default"), used))
const char* zomdroid_jni_GetStringUTFChars(__attribute__((unused)) JNIEnv *env, jstring str, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringUTFChars(g_zomdroid_jni_env, str, isCopy);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseStringUTFChars(__attribute__((unused)) JNIEnv *env, jstring str, const char* chars) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseStringUTFChars(g_zomdroid_jni_env, str, chars);
}

__attribute__((visibility("default"), used))
jsize zomdroid_jni_GetArrayLength(__attribute__((unused)) JNIEnv *env, jarray array) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetArrayLength(g_zomdroid_jni_env, array);
}

__attribute__((visibility("default"), used))
jobjectArray zomdroid_jni_NewObjectArray(__attribute__((unused)) JNIEnv *env, jsize len, jclass clazz, jobject init) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewObjectArray(g_zomdroid_jni_env, len, clazz, init);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_GetObjectArrayElement(__attribute__((unused)) JNIEnv *env, jobjectArray array, jsize index) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetObjectArrayElement(g_zomdroid_jni_env, array, index);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetObjectArrayElement(__attribute__((unused)) JNIEnv *env, jobjectArray array, jsize index, jobject val) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetObjectArrayElement(g_zomdroid_jni_env, array, index, val);
}

__attribute__((visibility("default"), used))
jbooleanArray zomdroid_jni_NewBooleanArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewBooleanArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jbyteArray zomdroid_jni_NewByteArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewByteArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jcharArray zomdroid_jni_NewCharArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewCharArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jshortArray zomdroid_jni_NewShortArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewShortArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jintArray zomdroid_jni_NewIntArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewIntArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jlongArray zomdroid_jni_NewLongArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewLongArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jfloatArray zomdroid_jni_NewFloatArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewFloatArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jdoubleArray zomdroid_jni_NewDoubleArray(__attribute__((unused)) JNIEnv *env, jsize len) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewDoubleArray(g_zomdroid_jni_env, len);
}

__attribute__((visibility("default"), used))
jboolean * zomdroid_jni_GetBooleanArrayElements(__attribute__((unused)) JNIEnv *env, jbooleanArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetBooleanArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jbyte * zomdroid_jni_GetByteArrayElements(__attribute__((unused)) JNIEnv *env, jbyteArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetByteArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jchar * zomdroid_jni_GetCharArrayElements(__attribute__((unused)) JNIEnv *env, jcharArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetCharArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jshort * zomdroid_jni_GetShortArrayElements(__attribute__((unused)) JNIEnv *env, jshortArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetShortArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jint * zomdroid_jni_GetIntArrayElements(__attribute__((unused)) JNIEnv *env, jintArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetIntArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jlong * zomdroid_jni_GetLongArrayElements(__attribute__((unused)) JNIEnv *env, jlongArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetLongArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jfloat * zomdroid_jni_GetFloatArrayElements(__attribute__((unused)) JNIEnv *env, jfloatArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetFloatArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
jdouble * zomdroid_jni_GetDoubleArrayElements(__attribute__((unused)) JNIEnv *env, jdoubleArray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetDoubleArrayElements(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseBooleanArrayElements(__attribute__((unused)) JNIEnv *env, jbooleanArray array, jboolean *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseBooleanArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseByteArrayElements(__attribute__((unused)) JNIEnv *env, jbyteArray array, jbyte *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseByteArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseCharArrayElements(__attribute__((unused)) JNIEnv *env, jcharArray array, jchar *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseCharArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseShortArrayElements(__attribute__((unused)) JNIEnv *env, jshortArray array, jshort *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseShortArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseIntArrayElements(__attribute__((unused)) JNIEnv *env, jintArray array, jint *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseIntArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseLongArrayElements(__attribute__((unused)) JNIEnv *env, jlongArray array, jlong *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseLongArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseFloatArrayElements(__attribute__((unused)) JNIEnv *env, jfloatArray array, jfloat *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseFloatArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseDoubleArrayElements(__attribute__((unused)) JNIEnv *env, jdoubleArray array, jdouble *elems, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseDoubleArrayElements(g_zomdroid_jni_env, array, elems, mode);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetBooleanArrayRegion(__attribute__((unused)) JNIEnv *env, jbooleanArray array, jsize start, jsize l, jboolean *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetBooleanArrayRegion(g_zomdroid_jni_env, array, start, l, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetByteArrayRegion(__attribute__((unused)) JNIEnv *env, jbyteArray array, jsize start, jsize len, jbyte *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetByteArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetCharArrayRegion(__attribute__((unused)) JNIEnv *env, jcharArray array, jsize start, jsize len, jchar *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetCharArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetShortArrayRegion(__attribute__((unused)) JNIEnv *env, jshortArray array, jsize start, jsize len, jshort *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetShortArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetIntArrayRegion(__attribute__((unused)) JNIEnv *env, jintArray array, jsize start, jsize len, jint *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetIntArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetLongArrayRegion(__attribute__((unused)) JNIEnv *env, jlongArray array, jsize start, jsize len, jlong *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetLongArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetFloatArrayRegion(__attribute__((unused)) JNIEnv *env, jfloatArray array, jsize start, jsize len, jfloat *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetFloatArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetDoubleArrayRegion(__attribute__((unused)) JNIEnv *env, jdoubleArray array, jsize start, jsize len, jdouble *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetDoubleArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetBooleanArrayRegion(__attribute__((unused)) JNIEnv *env, jbooleanArray array, jsize start, jsize l, const jboolean *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetBooleanArrayRegion(g_zomdroid_jni_env, array, start, l, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetByteArrayRegion(__attribute__((unused)) JNIEnv *env, jbyteArray array, jsize start, jsize len, const jbyte *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetByteArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetCharArrayRegion(__attribute__((unused)) JNIEnv *env, jcharArray array, jsize start, jsize len, const jchar *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetCharArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetShortArrayRegion(__attribute__((unused)) JNIEnv *env, jshortArray array, jsize start, jsize len, const jshort *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetShortArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetIntArrayRegion(__attribute__((unused)) JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetIntArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetLongArrayRegion(__attribute__((unused)) JNIEnv *env, jlongArray array, jsize start, jsize len, const jlong *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetLongArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetFloatArrayRegion(__attribute__((unused)) JNIEnv *env, jfloatArray array, jsize start, jsize len, const jfloat *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetFloatArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_SetDoubleArrayRegion(__attribute__((unused)) JNIEnv *env, jdoubleArray array, jsize start, jsize len, const jdouble *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->SetDoubleArrayRegion(g_zomdroid_jni_env, array, start, len, buf);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_RegisterNatives(__attribute__((unused)) JNIEnv *env, jclass clazz, const JNINativeMethod *methods, jint nMethods) {
    ensure_env();
    return (*g_zomdroid_jni_env)->RegisterNatives(g_zomdroid_jni_env, clazz, methods, nMethods);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_UnregisterNatives(__attribute__((unused)) JNIEnv *env, jclass clazz) {
    ensure_env();
    return (*g_zomdroid_jni_env)->UnregisterNatives(g_zomdroid_jni_env, clazz);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_MonitorEnter(__attribute__((unused)) JNIEnv *env, jobject obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->MonitorEnter(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_MonitorExit(__attribute__((unused)) JNIEnv *env, jobject obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->MonitorExit(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_GetJavaVM(__attribute__((unused)) JNIEnv* env, JavaVM** vm) {
    *vm = (void*) g_wrapped_jvm;
    return JNI_OK;
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetStringRegion(__attribute__((unused)) JNIEnv *env, jstring str, jsize start, jsize len, jchar *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringRegion(g_zomdroid_jni_env, str, start, len, buf);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_GetStringUTFRegion(__attribute__((unused)) JNIEnv *env, jstring str, jsize start, jsize len, char *buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringUTFRegion(g_zomdroid_jni_env, str, start, len, buf);
}

__attribute__((visibility("default"), used))
void * zomdroid_jni_GetPrimitiveArrayCritical(__attribute__((unused)) JNIEnv *env, jarray array, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetPrimitiveArrayCritical(g_zomdroid_jni_env, array, isCopy);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleasePrimitiveArrayCritical(__attribute__((unused)) JNIEnv *env, jarray array, void *carray, jint mode) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleasePrimitiveArrayCritical(g_zomdroid_jni_env, array, carray, mode);
}

__attribute__((visibility("default"), used))
const jchar * zomdroid_jni_GetStringCritical(__attribute__((unused)) JNIEnv *env, jstring string, jboolean *isCopy) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetStringCritical(g_zomdroid_jni_env, string, isCopy);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_ReleaseStringCritical(__attribute__((unused)) JNIEnv *env, jstring string, const jchar *cstring) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ReleaseStringCritical(g_zomdroid_jni_env, string, cstring);
}

__attribute__((visibility("default"), used))
jweak zomdroid_jni_NewWeakGlobalRef(__attribute__((unused)) JNIEnv *env, jobject obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewWeakGlobalRef(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
void zomdroid_jni_DeleteWeakGlobalRef(__attribute__((unused)) JNIEnv *env, jweak ref) {
    ensure_env();
    return (*g_zomdroid_jni_env)->DeleteWeakGlobalRef(g_zomdroid_jni_env, ref);
}

__attribute__((visibility("default"), used))
jboolean zomdroid_jni_ExceptionCheck(__attribute__((unused)) JNIEnv *env) {
    ensure_env();
    return (*g_zomdroid_jni_env)->ExceptionCheck(g_zomdroid_jni_env);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_NewDirectByteBuffer(__attribute__((unused)) JNIEnv* env, void* address, jlong capacity) {
    ensure_env();
    return (*g_zomdroid_jni_env)->NewDirectByteBuffer(g_zomdroid_jni_env, address, capacity);
}

__attribute__((visibility("default"), used))
void* zomdroid_jni_GetDirectBufferAddress(__attribute__((unused)) JNIEnv* env, jobject buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetDirectBufferAddress(g_zomdroid_jni_env, buf);
}

__attribute__((visibility("default"), used))
jlong zomdroid_jni_GetDirectBufferCapacity(__attribute__((unused)) JNIEnv* env, jobject buf) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetDirectBufferCapacity(g_zomdroid_jni_env, buf);
}

__attribute__((visibility("default"), used))
jobjectRefType zomdroid_jni_GetObjectRefType(__attribute__((unused)) JNIEnv* env, jobject obj) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetObjectRefType(g_zomdroid_jni_env, obj);
}

__attribute__((visibility("default"), used))
jobject zomdroid_jni_GetModule(__attribute__((unused)) JNIEnv* env, jclass clazz) {
    ensure_env();
    return (*g_zomdroid_jni_env)->GetModule(g_zomdroid_jni_env, clazz);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_DestroyJavaVM(__attribute__((unused)) JavaVM *vm) {
    return (*g_zomdroid_jvm)->DestroyJavaVM(g_zomdroid_jvm);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_AttachCurrentThread(__attribute__((unused)) JavaVM* vm, JNIEnv** env, void* args) {
    *env = (void*) g_wrapped_jni_env;
    return (*g_zomdroid_jvm)->AttachCurrentThread(g_zomdroid_jvm, (void**) &g_zomdroid_jni_env, args);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_DetachCurrentThread(__attribute__((unused)) JavaVM *vm) {
    return (*g_zomdroid_jvm)->DetachCurrentThread(g_zomdroid_jvm);
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_GetEnv(__attribute__((unused)) JavaVM* vm, void** env, jint ver) {
    int res = (*g_zomdroid_jvm)->GetEnv(g_zomdroid_jvm, (void **)&g_zomdroid_jni_env, ver);
    if (res != JNI_OK) {
        *env = NULL;
    } else {
        (*env) = (void*) g_wrapped_jni_env;
    }
    return res;
}

__attribute__((visibility("default"), used))
jint zomdroid_jni_AttachCurrentThreadAsDaemon(__attribute__((unused)) JavaVM* vm, JNIEnv** env, void* args) {
    *env = (void*) g_wrapped_jni_env;
    return (*g_zomdroid_jvm)->AttachCurrentThreadAsDaemon(g_zomdroid_jvm, (void**) &g_zomdroid_jni_env, args);
}
                                