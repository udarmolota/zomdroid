/*
 * macho_loader.c - maps a macOS arm64 dylib into this process. See macho_loader.h for why.
 *
 * The whole job, in order: read the file, pick the arm64 slice of the fat binary, walk the load
 * commands and refuse anything we do not handle, reserve one anonymous mapping for the image,
 * copy the segments in, apply the rebase opcodes (slide), apply the bind / weak-bind / lazy-bind
 * opcodes eagerly against bionic and libc++_shared, set the segment protections, read the
 * export trie, run the static constructors. Every refusal is one "[macho] <file> rejected:"
 * line and a NULL return; the linker then falls back to the next option for that library.
 */
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include "logger.h"
#include "macho_loader.h"
#include "macho_jnienv.h"
#include "macho_string.h"
#include "macho_unwind.h"

#define LOG_TAG "zomdroid-macho"

/* Same shape as linker.c: logcat for the moment, stdout for native.log and the bug report. */
#define LOG_REPORTED(fmt, ...) do {                  \
        LOGI(fmt __VA_OPT__(,) __VA_ARGS__);         \
        printf(fmt "\n" __VA_OPT__(,) __VA_ARGS__);  \
    } while (0)

int macho_verbose(void) {
    static int verbose = -1; /* read once; a racing first call only reads the same value twice */
    if (verbose < 0) {
        const char* env = getenv("ZOMDROID_NATIVE_VERBOSE");
        verbose = env != NULL && strcmp(env, "1") == 0;
    }
    return verbose;
}

/* ------------------------------------------------------------------------------------------ */
/* Mach-O on-disk structures - only the subset this loader reads. Layouts per Apple's mach-o headers. */

#define FAT_MAGIC_BE        0xCAFEBABEu   /* big-endian on disk */
#define MH_MAGIC_64         0xFEEDFACFu
#define MH_DYLIB            6u
#define CPU_TYPE_ARM64      0x0100000Cu
#define CPU_SUBTYPE_MASK    0x00FFFFFFu
#define CPU_SUBTYPE_ARM64E  2u

#define LC_REQ_DYLD             0x80000000u
#define LC_SEGMENT_64           0x19u
#define LC_LOAD_DYLIB           0x0Cu
#define LC_LOAD_WEAK_DYLIB      (0x18u | LC_REQ_DYLD)
#define LC_REEXPORT_DYLIB       (0x1Fu | LC_REQ_DYLD)
#define LC_LOAD_UPWARD_DYLIB    (0x23u | LC_REQ_DYLD)
#define LC_DYLD_INFO            0x22u
#define LC_DYLD_INFO_ONLY       (0x22u | LC_REQ_DYLD)
#define LC_DYLD_EXPORTS_TRIE    (0x33u | LC_REQ_DYLD)
#define LC_DYLD_CHAINED_FIXUPS  (0x34u | LC_REQ_DYLD)

#define SECTION_TYPE                          0xFFu
#define S_ZEROFILL                            0x01u
#define S_MOD_INIT_FUNC_POINTERS              0x09u
#define S_THREAD_LOCAL_REGULAR                0x11u
#define S_THREAD_LOCAL_INIT_FUNCTION_POINTERS 0x15u
#define S_INIT_FUNC_OFFSETS                   0x16u
#define S_ATTR_PURE_INSTRUCTIONS              0x80000000u
#define S_ATTR_SOME_INSTRUCTIONS              0x00000400u

#define LC_DATA_IN_CODE         0x29u

#ifndef HWCAP_LRCPC
#define HWCAP_LRCPC             (1UL << 15)
#endif

#define VM_PROT_READ    1
#define VM_PROT_WRITE   2
#define VM_PROT_EXECUTE 4

typedef struct { uint32_t magic, nfat_arch; } fat_header_t;                       /* big-endian */
typedef struct { uint32_t cputype, cpusubtype, offset, size, align; } fat_arch_t; /* big-endian */

typedef struct {
    uint32_t magic;
    uint32_t cputype, cpusubtype;
    uint32_t filetype, ncmds, sizeofcmds, flags, reserved;
} mach_header_64_t;

typedef struct { uint32_t cmd, cmdsize; } load_command_t;

typedef struct {
    uint32_t cmd, cmdsize;
    char segname[16];
    uint64_t vmaddr, vmsize, fileoff, filesize;
    int32_t maxprot, initprot;
    uint32_t nsects, flags;
} segment_command_64_t;

typedef struct {
    char sectname[16], segname[16];
    uint64_t addr, size;
    uint32_t offset, align, reloff, nreloc, flags, reserved1, reserved2, reserved3;
} section_64_t;

typedef struct {
    uint32_t cmd, cmdsize;
    uint32_t rebase_off, rebase_size;
    uint32_t bind_off, bind_size;
    uint32_t weak_bind_off, weak_bind_size;
    uint32_t lazy_bind_off, lazy_bind_size;
    uint32_t export_off, export_size;
} dyld_info_command_t;

typedef struct {
    uint32_t cmd, cmdsize;
    uint32_t name_off, timestamp, current_version, compat_version;
} dylib_command_t;

/* Rebase opcode stream (dyld). */
#define REBASE_OPCODE_MASK                               0xF0u
#define REBASE_IMMEDIATE_MASK                            0x0Fu
#define REBASE_TYPE_POINTER                              1u
#define REBASE_OPCODE_DONE                               0x00u
#define REBASE_OPCODE_SET_TYPE_IMM                       0x10u
#define REBASE_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB        0x20u
#define REBASE_OPCODE_ADD_ADDR_ULEB                      0x30u
#define REBASE_OPCODE_ADD_ADDR_IMM_SCALED                0x40u
#define REBASE_OPCODE_DO_REBASE_IMM_TIMES                0x50u
#define REBASE_OPCODE_DO_REBASE_ULEB_TIMES               0x60u
#define REBASE_OPCODE_DO_REBASE_ADD_ADDR_ULEB            0x70u
#define REBASE_OPCODE_DO_REBASE_ULEB_TIMES_SKIPPING_ULEB 0x80u

/* Bind opcode stream (dyld), shared by the bind, weak-bind and lazy-bind tables. */
#define BIND_OPCODE_MASK                                 0xF0u
#define BIND_IMMEDIATE_MASK                              0x0Fu
#define BIND_TYPE_POINTER                                1u
#define BIND_OPCODE_DONE                                 0x00u
#define BIND_OPCODE_SET_DYLIB_ORDINAL_IMM                0x10u
#define BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB               0x20u
#define BIND_OPCODE_SET_DYLIB_SPECIAL_IMM                0x30u
#define BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM        0x40u
#define BIND_OPCODE_SET_TYPE_IMM                         0x50u
#define BIND_OPCODE_SET_ADDEND_SLEB                      0x60u
#define BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB          0x70u
#define BIND_OPCODE_ADD_ADDR_ULEB                        0x80u
#define BIND_OPCODE_DO_BIND                              0x90u
#define BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB                0xA0u
#define BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED          0xB0u
#define BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB     0xC0u
#define BIND_OPCODE_THREADED                             0xD0u

/* Export trie terminal flags. */
#define EXPORT_SYMBOL_FLAGS_KIND_MASK          0x03u
#define EXPORT_SYMBOL_FLAGS_KIND_ABSOLUTE      0x02u
#define EXPORT_SYMBOL_FLAGS_REEXPORT           0x08u
#define EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER  0x10u

