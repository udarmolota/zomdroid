/*
 * macho_bridge.c - Apple-ABI shims for JNI entry points that live in a macOS dylib.
 *
 * Apple's arm64 calling convention and the Linux AAPCS64 that HotSpot uses agree on the first
 * eight integer and eight floating-point arguments (registers) and on return values. They part
 * ways in two places that matter for JNI:
 *
 *  1. Arguments that overflow the registers. Linux gives every one an 8-byte stack slot. Apple
 *     packs them at their natural size and alignment: an int takes 4 bytes, a jboolean 1, a
 *     float 4, a long/double/pointer 8. LightingJNI has eight natives with 9-20 arguments
 *     (squareSetLightTransmission takes twenty floats), so the callee would read the wrong slots.
 *  2. Sub-32-bit integer arguments in registers. Apple's callee assumes the caller already
 *     sign- or zero-extended a char/short/bool to 32 bits; Linux callers do not have to, and
 *     HotSpot does not.
 *
 * So for each JNI function the linker asks: does this signature hit either case? If not, the JVM
 * gets the dylib's function directly. If it does, a few instructions are assembled that fix the
 * registers, repack the stack, call the function and return whatever it returned untouched.
 * The shim never touches x18 (reserved on Android) and never touches x8 (indirect result).
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "logger.h"
#include "macho_loader.h"
#include "macho_jnienv.h"

#define LOG_TAG "zomdroid-macho"

#define LOG_REPORTED(fmt, ...) do {                  \
        LOGI(fmt __VA_OPT__(,) __VA_ARGS__);         \
        printf(fmt "\n" __VA_OPT__(,) __VA_ARGS__);  \
    } while (0)

/* --- the handful of AArch64 encodings the shim is made of ---------------------------------- */

/* Fixed instructions. */
#define INSN_STP_X29_X30_PRE   0xA9BF7BFDu   /* stp x29, x30, [sp, #-16]! */
#define INSN_MOV_X29_SP        0x910003FDu   /* mov x29, sp                */
#define INSN_MOV_SP_X29        0x910003BFu   /* mov sp, x29                */
#define INSN_LDP_X29_X30_POST  0xA8C17BFDu   /* ldp x29, x30, [sp], #16    */
#define INSN_BLR_X16           0xD63F0200u   /* blr x16                    */
#define INSN_RET               0xD65F03C0u   /* ret                        */

static uint32_t insn_sub_sp_imm(uint32_t imm12) { return 0xD10003FFu | ((imm12 & 0xFFFu) << 10); }  /* sub sp, sp, #imm */
static uint32_t insn_add_sp_imm(uint32_t imm12) { return 0x910003FFu | ((imm12 & 0xFFFu) << 10); }  /* add sp, sp, #imm */

/* Register pairs to/from [sp, #off] (signed-offset forms, imm7 scaled by 8): the argument
 * registers saved around the call that fetches the wrapper JNIEnv. */
static uint32_t insn_stp_x_sp(unsigned rt, unsigned rt2, unsigned off) { return 0xA9000000u | ((off / 8) << 15) | (rt2 << 10) | (31u << 5) | rt; }
static uint32_t insn_ldp_x_sp(unsigned rt, unsigned rt2, unsigned off) { return 0xA9400000u | ((off / 8) << 15) | (rt2 << 10) | (31u << 5) | rt; }
static uint32_t insn_stp_d_sp(unsigned rt, unsigned rt2, unsigned off) { return 0x6D000000u | ((off / 8) << 15) | (rt2 << 10) | (31u << 5) | rt; }
static uint32_t insn_ldp_d_sp(unsigned rt, unsigned rt2, unsigned off) { return 0x6D400000u | ((off / 8) << 15) | (rt2 << 10) | (31u << 5) | rt; }
static uint32_t insn_movz_x16(uint16_t imm, unsigned hw) { return 0xD2800010u | (hw << 21) | ((uint32_t)imm << 5); }
static uint32_t insn_movk_x16(uint16_t imm, unsigned hw) { return 0xF2800010u | (hw << 21) | ((uint32_t)imm << 5); }

/* Extensions in place on w<n>: SBFM/UBFM with immr=0 and imms = width-1. */
static uint32_t insn_sxtb_w(unsigned n) { return 0x13001C00u | (n << 5) | n; }
static uint32_t insn_sxth_w(unsigned n) { return 0x13003C00u | (n << 5) | n; }
static uint32_t insn_uxtb_w(unsigned n) { return 0x53001C00u | (n << 5) | n; }
static uint32_t insn_uxth_w(unsigned n) { return 0x53003C00u | (n << 5) | n; }

