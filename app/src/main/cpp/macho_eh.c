/*
 * macho_eh.c - the C++ exception runtime the macOS dylibs throw and catch through.
 *
 * Built as its own shared library (libzomdroid_macho_eh.so) with the NDK's libc++abi.a and
 * libunwind.a linked in whole. Why a second copy of what libc++_shared.so already has: the
 * unwinder inside libc++_shared is private to it. Its dynamic-FDE registration entry points are
 * hidden symbols, so there is no way to tell it about code it did not load itself, and a throw
 * from a dylib frame ends in std::terminate the moment it reaches that frame. This copy has the
 * same libunwind with its registration reachable, so the loader can hand it the DWARF tables it
 * synthesises from the dylib's compact unwind info (macho_unwind.c), and the dylib's __cxa_* and
 * _Unwind_* imports are bound here instead of to libc++_shared.
 *
 * What stays with libc++_shared: everything that describes types. The typeinfo objects and the
 * __cxxabiv1 vtables behind them must all come from one place, or the personality's catch
 * matching compares objects from two worlds; this file deliberately exports none of them.
 *
 * Known limit: an exception raised by Android code (libc++_shared, bionic, our own shims) that
 * would have to unwind THROUGH a dylib frame still terminates, because that raise goes through
 * libc++_shared's unwinder. Exceptions raised in a dylib and caught in a dylib - the case the
 * game's libraries actually rely on - work.
 */
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* Everything below is provided by the archives linked into this library. The declarations are
 * deliberately loose (no argument lists): only the addresses are needed. */
extern void __cxa_allocate_exception(void);
extern void __cxa_free_exception(void);
extern void __cxa_throw(void);
extern void __cxa_begin_catch(void);
extern void __cxa_end_catch(void);
extern void __cxa_rethrow(void);
extern void __cxa_get_exception_ptr(void);
extern void __cxa_call_unexpected(void);
extern void __cxa_current_exception_type(void);
extern void __cxa_guard_acquire(void);
extern void __cxa_guard_release(void);
extern void __cxa_guard_abort(void);
extern void __cxa_pure_virtual(void);
extern void __cxa_deleted_virtual(void);
extern void __gxx_personality_v0(void);
extern void _Unwind_Resume(void);
extern void _Unwind_RaiseException(void);
extern void _Unwind_DeleteException(void);
extern void _Unwind_GetLanguageSpecificData(void);
extern void _Unwind_GetRegionStart(void);
extern void _Unwind_GetIP(void);
extern void _Unwind_SetGR(void);
extern void _Unwind_SetIP(void);
extern void _ZSt9terminatev(void);
extern void _ZSt10unexpectedv(void);
extern void __unw_add_dynamic_fde(uintptr_t fde);
extern void __unw_remove_dynamic_fde(uintptr_t fde);

typedef struct { const char* name; void* addr; } macho_eh_entry_t;

static const macho_eh_entry_t macho_eh_table[] = {
    { "__cxa_allocate_exception",        (void*)__cxa_allocate_exception },
    { "__cxa_free_exception",            (void*)__cxa_free_exception },
    { "__cxa_throw",                     (void*)__cxa_throw },
    { "__cxa_begin_catch",               (void*)__cxa_begin_catch },
    { "__cxa_end_catch",                 (void*)__cxa_end_catch },
    { "__cxa_rethrow",                   (void*)__cxa_rethrow },
    { "__cxa_get_exception_ptr",         (void*)__cxa_get_exception_ptr },
    { "__cxa_call_unexpected",           (void*)__cxa_call_unexpected },
    { "__cxa_current_exception_type",    (void*)__cxa_current_exception_type },
    { "__cxa_guard_acquire",             (void*)__cxa_guard_acquire },
    { "__cxa_guard_release",             (void*)__cxa_guard_release },
    { "__cxa_guard_abort",               (void*)__cxa_guard_abort },
    { "__cxa_pure_virtual",              (void*)__cxa_pure_virtual },
    { "__cxa_deleted_virtual",           (void*)__cxa_deleted_virtual },
    { "__gxx_personality_v0",            (void*)__gxx_personality_v0 },
    { "_Unwind_Resume",                  (void*)_Unwind_Resume },
    { "_Unwind_RaiseException",          (void*)_Unwind_RaiseException },
    { "_Unwind_DeleteException",         (void*)_Unwind_DeleteException },
    { "_Unwind_GetLanguageSpecificData", (void*)_Unwind_GetLanguageSpecificData },
    { "_Unwind_GetRegionStart",          (void*)_Unwind_GetRegionStart },
    { "_Unwind_GetIP",                   (void*)_Unwind_GetIP },
    { "_Unwind_SetGR",                   (void*)_Unwind_SetGR },
    { "_Unwind_SetIP",                   (void*)_Unwind_SetIP },
    { "_ZSt9terminatev",                 (void*)_ZSt9terminatev },
    { "_ZSt10unexpectedv",               (void*)_ZSt10unexpectedv },
};

/* The loader asks for these by their C name (Darwin's leading underscore already stripped). */
__attribute__((visibility("default")))
void* zomdroid_macho_eh_symbol(const char* name) {
    if (!name) return NULL;
    for (size_t i = 0; i < sizeof(macho_eh_table) / sizeof(macho_eh_table[0]); i++)
        if (strcmp(name, macho_eh_table[i].name) == 0) return macho_eh_table[i].addr;
    return NULL;
}

/*
 * Registers every FDE of a complete .eh_frame section (CIEs, FDEs, zero terminator) with this
 * unwinder, one by one. The memory must stay valid and unchanged for the life of the process.
 *
 * Deliberately not __unw_add_dynamic_eh_frame_section: its walk decodes each FDE against the
 * last CIE it parsed rather than the one the FDE points at, and it does not stop at the zero
 * terminator (the CIE parser accepts a zero length as success without advancing), so it runs
 * off the end of the buffer. Both seen on the phone 2026-09-12. __unw_add_dynamic_fde decodes
 * one FDE together with its own CIE, and the walk below knows exactly where the section ends.
 */
static int macho_eh_walk(const void* eh_frame, void (*fn)(uintptr_t)) {
    const uint8_t* p = eh_frame;
    int n = 0;
    for (;;) {
        uint32_t length, id;
        memcpy(&length, p, 4);
        if (length == 0 || length == 0xffffffffu) break;   /* terminator (64-bit form: never ours) */
        memcpy(&id, p + 4, 4);
        if (id != 0) { fn((uintptr_t)p); n++; }             /* id 0 = CIE, anything else = FDE */
        p += 4 + (size_t)length;
    }
    return n;
}

__attribute__((visibility("default")))
int zomdroid_macho_eh_register(const void* eh_frame) {
    return macho_eh_walk(eh_frame, __unw_add_dynamic_fde);
}

__attribute__((visibility("default")))
int zomdroid_macho_eh_unregister(const void* eh_frame) {
    return macho_eh_walk(eh_frame, __unw_remove_dynamic_fde);
}

/* A version stamp for the log, so a mismatched pair of libraries is visible. */
__attribute__((visibility("default")))
int zomdroid_macho_eh_version(void) {
    return 1;
}