/* ------------------------------------------------------------------------------------------ */

#define MACHO_MAX_SEGMENTS   16
#define MACHO_MAX_INIT_SECTS 8
#define MACHO_MAX_CODE_SECTS 8
#define MACHO_MAX_FILE       (64u * 1024u * 1024u)
#define MACHO_MAX_UNRESOLVED 24
#define MACHO_NAME_MAX       1024

typedef struct {
    char name[17];
    uint64_t vmaddr, vmsize, fileoff, filesize;
    int initprot;
} macho_segment_t;

typedef struct {
    const char* name;   /* without the leading underscore, malloc'ed */
    void* addr;
} macho_export_t;

struct macho_lib {
    char name[64];              /* basename, for the log */
    uint8_t* base;              /* start of the mapping = address of the mach header */
    size_t map_size;
    macho_segment_t seg[MACHO_MAX_SEGMENTS];
    int nseg;
    macho_export_t* exports;
    int nexports, export_cap;
    int nbinds;
    int wrap_env;               /* exports the JNIEnv_ C++ wrappers -> needs the wrapper env */
    int throws;                 /* imports __cxa_throw */
    uint64_t unwind_addr, unwind_size;   /* __unwind_info section, image offsets */
    const void* eh_frame;       /* the DWARF tables registered with the EH runtime, or NULL */
};

typedef struct {
    uint32_t type;      /* S_MOD_INIT_FUNC_POINTERS or S_INIT_FUNC_OFFSETS */
    uint64_t addr, size;
} macho_init_sect_t;

/* Everything the load needs while it is in flight; freed or handed to the lib at the end. */
typedef struct {
    macho_lib_t* lib;
    const uint8_t* slice;       /* the arm64 slice inside the file buffer */
    size_t slice_size;
    const dyld_info_command_t* dyld;
    macho_init_sect_t init[MACHO_MAX_INIT_SECTS];
    int ninit;
    char* unresolved[MACHO_MAX_UNRESOLVED];
    int nunresolved;            /* total, may exceed the names kept */
    char* layout_conflict[MACHO_MAX_UNRESOLVED];
    int nlayout_conflict;       /* libc++ classes whose object layout differs from Android's */
    struct { uint64_t addr, size; } code[MACHO_MAX_CODE_SECTS];   /* sections holding instructions */
    int ncode;
    uint32_t dic_off, dic_size; /* LC_DATA_IN_CODE table (slice offsets): data inside __text */
} macho_load_ctx_t;

static const char* base_name(const char* path) {
    const char* s = strrchr(path, '/');
    return s ? s + 1 : path;
}

static uint32_t be32(uint32_t v) { return __builtin_bswap32(v); }

/* ------------------------------------------------------------------------------------------ */
/* Import resolution: a Mach-O import name -> an address in this process.                     */

/* dyld would bind lazy pointers on first call through this. We bind everything eagerly and
 * reject on any miss, so reaching it means a pointer was left unbound - say so, then die. */
static void macho_stub_binder_trap(void) {
    LOG_REPORTED("[macho] dyld_stub_binder reached: a lazy pointer was left unbound");
    abort();
}

/* bionic has bzero only as an inline in its headers; give the dylib a real one. */
static void macho_bzero(void* p, size_t n) { memset(p, 0, n); }

/* Apple's stack probe, called from prologues with large frames before anything is saved, so it
 * must not touch a register. Linux stacks need no probing (the main thread's grows on demand,
 * pthread stacks are mapped in full): do nothing. */
__attribute__((naked)) static void macho_chkstk_noop(void) {
    __asm__ volatile("ret");
}

/* Apple's combined sin/cos, returning both in d0/d1 (s0/s1 for the float form). A two-member
 * homogeneous struct returns exactly that way under AAPCS64 as well. */
typedef struct { double s, c; } macho_sincos_t;
typedef struct { float s, c; } macho_sincosf_t;
static macho_sincos_t macho_sincos_stret(double x) { macho_sincos_t r = { sin(x), cos(x) }; return r; }
static macho_sincosf_t macho_sincosf_stret(float x) { macho_sincosf_t r = { sinf(x), cosf(x) }; return r; }

/* Darwin libc extension: fill a buffer with a repeating 4/8/16-byte pattern. */
static void macho_memset_pattern(void* b, const void* pat, size_t len, size_t plen) {
    uint8_t* d = b;
    while (len >= plen) { memcpy(d, pat, plen); d += plen; len -= plen; }
    if (len) memcpy(d, pat, len);
}
static void macho_memset_pattern4(void* b, const void* p, size_t n)  { macho_memset_pattern(b, p, n, 4); }
static void macho_memset_pattern8(void* b, const void* p, size_t n)  { macho_memset_pattern(b, p, n, 8); }
static void macho_memset_pattern16(void* b, const void* p, size_t n) { macho_memset_pattern(b, p, n, 16); }

typedef struct { const char* name; void* addr; } macho_fixed_import_t;
static const macho_fixed_import_t macho_fixed_imports[] = {
    { "dyld_stub_binder", (void*)macho_stub_binder_trap },
    { "bzero",            (void*)macho_bzero },
    { "__chkstk_darwin",  (void*)macho_chkstk_noop },
    { "__sincos_stret",   (void*)macho_sincos_stret },
    { "__sincosf_stret",  (void*)macho_sincosf_stret },
    { "memset_pattern4",  (void*)macho_memset_pattern4 },
    { "memset_pattern8",  (void*)macho_memset_pattern8 },
    { "memset_pattern16", (void*)macho_memset_pattern16 },
};

/* Darwin name -> bionic name, when the function is the same and only the name differs. */
typedef struct { const char* darwin; const char* bionic; } macho_alias_t;
static const macho_alias_t macho_aliases[] = {
    { "malloc_size", "malloc_usable_size" },
};

/* The libraries an import may come from, in lookup order. libc++_shared first: it is where the
 * C++ runtime and the exception machinery live; bionic's libc exports the unwinder entry points
 * (_Unwind_*) and everything POSIX. RTLD_DEFAULT last, for anything already in the process. */
static void* macho_rt_handles[4];
static const char* const macho_rt_names[4] = { "libc++_shared.so", "libc.so", "libdl.so", "libm.so" };
static int macho_rt_opened = 0;

static void macho_open_runtime(void) {
    if (macho_rt_opened) return;
    macho_rt_opened = 1;
    for (int i = 0; i < 4; i++) {
        macho_rt_handles[i] = dlopen(macho_rt_names[i], RTLD_NOW);
        if (!macho_rt_handles[i]) {
            const char* e = dlerror();
            LOG_REPORTED("[macho] runtime library %s unavailable (%s)", macho_rt_names[i],
                         e ? e : "no error reported");
        }
    }
}

/* The C++ exception runtime the dylibs are bound to: libzomdroid_macho_eh.so, our own copy of
 * libc++abi + libunwind with FDE registration reachable (see macho_eh.c). Opened once; when it
 * is missing the dylib's EH imports fall back to libc++_shared and a throw inside it aborts. */
static void* (*macho_eh_symbol)(const char*) = NULL;
static int (*macho_eh_register)(const void*) = NULL;
static int macho_eh_opened = 0;