/* Loads from the Linux argument area at [x29, #off] into x9/w9, stores to the Apple area at
 * [sp, #off]. Unsigned-offset forms; the immediate is scaled by the access size. */
static uint32_t insn_ldr_x9_x29(unsigned off)  { return 0xF94003A9u | ((off / 8) << 10); }
static uint32_t insn_str_x9_sp(unsigned off)   { return 0xF90003E9u | ((off / 8) << 10); }
static uint32_t insn_ldr_w9_x29(unsigned off)  { return 0xB94003A9u | ((off / 4) << 10); }
static uint32_t insn_str_w9_sp(unsigned off)   { return 0xB90003E9u | ((off / 4) << 10); }
static uint32_t insn_ldrh_w9_x29(unsigned off) { return 0x794003A9u | ((off / 2) << 10); }
static uint32_t insn_strh_w9_sp(unsigned off)  { return 0x790003E9u | ((off / 2) << 10); }
static uint32_t insn_ldrb_w9_x29(unsigned off) { return 0x394003A9u | (off << 10); }
static uint32_t insn_strb_w9_sp(unsigned off)  { return 0x390003E9u | (off << 10); }

/* --- the signature, as the linker's letters ---------------------------------------------- */

typedef struct {
    char type;
    unsigned size;      /* bytes on the Apple stack */
} shim_arg_t;

static int arg_class_and_size(char t, int* is_fp, unsigned* size) {
    switch (t) {
    case 'p': case 'I': *is_fp = 0; *size = 8; return 1;
    case 'i':           *is_fp = 0; *size = 4; return 1;
    case 'w': case 'W': *is_fp = 0; *size = 2; return 1;
    case 'c': case 'C': *is_fp = 0; *size = 1; return 1;
    case 'd':           *is_fp = 1; *size = 8; return 1;
    case 'f':           *is_fp = 1; *size = 4; return 1;
    default: return 0;
    }
}

#define SHIM_MAX_ARGS 64
#define SHIM_MAX_INSNS 512