static void macho_open_eh_runtime(void) {
    if (macho_eh_opened) return;
    macho_eh_opened = 1;
    void* h = dlopen("libzomdroid_macho_eh.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char* e = dlerror();
        LOG_REPORTED("[macho] EH runtime libzomdroid_macho_eh.so unavailable (%s): C++ exceptions "
                     "inside a dylib will abort", e ? e : "no error reported");
        return;
    }
    macho_eh_symbol = (void* (*)(const char*))dlsym(h, "zomdroid_macho_eh_symbol");
    macho_eh_register = (int (*)(const void*))dlsym(h, "zomdroid_macho_eh_register");
    int (*version)(void) = (int (*)(void))dlsym(h, "zomdroid_macho_eh_version");
    if (!macho_eh_symbol || !macho_eh_register) {
        LOG_REPORTED("[macho] EH runtime is incomplete: C++ exceptions inside a dylib will abort");
        macho_eh_symbol = NULL;
        macho_eh_register = NULL;
        return;
    }
    LOGI("[macho] EH runtime ready (version %d)", version ? version() : 0);
}

/* Apple's libc++ lives in namespace std::__1, the NDK's in std::__ndk1. This is only a symbol
 * rewrite, NOT an ABI conversion. Layout-sensitive imports need explicit shims or rejection.
 * Rewrite every "St3__1" (the mangling of std::__1) into
 * "St6__ndk1". Darwin's mbstate_t is spelled __mbstate_t, which changes the mangling of the
 * codecvt facet id the stream code looks up. Returns 0 when the rewritten name does not fit. */
typedef struct { const char* from; const char* to; } macho_rewrite_t;
static const macho_rewrite_t macho_rewrites[] = {
    { "St3__1",        "St6__ndk1" },
    { "11__mbstate_t", "9mbstate_t" },
};

static int macho_rewrite_name(const char* in, char* out, size_t cap) {
    size_t o = 0;
    for (const char* p = in; *p; ) {
        int hit = 0;
        for (size_t r = 0; r < sizeof(macho_rewrites) / sizeof(macho_rewrites[0]); r++) {
            size_t flen = strlen(macho_rewrites[r].from);
            if (strncmp(p, macho_rewrites[r].from, flen) != 0) continue;
            size_t tlen = strlen(macho_rewrites[r].to);
            if (o + tlen >= cap) return 0;
            memcpy(out + o, macho_rewrites[r].to, tlen);
            o += tlen;
            p += flen;
            hit = 1;
            break;
        }
        if (!hit) {
            if (o + 1 >= cap) return 0;
            out[o++] = *p++;
        }
    }
    out[o] = '\0';
    return 1;
}

/*
 * Apple's libc++ on arm64 is built with the alternate std::string layout (data pointer first,
 * long/short flag in the last byte); the NDK's libc++ is not. A dylib whose inline code builds
 * such a string and hands it to a libc++_shared function gets garbage back - seen 2026-09-11 on
 * the phone: PopMan and PathFind construct a std::random_device from a std::string in a static
 * initializer and died in memmove before dlopen even returned. Every string function these
 * dylibs import is now served by macho_string.cpp, built for Apple's layout, so the guard only
 * fires for a string or random_device import no shim covers - a future TIS build using a method
 * we have not written yet. That library is refused before anything of it executes.
 *
 * The stream classes are not on this list on purpose: their objects are laid out the same on
 * both sides (basic_streambuf, ios_base, basic_ios, basic_istream/ostream carry no std::string
 * and no mbstate_t), and basic_filebuf, which does carry one, is compiled into the dylib itself
 * and only ever touched by its own code. Lighting imports none of these either way.
 */
static const char* const macho_layout_sensitive[] = {
    "12basic_string", "13random_device",
};

static int macho_import_is_layout_sensitive(const char* name) {
    if (!strstr(name, "St3__1")) return 0;
    for (size_t i = 0; i < sizeof(macho_layout_sensitive) / sizeof(macho_layout_sensitive[0]); i++)
        if (strstr(name, macho_layout_sensitive[i])) return 1;
    return 0;
}

static void* macho_resolve_import(macho_lib_t* lib, const char* macho_name) {
    /* One leading underscore is the C symbol convention on Darwin: "_memcpy", "__ZNSt...". */
    const char* name = (macho_name[0] == '_') ? macho_name + 1 : macho_name;
    const char* own_name = name;
    void* string_shim = macho_string_import(name);
    if (string_shim) return string_shim;

    for (size_t i = 0; i < sizeof(macho_fixed_imports) / sizeof(macho_fixed_imports[0]); i++)
        if (strcmp(name, macho_fixed_imports[i].name) == 0) return macho_fixed_imports[i].addr;

    /* __cxa_*, _Unwind_*, the personality: ours, so that what the dylib throws is raised by the
     * unwinder that knows its frames. Type descriptions stay with libc++_shared (below). */
    if (strcmp(name, "__cxa_throw") == 0) lib->throws = 1;
    macho_open_eh_runtime();
    if (macho_eh_symbol) {
        void* eh = macho_eh_symbol(name);
        if (eh) return eh;
    }

    for (size_t i = 0; i < sizeof(macho_aliases) / sizeof(macho_aliases[0]); i++)
        if (strcmp(name, macho_aliases[i].darwin) == 0) { name = macho_aliases[i].bionic; break; }

    char remapped[MACHO_NAME_MAX];
    if (macho_rewrite_name(name, remapped, sizeof(remapped))) name = remapped;

    macho_open_runtime();
    for (int i = 0; i < 4; i++) {
        if (!macho_rt_handles[i]) continue;
        void* a = dlsym(macho_rt_handles[i], name);
        if (a) return a;
    }

    /* Weak definitions the image itself provides - function-local statics and their guards
     * (Bullet's btTransform::getIdentity() and friends) - come through the weak-bind table so
     * dyld could coalesce them across images. Nobody else defines them here: bind to our own. */
    void* own = macho_dlsym(lib, own_name);
    if (own) return own;

    return dlsym(RTLD_DEFAULT, name);
}

/* ------------------------------------------------------------------------------------------ */
/* Bounded LEB128 readers. A malformed stream fails the load instead of running off the end.   */

static int read_uleb(const uint8_t** p, const uint8_t* end, uint64_t* out) {
    uint64_t v = 0;
    int shift = 0;
    while (*p < end) {
        uint8_t b = *(*p)++;
        if (shift < 64) v |= (uint64_t)(b & 0x7F) << shift;
        shift += 7;
        if (!(b & 0x80)) { *out = v; return 1; }
        if (shift > 70) return 0;
    }
    return 0;
}

static int read_sleb(const uint8_t** p, const uint8_t* end, int64_t* out) {
    int64_t v = 0;
    int shift = 0;
    uint8_t b;
    do {
        if (*p >= end) return 0;
        b = *(*p)++;
        if (shift < 64) v |= (int64_t)(b & 0x7F) << shift;
        shift += 7;
        if (shift > 70) return 0;
    } while (b & 0x80);
    if (shift < 64 && (b & 0x40)) v |= -((int64_t)1 << shift);
    *out = v;
    return 1;
}

/* Address inside the mapping for (segment index, offset), or NULL when out of range. */
static uint8_t* macho_seg_addr(macho_lib_t* lib, int seg, uint64_t off, size_t need) {
    if (seg < 0 || seg >= lib->nseg) return NULL;
    macho_segment_t* s = &lib->seg[seg];
    uint64_t target = s->vmaddr + off;          /* dyld lets ADD_ADDR_ULEB wrap; so do we */
    if (target < s->vmaddr || target + need > s->vmaddr + s->vmsize) return NULL;
    if (target + need > lib->map_size) return NULL;
    return lib->base + target;                  /* __TEXT is at vmaddr 0, so vmaddr == offset */
}

/* ------------------------------------------------------------------------------------------ */
/* Rebase: add the slide to every absolute pointer the image holds.                           */

static const char* macho_apply_rebase(macho_lib_t* lib, const uint8_t* p, const uint8_t* end) {
    uint32_t type = 0;
    int seg = -1;
    uint64_t off = 0;
    uint64_t slide = (uint64_t)(uintptr_t)lib->base;

#define REBASE_ONE() do {                                                        \
        uint8_t* a = macho_seg_addr(lib, seg, off, 8);                           \
        if (!a) return "rebase target outside its segment";                      \
        if (type != REBASE_TYPE_POINTER) return "rebase type other than pointer"; \
        *(uint64_t*)a += slide;                                                  \
    } while (0)

    while (p < end) {
        uint8_t b = *p++;
        uint8_t imm = b & REBASE_IMMEDIATE_MASK;
        uint64_t u1, u2;
        switch (b & REBASE_OPCODE_MASK) {
        case REBASE_OPCODE_DONE:
            return NULL;
        case REBASE_OPCODE_SET_TYPE_IMM:
            type = imm;
            break;
        case REBASE_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB:
            seg = imm;
            if (!read_uleb(&p, end, &off)) return "truncated rebase stream";
            break;
        case REBASE_OPCODE_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated rebase stream";
            off += u1;
            break;
        case REBASE_OPCODE_ADD_ADDR_IMM_SCALED:
            off += (uint64_t)imm * 8;
            break;
        case REBASE_OPCODE_DO_REBASE_IMM_TIMES:
            for (uint32_t i = 0; i < imm; i++) { REBASE_ONE(); off += 8; }
            break;
        case REBASE_OPCODE_DO_REBASE_ULEB_TIMES:
            if (!read_uleb(&p, end, &u1)) return "truncated rebase stream";
            for (uint64_t i = 0; i < u1; i++) { REBASE_ONE(); off += 8; }
            break;
        case REBASE_OPCODE_DO_REBASE_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated rebase stream";
            REBASE_ONE();
            off += u1 + 8;
            break;
        case REBASE_OPCODE_DO_REBASE_ULEB_TIMES_SKIPPING_ULEB:
            if (!read_uleb(&p, end, &u1) || !read_uleb(&p, end, &u2)) return "truncated rebase stream";
            for (uint64_t i = 0; i < u1; i++) { REBASE_ONE(); off += 8 + u2; }
            break;
        default:
            return "unknown rebase opcode";
        }
    }
#undef REBASE_ONE
    return NULL;
}

/* ------------------------------------------------------------------------------------------ */
/* Bind: point every import slot at the matching bionic / libc++_shared symbol.               */

void* macho_dlsym(macho_lib_t* lib, const char* name);

static void macho_note_unresolved(macho_load_ctx_t* ctx, const char* name) {
    if (ctx->nunresolved < MACHO_MAX_UNRESOLVED) ctx->unresolved[ctx->nunresolved] = strdup(name);
    ctx->nunresolved++;
}

static const char* macho_apply_binds(macho_load_ctx_t* ctx, const uint8_t* p, const uint8_t* end,
                                     int lazy) {
    macho_lib_t* lib = ctx->lib;
    uint32_t type = BIND_TYPE_POINTER;
    int seg = -1;
    uint64_t off = 0;
    int64_t addend = 0;
    const char* sym = NULL;
    void* sym_addr = NULL;
    int sym_missing = 0;

#define BIND_ONE() do {                                                          \
        uint8_t* a = macho_seg_addr(lib, seg, off, 8);                           \
        if (!a) return "bind target outside its segment";                        \
        if (type != BIND_TYPE_POINTER) return "bind type other than pointer";    \
        if (!sym) return "bind without a symbol";                                \
        if (!sym_missing) *(uint64_t*)a = (uint64_t)(uintptr_t)sym_addr + (uint64_t)addend; \
        lib->nbinds++;                                                           \
    } while (0)

    while (p < end) {
        uint8_t b = *p++;
        uint8_t imm = b & BIND_IMMEDIATE_MASK;
        uint64_t u1, u2;
        switch (b & BIND_OPCODE_MASK) {
        case BIND_OPCODE_DONE:
            /* Ends the table - except in the lazy one, where it separates entries. */
            if (!lazy) return NULL;
            break;
        case BIND_OPCODE_SET_DYLIB_ORDINAL_IMM:
        case BIND_OPCODE_SET_DYLIB_SPECIAL_IMM:
            break;  /* which Apple library it came from does not matter here */
        case BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated bind stream";
            break;
        case BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM: {
            sym = (const char*)p;
            while (p < end && *p) p++;
            if (p >= end) return "unterminated symbol name in bind stream";
            p++;
            sym_addr = macho_resolve_import(lib, sym);
            sym_missing = (sym_addr == NULL);
            if (sym_missing) macho_note_unresolved(ctx, sym);
            if (macho_import_is_layout_sensitive(sym)
                    && !macho_string_import(sym[0] == '_' ? sym + 1 : sym)) {
                if (ctx->nlayout_conflict < MACHO_MAX_UNRESOLVED)
                    ctx->layout_conflict[ctx->nlayout_conflict] = strdup(sym);
                ctx->nlayout_conflict++;
            }
            break;
        }
        case BIND_OPCODE_SET_TYPE_IMM:
            type = imm;
            break;
        case BIND_OPCODE_SET_ADDEND_SLEB:
            if (!read_sleb(&p, end, &addend)) return "truncated bind stream";
            break;
        case BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB:
            seg = imm;
            if (!read_uleb(&p, end, &off)) return "truncated bind stream";
            break;
        case BIND_OPCODE_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated bind stream";
            off += u1;
            break;
        case BIND_OPCODE_DO_BIND:
            BIND_ONE();
            off += 8;
            break;
        case BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated bind stream";
            BIND_ONE();
            off += u1 + 8;
            break;
        case BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED:
            BIND_ONE();
            off += (uint64_t)imm * 8 + 8;
            break;
        case BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB:
            if (!read_uleb(&p, end, &u1) || !read_uleb(&p, end, &u2)) return "truncated bind stream";
            for (uint64_t i = 0; i < u1; i++) { BIND_ONE(); off += u2 + 8; }
            break;
        case BIND_OPCODE_THREADED:
            return "threaded (chained) binds";
        default:
            return "unknown bind opcode";
        }
    }
#undef BIND_ONE
    return NULL;
}

/* ------------------------------------------------------------------------------------------ */
/* Export trie -> flat sorted table.                                                          */