void* macho_jni_bridge(EmulatedLib* lib, void* target, const char* arg_types, const char* sym_name,
                       int wrap_env) {
    if (!target || !arg_types) return NULL;

    /* Walk the arguments the way both ABIs assign registers: integers to x0-x7 in order,
     * floats to d0-d7 in order, the rest to the stack in argument order. */
    shim_arg_t stack_args[SHIM_MAX_ARGS];
    int nstack = 0;
    unsigned fix_reg[8];        /* x<n> that needs an extension, and how */
    char fix_type[8];
    int nfix = 0;
    int ngpr = 0, nfpr = 0;

    for (const char* t = arg_types; *t; t++) {
        int is_fp;
        unsigned size;
        if (!arg_class_and_size(*t, &is_fp, &size)) {
            LOG_REPORTED("[macho] bridge %s: unknown argument letter '%c' in %s", sym_name, *t, arg_types);
            return NULL;
        }
        if (is_fp) {
            if (nfpr < 8) { nfpr++; continue; }
        } else {
            if (ngpr < 8) {
                if (size < 4) { fix_reg[nfix] = (unsigned)ngpr; fix_type[nfix] = *t; nfix++; }
                ngpr++;
                continue;
            }
        }
        if (nstack == SHIM_MAX_ARGS) {
            LOG_REPORTED("[macho] bridge %s: too many stack arguments", sym_name);
            return NULL;
        }
        stack_args[nstack].type = *t;
        stack_args[nstack].size = size;
        nstack++;
    }

    if (!nfix && !nstack && !wrap_env) {
        LOGI("[macho] bridge %s args=%s direct", sym_name, arg_types);
        return target;
    }

    /* Apple stack layout: each argument at its natural alignment, area rounded to 16. */
    unsigned apple_off[SHIM_MAX_ARGS];
    unsigned area = 0;
    for (int i = 0; i < nstack; i++) {
        unsigned s = stack_args[i].size;
        area = (area + s - 1) & ~(s - 1);
        apple_off[i] = area;
        area += s;
    }
    area = (area + 15u) & ~15u;
    if (area > 4095) {
        LOG_REPORTED("[macho] bridge %s: stack area too large (%u bytes)", sym_name, area);
        return NULL;
    }

    uint32_t code[SHIM_MAX_INSNS];
    int n = 0;
#define EMIT(x) do { if (n == SHIM_MAX_INSNS) { LOG_REPORTED("[macho] bridge %s: shim too long", sym_name); return NULL; } code[n++] = (x); } while (0)

    EMIT(INSN_STP_X29_X30_PRE);
    EMIT(INSN_MOV_X29_SP);

    /* The wrapper JNIEnv replaces x0. Fetching it is a C call, so every other argument register
     * is parked on the stack around it: x1-x7 and d0-d7, 128 bytes, sp back to x29 afterwards. */
    if (wrap_env) {
        EMIT(insn_sub_sp_imm(128));
        EMIT(insn_stp_x_sp(1, 2, 0));  EMIT(insn_stp_x_sp(3, 4, 16));
        EMIT(insn_stp_x_sp(5, 6, 32)); EMIT(insn_stp_x_sp(7, 31, 48));   /* x7 and xzr */
        EMIT(insn_stp_d_sp(0, 1, 64)); EMIT(insn_stp_d_sp(2, 3, 80));
        EMIT(insn_stp_d_sp(4, 5, 96)); EMIT(insn_stp_d_sp(6, 7, 112));
        uint64_t w = (uint64_t)(uintptr_t)macho_jnienv_wrap;
        EMIT(insn_movz_x16((uint16_t)(w & 0xFFFF), 0));
        EMIT(insn_movk_x16((uint16_t)((w >> 16) & 0xFFFF), 1));
        EMIT(insn_movk_x16((uint16_t)((w >> 32) & 0xFFFF), 2));
        EMIT(insn_movk_x16((uint16_t)((w >> 48) & 0xFFFF), 3));
        EMIT(INSN_BLR_X16);
        EMIT(insn_ldp_x_sp(1, 2, 0));  EMIT(insn_ldp_x_sp(3, 4, 16));
        EMIT(insn_ldp_x_sp(5, 6, 32)); EMIT(insn_ldp_x_sp(7, 31, 48));
        EMIT(insn_ldp_d_sp(0, 1, 64)); EMIT(insn_ldp_d_sp(2, 3, 80));
        EMIT(insn_ldp_d_sp(4, 5, 96)); EMIT(insn_ldp_d_sp(6, 7, 112));
        EMIT(insn_add_sp_imm(128));
    }

    if (area) EMIT(insn_sub_sp_imm(area));

    /* Register arguments Apple expects extended to 32 bits. */
    for (int i = 0; i < nfix; i++) {
        switch (fix_type[i]) {
        case 'c': EMIT(insn_sxtb_w(fix_reg[i])); break;   /* jbyte    */
        case 'C': EMIT(insn_uxtb_w(fix_reg[i])); break;   /* jboolean */
        case 'w': EMIT(insn_sxth_w(fix_reg[i])); break;   /* jshort   */
        case 'W': EMIT(insn_uxth_w(fix_reg[i])); break;   /* jchar    */
        default: break;
        }
    }

    /* Stack arguments: Linux slot k sits at [x29 + 16 + 8k] (x29 = sp at entry minus our 16-byte
     * frame); copy each at its own size into the Apple area at [sp + apple_off]. Little-endian,
     * so the low bytes of the 8-byte slot are the value. */
    for (int i = 0; i < nstack; i++) {
        unsigned src = 16 + 8u * (unsigned)i;
        unsigned dst = apple_off[i];
        switch (stack_args[i].size) {
        case 8: EMIT(insn_ldr_x9_x29(src));  EMIT(insn_str_x9_sp(dst));  break;
        case 4: EMIT(insn_ldr_w9_x29(src));  EMIT(insn_str_w9_sp(dst));  break;
        case 2: EMIT(insn_ldrh_w9_x29(src)); EMIT(insn_strh_w9_sp(dst)); break;
        default: EMIT(insn_ldrb_w9_x29(src)); EMIT(insn_strb_w9_sp(dst)); break;
        }
    }

    /* Call, restore, return: x0/d0 come back exactly as the dylib left them. */
    uint64_t addr = (uint64_t)(uintptr_t)target;
    EMIT(insn_movz_x16((uint16_t)(addr & 0xFFFF), 0));
    EMIT(insn_movk_x16((uint16_t)((addr >> 16) & 0xFFFF), 1));
    EMIT(insn_movk_x16((uint16_t)((addr >> 32) & 0xFFFF), 2));
    EMIT(insn_movk_x16((uint16_t)((addr >> 48) & 0xFFFF), 3));
    EMIT(INSN_BLR_X16);
    EMIT(INSN_MOV_SP_X29);
    EMIT(INSN_LDP_X29_X30_POST);
    EMIT(INSN_RET);
#undef EMIT

    void* mem = zomdroid_emulation_install_code(lib, code, n * (int)sizeof(uint32_t));
    if (!mem) {
        LOG_REPORTED("[macho] bridge %s: no code page", sym_name);
        return NULL;
    }
    LOG_REPORTED("[macho] bridge %s args=%s stack-shim=%d extended=%d apple-area=%u env-wrap=%d",
                 sym_name, arg_types, nstack ? 1 : 0, nfix, area, wrap_env ? 1 : 0);
    return mem;
}