static int macho_add_export(macho_lib_t* lib, const char* name, void* addr) {
    if (lib->nexports == lib->export_cap) {
        int cap = lib->export_cap ? lib->export_cap * 2 : 64;
        macho_export_t* n = realloc(lib->exports, (size_t)cap * sizeof(*n));
        if (!n) return 0;
        lib->exports = n;
        lib->export_cap = cap;
    }
    const char* stored = strdup(name[0] == '_' ? name + 1 : name);
    if (!stored) return 0;
    /* The inline JNIEnv_ C++ wrappers are emitted as (weak) exports; their presence means the
     * dylib calls variadic JNI functions with an Apple va_list - see macho_jnienv.h. */
    if (strncmp(stored, "_ZN7JNIEnv_", 11) == 0) lib->wrap_env = 1;
    lib->exports[lib->nexports].name = stored;
    lib->exports[lib->nexports].addr = addr;
    lib->nexports++;
    return 1;
}

static const char* macho_walk_trie(macho_lib_t* lib, const uint8_t* start, const uint8_t* end,
                                   const uint8_t* node, char* name, size_t len, int depth) {
    if (depth > 128 || node < start || node >= end) return "malformed export trie";
    const uint8_t* p = node;
    uint64_t terminal_size;
    if (!read_uleb(&p, end, &terminal_size)) return "malformed export trie";
    const uint8_t* children = p + terminal_size;
    if (children > end) return "malformed export trie";

    if (terminal_size) {
        uint64_t flags, offset;
        if (!read_uleb(&p, end, &flags)) return "malformed export trie";
        if (flags & EXPORT_SYMBOL_FLAGS_REEXPORT) {
            /* A symbol that lives in another dylib. Nothing here needs one; skip it. */
        } else {
            if (!read_uleb(&p, end, &offset)) return "malformed export trie";
            if (flags & EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER) {
                /* Runtime-chosen implementation; not used by these files. Skip. */
            } else {
                void* addr = ((flags & EXPORT_SYMBOL_FLAGS_KIND_MASK) == EXPORT_SYMBOL_FLAGS_KIND_ABSOLUTE)
                             ? (void*)(uintptr_t)offset : (void*)(lib->base + offset);
                if (offset >= lib->map_size && (flags & EXPORT_SYMBOL_FLAGS_KIND_MASK) != EXPORT_SYMBOL_FLAGS_KIND_ABSOLUTE)
                    return "export outside the image";
                name[len] = '\0';
                if (!macho_add_export(lib, name, addr)) return "out of memory";
            }
        }
    }

    p = children;
    if (p >= end) return "malformed export trie";
    uint8_t nchildren = *p++;
    for (uint8_t i = 0; i < nchildren; i++) {
        size_t l = len;
        while (p < end && *p) {
            if (l + 1 >= MACHO_NAME_MAX) return "export name too long";
            name[l++] = (char)*p++;
        }
        if (p >= end) return "malformed export trie";
        p++;
        uint64_t child_off;
        if (!read_uleb(&p, end, &child_off)) return "malformed export trie";
        const char* err = macho_walk_trie(lib, start, end, start + child_off, name, l, depth + 1);
        if (err) return err;
    }
    return NULL;
}

static int macho_export_cmp(const void* a, const void* b) {
    return strcmp(((const macho_export_t*)a)->name, ((const macho_export_t*)b)->name);
}

void* macho_dlsym(macho_lib_t* lib, const char* name) {
    if (!lib || !name || !lib->nexports) return NULL;
    macho_export_t key = { name, NULL };
    macho_export_t* e = bsearch(&key, lib->exports, (size_t)lib->nexports, sizeof(*e), macho_export_cmp);
    return e ? e->addr : NULL;
}

int macho_lib_wraps_env(const macho_lib_t* lib) {
    return lib ? lib->wrap_env : 0;
}

const char* macho_lib_name(const macho_lib_t* lib) {
    return lib ? lib->name : "?";
}

/* ------------------------------------------------------------------------------------------ */
/* Load commands: collect segments, the dyld info, the init sections; refuse the unsupported. */

static const char* macho_parse_commands(macho_load_ctx_t* ctx, const mach_header_64_t* mh) {
    macho_lib_t* lib = ctx->lib;
    const uint8_t* p = (const uint8_t*)(mh + 1);
    const uint8_t* end = ctx->slice + ctx->slice_size;
    if ((size_t)mh->sizeofcmds > (size_t)(end - p)) return "load commands run past the slice";
    end = p + mh->sizeofcmds;

    for (uint32_t i = 0; i < mh->ncmds; i++) {
        if (p + sizeof(load_command_t) > end) return "truncated load commands";
        const load_command_t* lc = (const load_command_t*)p;
        if (lc->cmdsize < sizeof(load_command_t) || p + lc->cmdsize > end) return "bad load command size";

        switch (lc->cmd) {
        case 0x2: { /* LC_SYMTAB: C++ JNIEnv helpers are usually LOCAL, not trie exports. */
            if (lc->cmdsize < 24) return "bad symbol table command";
            uint32_t fields[4];
            memcpy(fields, p + 8, sizeof(fields));
            uint64_t symoff = fields[0], nsyms = fields[1], stroff = fields[2], strsize = fields[3];
            if (symoff > ctx->slice_size || nsyms > (ctx->slice_size - symoff) / 16
                    || stroff > ctx->slice_size || strsize > ctx->slice_size - stroff)
                return "symbol table outside slice";
            for (uint64_t k = 0; k < nsyms; ++k) {
                const uint8_t* entry = ctx->slice + symoff + k * 16;
                uint32_t strx;
                memcpy(&strx, entry, sizeof(strx));
                if (entry[4] & 0xe0) continue; /* STAB debugging entry */
                if (strx >= strsize) return "bad symbol string index";
                const char* name = (const char*)ctx->slice + stroff + strx;
                size_t available = (size_t)(strsize - strx);
                if (!memchr(name, 0, available)) return "unterminated symbol name";
                if (strncmp(name, "__ZN7JNIEnv_", sizeof("__ZN7JNIEnv_") - 1) == 0)
                    lib->wrap_env = 1;
            }
            break;
        }
        case LC_SEGMENT_64: {
            if (lc->cmdsize < sizeof(segment_command_64_t)) return "bad segment command";
            const segment_command_64_t* sc = (const segment_command_64_t*)p;
            if (lib->nseg == MACHO_MAX_SEGMENTS) return "too many segments";
            macho_segment_t* s = &lib->seg[lib->nseg++];
            memcpy(s->name, sc->segname, 16);
            s->name[16] = '\0';
            s->vmaddr = sc->vmaddr;
            s->vmsize = sc->vmsize;
            s->fileoff = sc->fileoff;
            s->filesize = sc->filesize;
            s->initprot = sc->initprot;
            if (sc->filesize > sc->vmsize) return "segment file size larger than its vm size";
            if (sc->fileoff + sc->filesize > ctx->slice_size) return "segment runs past the slice";
            if (lc->cmdsize < sizeof(segment_command_64_t) + (size_t)sc->nsects * sizeof(section_64_t))
                return "bad section table";
            const section_64_t* sect = (const section_64_t*)(sc + 1);
            for (uint32_t k = 0; k < sc->nsects; k++, sect++) {
                uint32_t type = sect->flags & SECTION_TYPE;
                if (strncmp(sect->sectname, "__unwind_info", 16) == 0) {
                    lib->unwind_addr = sect->addr;
                    lib->unwind_size = sect->size;
                }
                if ((sect->flags & (S_ATTR_PURE_INSTRUCTIONS | S_ATTR_SOME_INSTRUCTIONS)) && sect->size) {
                    if (ctx->ncode == MACHO_MAX_CODE_SECTS) return "too many code sections";
                    ctx->code[ctx->ncode].addr = sect->addr;
                    ctx->code[ctx->ncode].size = sect->size;
                    ctx->ncode++;
                }
                if (type >= S_THREAD_LOCAL_REGULAR && type <= S_THREAD_LOCAL_INIT_FUNCTION_POINTERS)
                    return "thread-local storage";
                if (strncmp(sect->sectname, "__objc", 6) == 0 || strncmp(sect->segname, "__OBJC", 6) == 0)
                    return "Objective-C";
                if (type == S_MOD_INIT_FUNC_POINTERS || type == S_INIT_FUNC_OFFSETS) {
                    if (ctx->ninit == MACHO_MAX_INIT_SECTS) return "too many init sections";
                    ctx->init[ctx->ninit].type = type;
                    ctx->init[ctx->ninit].addr = sect->addr;
                    ctx->init[ctx->ninit].size = sect->size;
                    ctx->ninit++;
                }
            }
            break;
        }
        case LC_DYLD_INFO:
        case LC_DYLD_INFO_ONLY:
            if (lc->cmdsize < sizeof(dyld_info_command_t)) return "bad dyld info command";
            ctx->dyld = (const dyld_info_command_t*)p;
            break;
        case LC_DYLD_CHAINED_FIXUPS:
            return "chained fixups (newer linker output than this loader reads)";
        case LC_DATA_IN_CODE: {
            if (lc->cmdsize < 16) return "bad data-in-code command";
            uint32_t f[2];
            memcpy(f, p + 8, sizeof(f));
            if (f[0] > ctx->slice_size || f[1] > ctx->slice_size - f[0] || f[1] % 8)
                return "data-in-code table outside the slice";
            ctx->dic_off = f[0];
            ctx->dic_size = f[1];
            break;
        }
        case LC_LOAD_DYLIB:
        case LC_LOAD_WEAK_DYLIB:
        case LC_REEXPORT_DYLIB:
        case LC_LOAD_UPWARD_DYLIB: {
            if (lc->cmdsize < sizeof(dylib_command_t)) return "bad dylib command";
            const dylib_command_t* dc = (const dylib_command_t*)p;
            if (dc->name_off >= lc->cmdsize) return "bad dylib name";
            const char* dep = (const char*)p + dc->name_off;
            size_t maxlen = lc->cmdsize - dc->name_off;
            if (strnlen(dep, maxlen) == maxlen) return "unterminated dylib name";
            if (!strstr(dep, "libc++") && !strstr(dep, "libSystem")) {
                static char why[160];
                snprintf(why, sizeof(why), "depends on %s", dep);
                return why;
            }
            break;
        }
        default:
            break;  /* symtab, uuid, build version, code signature, ...: not needed */
        }
        p += lc->cmdsize;
    }
    if (!ctx->dyld) return "no LC_DYLD_INFO (nothing tells us how to relocate it)";
    if (!lib->nseg) return "no segments";
    return NULL;
}

/* ------------------------------------------------------------------------------------------ */

/*
 * Apple built these dylibs for M1-class cores, and the compiler used LDAPR (ARMv8.3 RCPC, a
 * load-acquire with the weaker "processor consistent" ordering) in PopMan and PathFind - four
 * each, measured 2026-09-12. Cortex-A53/A57/A72/A73 do not have it and would die with SIGILL
 * the first time one runs. On such a CPU every LDAPR is rewritten into LDAR: the same load with
 * the same registers and size, and a stronger (sequentially consistent) acquire, which any code
 * written for the weaker one is correct under. Everything else in these files is plain ARMv8.0.
 *
 *   LDAPR{B,H,}  size 111000 101 11111 110000 Rn Rt   mask 0x3FFFFC00 == 0x38BFC000
 *   LDAR{B,H,}   size 001000 110 11111 111111 Rn Rt   = (insn & 0xC00003FF) | 0x08DFFC00
 *
 * Only sections that hold instructions are scanned, and the LC_DATA_IN_CODE ranges (jump tables
 * and literals the linker left inside __text) are skipped, so data that happens to look like an
 * LDAPR is never touched. ZOMDROID_MACHO_FORCE_LDAR=1 applies the rewrite on any CPU (tests).
 */
static int macho_cpu_has_rcpc(void) {
    const char* force = getenv("ZOMDROID_MACHO_FORCE_LDAR");
    if (force && strcmp(force, "1") == 0) return 0;
    return (getauxval(AT_HWCAP) & HWCAP_LRCPC) != 0;
}

static int macho_is_data_in_code(const macho_load_ctx_t* ctx, uint64_t addr) {
    const macho_lib_t* lib = ctx->lib;
    for (uint32_t k = 0; k + 8 <= ctx->dic_size; k += 8) {
        const uint8_t* e = ctx->slice + ctx->dic_off + k;
        uint32_t off;
        uint16_t len;
        memcpy(&off, e, sizeof(off));
        memcpy(&len, e + 4, sizeof(len));
        for (int s = 0; s < lib->nseg; s++) {   /* the table holds file offsets */
            const macho_segment_t* sg = &lib->seg[s];
            if (off < sg->fileoff || off >= sg->fileoff + sg->filesize) continue;
            uint64_t start = sg->vmaddr + (off - sg->fileoff);
            if (addr + 4 > start && addr < start + len) return 1;
            break;
        }
    }
    return 0;
}

static void macho_rewrite_ldapr(macho_load_ctx_t* ctx) {
    macho_lib_t* lib = ctx->lib;
    int rewritten = 0, skipped = 0;
    for (int i = 0; i < ctx->ncode; i++) {
        uint64_t a = (ctx->code[i].addr + 3) & ~(uint64_t)3;
        uint64_t end = ctx->code[i].addr + ctx->code[i].size;
        if (end > lib->map_size || end < ctx->code[i].addr) continue;
        for (; a + 4 <= end; a += 4) {
            uint32_t insn;
            memcpy(&insn, lib->base + a, sizeof(insn));
            if ((insn & 0x3FFFFC00u) != 0x38BFC000u) continue;
            if (macho_is_data_in_code(ctx, a)) { skipped++; continue; }
            insn = (insn & 0xC00003FFu) | 0x08DFFC00u;
            memcpy(lib->base + a, &insn, sizeof(insn));
            if (macho_verbose()) LOGI("[macho] %s: LDAPR at +0x%llx -> LDAR", lib->name, (unsigned long long)a);
            rewritten++;
        }
    }
    if (rewritten || skipped)
        LOG_REPORTED("[macho] %s: CPU lacks RCPC, %d LDAPR rewritten to LDAR, %d data words left alone",
                     lib->name, rewritten, skipped);
}

static const char* macho_check_range(const macho_load_ctx_t* ctx, uint32_t off, uint32_t size) {
    if ((uint64_t)off + size > ctx->slice_size) return "dyld info table runs past the slice";
    return NULL;
}

static void macho_run_initializers(macho_load_ctx_t* ctx) {
    macho_lib_t* lib = ctx->lib;
    int ran = 0;
    for (int i = 0; i < ctx->ninit; i++) {
        macho_init_sect_t* s = &ctx->init[i];
        if (s->addr + s->size > lib->map_size) continue;
        if (s->type == S_MOD_INIT_FUNC_POINTERS) {
            uint64_t* fp = (uint64_t*)(lib->base + s->addr);   /* rebased already */
            for (uint64_t k = 0; k < s->size / 8; k++, fp++) {
                if (!*fp) continue;
                ((void (*)(int, char**, char**, char**, void*))(uintptr_t)*fp)(0, NULL, NULL, NULL, NULL);
                ran++;
            }
        } else {
            uint32_t* op = (uint32_t*)(lib->base + s->addr);
            for (uint64_t k = 0; k < s->size / 4; k++, op++) {
                if (*op >= lib->map_size) continue;
                ((void (*)(int, char**, char**, char**, void*))(lib->base + *op))(0, NULL, NULL, NULL, NULL);
                ran++;
            }
        }
    }
    if (ran) LOGI("[macho] %s: ran %d static initializer(s)", lib->name, ran);
}

static void macho_free_lib(macho_lib_t* lib) {
    if (!lib) return;
    if (lib->base) munmap(lib->base, lib->map_size);
    for (int i = 0; i < lib->nexports; i++) free((void*)lib->exports[i].name);
    free(lib->exports);
    free(lib);
}

static macho_lib_t* macho_reject(macho_load_ctx_t* ctx, uint8_t* file, const char* name,
                                 const char* reason) {
    LOG_REPORTED("[macho] %s rejected: %s", name, reason);
    for (int i = 0; i < ctx->nunresolved && i < MACHO_MAX_UNRESOLVED; i++) free(ctx->unresolved[i]);
    for (int i = 0; i < ctx->nlayout_conflict && i < MACHO_MAX_UNRESOLVED; i++) free(ctx->layout_conflict[i]);
    macho_free_lib(ctx->lib);
    free(file);
    return NULL;
}

macho_lib_t* macho_load(const char* path) {
    const char* name = base_name(path);
    macho_load_ctx_t ctx;
    memset(&ctx, 0, sizeof(ctx));

    /* --- the file, whole: the dyld tables live in __LINKEDIT and are read from here --- */
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { LOG_REPORTED("[macho] %s rejected: open failed (%s)", name, strerror(errno)); return NULL; }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0 || (uint64_t)st.st_size > MACHO_MAX_FILE) {
        close(fd);
        LOG_REPORTED("[macho] %s rejected: unusable file size", name);
        return NULL;
    }
    size_t file_size = (size_t)st.st_size;
    uint8_t* file = malloc(file_size);
    if (!file) { close(fd); LOG_REPORTED("[macho] %s rejected: out of memory", name); return NULL; }
    size_t got = 0;
    while (got < file_size) {
        ssize_t r = read(fd, file + got, file_size - got);
        if (r <= 0) break;
        got += (size_t)r;
    }
    close(fd);
    if (got != file_size) { free(file); LOG_REPORTED("[macho] %s rejected: short read", name); return NULL; }

    ctx.lib = calloc(1, sizeof(macho_lib_t));
    if (!ctx.lib) { free(file); LOG_REPORTED("[macho] %s rejected: out of memory", name); return NULL; }
    snprintf(ctx.lib->name, sizeof(ctx.lib->name), "%s", name);

    /* --- the arm64 slice --- */
    ctx.slice = file;
    ctx.slice_size = file_size;
    if (file_size >= sizeof(fat_header_t) && be32(*(const uint32_t*)file) == FAT_MAGIC_BE) {
        const fat_header_t* fh = (const fat_header_t*)file;
        uint32_t n = be32(fh->nfat_arch);
        if (n > 16 || sizeof(fat_header_t) + (size_t)n * sizeof(fat_arch_t) > file_size)
            return macho_reject(&ctx, file, name, "bad fat header");
        const fat_arch_t* fa = (const fat_arch_t*)(fh + 1);
        const uint8_t* slice = NULL;
        size_t slice_size = 0;
        int saw_arm64e = 0;
        for (uint32_t i = 0; i < n; i++, fa++) {
            if (be32(fa->cputype) != CPU_TYPE_ARM64) continue;
            if ((be32(fa->cpusubtype) & CPU_SUBTYPE_MASK) == CPU_SUBTYPE_ARM64E) { saw_arm64e = 1; continue; }
            uint32_t off = be32(fa->offset), sz = be32(fa->size);
            if ((uint64_t)off + sz > file_size) return macho_reject(&ctx, file, name, "arm64 slice runs past the file");
            slice = file + off;
            slice_size = sz;
            break;
        }
        if (!slice) return macho_reject(&ctx, file, name, saw_arm64e ? "arm64e slice only (pointer authentication)" : "no arm64 slice");
        ctx.slice = slice;
        ctx.slice_size = slice_size;
    }

    if (ctx.slice_size < sizeof(mach_header_64_t)) return macho_reject(&ctx, file, name, "too small for a Mach-O header");
    const mach_header_64_t* mh = (const mach_header_64_t*)ctx.slice;
    if (mh->magic != MH_MAGIC_64) return macho_reject(&ctx, file, name, "not a 64-bit Mach-O");
    if (mh->cputype != CPU_TYPE_ARM64) return macho_reject(&ctx, file, name, "not arm64");
    if ((mh->cpusubtype & CPU_SUBTYPE_MASK) == CPU_SUBTYPE_ARM64E) return macho_reject(&ctx, file, name, "arm64e (pointer authentication)");
    if (mh->filetype != MH_DYLIB) return macho_reject(&ctx, file, name, "not a dylib");

    const char* err = macho_parse_commands(&ctx, mh);
    if (err) return macho_reject(&ctx, file, name, err);

    /* --- one mapping for the whole image; __TEXT must sit at vmaddr 0 (it does in a dylib) --- */
    macho_lib_t* lib = ctx.lib;
    uint64_t hi = 0;
    for (int i = 0; i < lib->nseg; i++) {
        if (lib->seg[i].vmsize == 0) continue;
        if (lib->seg[i].vmaddr + lib->seg[i].vmsize > hi) hi = lib->seg[i].vmaddr + lib->seg[i].vmsize;
    }
    if (lib->seg[0].vmaddr != 0 || strcmp(lib->seg[0].name, "__TEXT") != 0)
        return macho_reject(&ctx, file, name, "first segment is not __TEXT at address 0");
    if (hi == 0 || hi > (256u << 20)) return macho_reject(&ctx, file, name, "unreasonable image size");
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    lib->map_size = (size_t)((hi + (uint64_t)page - 1) & ~((uint64_t)page - 1));
    void* base = mmap(NULL, lib->map_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) { lib->base = NULL; return macho_reject(&ctx, file, name, "mmap failed"); }
    lib->base = base;
    for (int i = 0; i < lib->nseg; i++) {
        macho_segment_t* s = &lib->seg[i];
        if (!s->filesize || !s->vmsize) continue;
        if (s->vmaddr + s->filesize > lib->map_size) return macho_reject(&ctx, file, name, "segment outside the mapping");
        memcpy(lib->base + s->vmaddr, ctx.slice + s->fileoff, (size_t)s->filesize);
    }

    const dyld_info_command_t* di = ctx.dyld;
    if ((err = macho_check_range(&ctx, di->rebase_off, di->rebase_size)) ||
        (err = macho_check_range(&ctx, di->bind_off, di->bind_size)) ||
        (err = macho_check_range(&ctx, di->weak_bind_off, di->weak_bind_size)) ||
        (err = macho_check_range(&ctx, di->lazy_bind_off, di->lazy_bind_size)) ||
        (err = macho_check_range(&ctx, di->export_off, di->export_size)))
        return macho_reject(&ctx, file, name, err);

    /* --- exports first: binding may have to fall back to the image's own weak definitions --- */
    {
        char namebuf[MACHO_NAME_MAX];
        const uint8_t* trie = ctx.slice + di->export_off;
        if (di->export_size) {
            err = macho_walk_trie(lib, trie, trie + di->export_size, trie, namebuf, 0, 0);
            if (err) return macho_reject(&ctx, file, name, err);
        }
        if (lib->nexports > 1) qsort(lib->exports, (size_t)lib->nexports, sizeof(*lib->exports), macho_export_cmp);
    }

    /* --- relocate and bind --- */
    err = macho_apply_rebase(lib, ctx.slice + di->rebase_off, ctx.slice + di->rebase_off + di->rebase_size);
    if (err) return macho_reject(&ctx, file, name, err);
    err = macho_apply_binds(&ctx, ctx.slice + di->bind_off, ctx.slice + di->bind_off + di->bind_size, 0);
    if (err) return macho_reject(&ctx, file, name, err);
    err = macho_apply_binds(&ctx, ctx.slice + di->weak_bind_off, ctx.slice + di->weak_bind_off + di->weak_bind_size, 0);
    if (err) return macho_reject(&ctx, file, name, err);
    /* Lazy pointers too, all of them now: nothing in this process can bind them on first use. */
    err = macho_apply_binds(&ctx, ctx.slice + di->lazy_bind_off, ctx.slice + di->lazy_bind_off + di->lazy_bind_size, 1);
    if (err) return macho_reject(&ctx, file, name, err);

    if (ctx.nunresolved) {
        /* The full list is the point: it says what bionic or libc++_shared lacks this time. */
        char why[1400];
        int n = snprintf(why, sizeof(why), "%d unresolved import(s):", ctx.nunresolved);
        for (int i = 0; i < ctx.nunresolved && i < MACHO_MAX_UNRESOLVED && n < (int)sizeof(why) - 2; i++)
            n += snprintf(why + n, sizeof(why) - (size_t)n, " %s", ctx.unresolved[i]);
        if (ctx.nunresolved > MACHO_MAX_UNRESOLVED && n < (int)sizeof(why) - 4) snprintf(why + n, sizeof(why) - (size_t)n, " ...");
        return macho_reject(&ctx, file, name, why);
    }
    int refuse_layout = ctx.nlayout_conflict != 0;
#ifdef MACHO_POPMAN_CONSTRUCTOR_HARNESS
    // Standalone, disposable test process only. Never define this in the app CMake target.
    // This allows constructors to be probed; it does NOT establish stream or exception safety.
    if (strcmp(name, "libPZPopMan.dylib") == 0) {
        LOG_REPORTED("[macho] UNSAFE CONSTRUCTOR HARNESS: bypassing PopMan layout guard");
        refuse_layout = 0;
    }
#endif
    if (refuse_layout) {
        char why[1400];
        int n = snprintf(why, sizeof(why), "uses std::string/iostream, whose layout differs between "
                         "Apple's and Android's libc++ (%d import(s), e.g.", ctx.nlayout_conflict);
        for (int i = 0; i < ctx.nlayout_conflict && i < 4 && n < (int)sizeof(why) - 2; i++)
            n += snprintf(why + n, sizeof(why) - (size_t)n, " %s", ctx.layout_conflict[i]);
        if (n < (int)sizeof(why) - 2) snprintf(why + n, sizeof(why) - (size_t)n, ")");
        return macho_reject(&ctx, file, name, why);
    }

    if (lib->wrap_env && !macho_jnienv_ready())
        return macho_reject(&ctx, file, name, "JNIEnv wrapper unavailable");

    /* --- ARMv8.3 loads on cores without them, while __TEXT is still writable --- */
    if (!macho_cpu_has_rcpc()) macho_rewrite_ldapr(&ctx);

    /* --- protections: what the file asks for, __TEXT executable, and the icache told --- */
    for (int i = 0; i < lib->nseg; i++) {
        macho_segment_t* s = &lib->seg[i];
        if (!s->vmsize) continue;
        int prot = 0;
        if (s->initprot & VM_PROT_READ) prot |= PROT_READ;
        if (s->initprot & VM_PROT_WRITE) prot |= PROT_WRITE;
        if (s->initprot & VM_PROT_EXECUTE) prot |= PROT_EXEC;
        size_t len = (size_t)((s->vmsize + (uint64_t)page - 1) & ~((uint64_t)page - 1));
        if (s->vmaddr + len > lib->map_size) len = lib->map_size - (size_t)s->vmaddr;
        if (prot & PROT_EXEC) __builtin___clear_cache((char*)(lib->base + s->vmaddr), (char*)(lib->base + s->vmaddr + len));
        if (mprotect(lib->base + s->vmaddr, len, prot) != 0) {
            static char why[96];
            snprintf(why, sizeof(why), "mprotect(%s) failed (%s)", s->name, strerror(errno));
            return macho_reject(&ctx, file, name, why);
        }
    }

    free(file);
    file = NULL;

    /* --- unwind tables, so a throw inside the dylib finds its catch (and cleanups) --- */
    if (lib->unwind_size && macho_eh_register && macho_eh_symbol) {
        if (lib->unwind_addr + lib->unwind_size > lib->map_size)
            return macho_reject(&ctx, file, name, "__unwind_info outside the image");
        const void* eh_frame = NULL;
        uint32_t nfde = 0, nlsda = 0;
        const char* uerr = macho_unwind_translate(lib->base, lib->map_size,
                                                  lib->base + lib->unwind_addr, (uint32_t)lib->unwind_size,
                                                  macho_eh_symbol("__gxx_personality_v0"),
                                                  &eh_frame, &nfde, &nlsda);
        if (uerr) {
            /* A library that never throws can live without tables; one that does cannot. */
            if (lib->throws) {
                char why[240];
                snprintf(why, sizeof(why), "unwind info not translatable (%s)", uerr);
                return macho_reject(&ctx, file, name, why);
            }
            LOG_REPORTED("[macho] %s: unwind info not translatable (%s); it does not throw, going on",
                         lib->name, uerr);
        } else {
            int registered = macho_eh_register(eh_frame);
            if ((uint32_t)registered != nfde) {
                char why[160];
                snprintf(why, sizeof(why), "unwind registration took %d of %u entries", registered, nfde);
                return macho_reject(&ctx, file, name, why);
            }
            lib->eh_frame = eh_frame;
            LOGI("[macho] %s: %u unwind entries registered, %u with handlers", lib->name, nfde, nlsda);
        }
    } else if (lib->throws) {
        LOG_REPORTED("[macho] %s throws C++ exceptions but has no usable unwind support here: a throw "
                     "will abort", lib->name);
    }

    /* --- static constructors, as dyld would run them, with nothing to hand them --- */
    macho_run_initializers(&ctx);

    LOG_REPORTED("[macho] %s loaded natively: arm64 slice, %d exports, %d binds, %zu KB at %p%s%s",
                 lib->name, lib->nexports, lib->nbinds, lib->map_size / 1024, (void*)lib->base,
                 lib->eh_frame ? ", exceptions on" : "", lib->wrap_env ? ", JNIEnv wrapped" : "");
    return lib;
}
