#include <string.h>
#include <malloc.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <errno.h>
#include <stdlib.h>

#include "logger.h"
#include "wrapped_jni.h"
#include "emulation.h"

#include "box64/src/include/box64context.h"
#include "box64/src/include/x64emu.h"
#include "box64/src/include/box64stack.h"
#include "box64/src/include/librarian.h"
#include "box64/src/include/callback.h"
#include "box64/src/include/box32.h"

#define LOG_TAG "zomdroid-emu"

#define A64_SF_32 0
#define A64_SF_64 1
#define A64_REG_0 0
#define A64_REG_1 1
#define A64_REG_2 2
#define A64_REG_18 18
#define A64_REG_29 29
#define A64_REG_30 30
#define A64_REG_SP 31

static long page_size;
uint64_t g_wrapped_jni_env;
uint64_t g_wrapped_jvm;

uint32_t base_mov_reg(uint8_t sf, uint8_t Rm, uint8_t Rd) {
    return ((sf & 0x1) << 31) | (0b0101010000 << 21) | ((Rm & 0x1F) << 16) | (0b00000011111 << 5) | (Rd & 0x1F);
}

uint32_t base_mov_sp(uint8_t sf, uint8_t Rn, uint8_t Rd) {
    return ((sf & 0x1) << 31) | (0b001000100000000000000 << 10) | ((Rn & 0x1F) << 5) | (Rd & 0x1F);
}

uint32_t base_movz(uint8_t sf, uint8_t hw, uint16_t imm16, uint8_t rd) {
    return ((sf & 0x1) << 31) | (0b10100101 << 23) | ((hw & 0x3) << 21) | ((imm16 & 0xFFFF) << 5) | (rd & 0x1F);
}
uint32_t base_movk(uint8_t sf, uint8_t hw, uint16_t imm16, uint8_t rd) {
    return ((sf & 0x1) << 31) | (0b11100101 << 23) | ((hw & 0x3) << 21) | ((imm16 & 0xFFFF) << 5) | (rd & 0x1F);
}

uint32_t base_stp_prei(uint8_t sf, int8_t imm7, uint8_t Rt2, uint8_t Rn, uint8_t Rt) {
    return ((sf & 0x1) << 31) | (0b010100110 << 22) | ((imm7 & 0x7F) << 15) | ((Rt2 & 0x1F) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}
uint32_t base_stp(uint8_t sf, int8_t imm7, uint8_t Rt2, uint8_t Rn, uint8_t Rt) {
    return ((sf & 0x1) << 31) | (0b010100100 << 22) | ((imm7 & 0x7F) << 15) | ((Rt2 & 0x1F) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t base_ldp(uint8_t sf, int8_t imm7, uint8_t Rt2, uint8_t Rn, uint8_t Rt) {
    return ((sf & 0x1) << 31) | (0b010100101 << 22) | ((imm7 & 0x7F) << 15) | ((Rt2 & 0x1F) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t base_ldp_posti(uint8_t sf, int8_t imm7, uint8_t Rt2, uint8_t Rn, uint8_t Rt) {
    return ((sf & 0x1) << 31) | (0b010100011 << 22) | ((imm7 & 0x7F) << 15) | ((Rt2 & 0x1F) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t base_blr(uint8_t Rn) {
    return (0b1101011000111111000000 << 10) | ((Rn & 0x1F) << 5);
}

uint32_t base_ret(uint8_t Rn) {
    return (0b1101011001011111000000 << 10 ) | ((Rn & 0x1F) << 5);
}

uint32_t base_sub_imm(uint8_t sf, uint8_t sh, uint16_t imm12, uint8_t Rn, uint8_t Rd) {
    return ((sf & 0x1) << 31) | (0b10100010 << 23) | ((sh & 0x1) << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rd & 0x1F);
}
uint32_t base_add_imm(uint8_t sf, uint8_t sh, uint16_t imm12, uint8_t Rn, uint8_t Rd) {
    return ((sf & 0x1) << 31) | (0b00100010 << 23) | ((sh & 0x1) << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rd & 0x1F);
}

uint32_t base_str_imm(uint8_t sf, uint16_t imm12, uint8_t Rn, uint8_t Rt) {
    return (0b1 << 31) | ((sf & 0x1) << 30) | (0b11100100 << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t base_ldr_imm(uint8_t sf, uint16_t imm12, uint8_t Rn, uint8_t Rd) {
    uint32_t insn = (0b1 << 31) | ((sf & 0x1) << 30) | (0b11100101 << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rd & 0x1F);
    return  insn;
}

uint32_t base_strb_imm(uint16_t imm12, uint8_t Rn, uint8_t Rt) {
    return (0b0011100100 << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t simd_mov_vec(uint8_t Q, uint8_t Rm, uint8_t Rn, uint8_t Rd) {
    return (0b0 << 31) | ((Q & 0x1) << 30) | (0b001110101 << 21) | ((Rm & 0x1F) << 16) | (0b000111 << 10) | ((Rn & 0x1F) << 5) | (Rd & 0x1F);
}

uint32_t simd_str_imm(uint8_t size, uint8_t opc, uint16_t imm12, uint8_t Rn, uint8_t Rt) {
    return ((size & 0x3) << 30) | (0b111101 << 24) | ((opc & 0b11) << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t simd_ldr_imm(uint8_t size, uint8_t opc, uint16_t imm12, uint8_t Rn, uint8_t Rt) {
    return ((size & 0b11) << 30) | (0b111101 << 24) | ((opc & 0b11) << 22) | ((imm12 & 0xFFF) << 10) | ((Rn & 0x1F) << 5) | (Rt & 0x1F);
}

uint32_t simd_fcvt(uint8_t ftype, uint8_t opc, uint8_t Rn, uint8_t Rd) {
    return (0b00011110 << 24) | ((ftype & 0b11) << 22) | (0b10001 << 17) | ((opc & 0b11) << 15) | (0b10000 << 10) | ((Rn & 0x1F) << 5) | (Rd & 0x1F);
}

static void assemble_box64_jni_trampoline(uint32_t** code, int* code_size, const char* signature, char returnType, uint64_t emulated_fn) {
#define ADD_INSN(I) *code_size = *code_size + (int) sizeof (uint32_t); \
                *code = realloc(*code, *code_size);\
                insn_index = *code_size / (int) sizeof(uint32_t) - 1;\
                (*code)[insn_index] = I;

    int insn_index = 0;

    const int argc = (int)strlen(signature);

    const int i64_reserved = 2; // RunFunctionFmtSpecial args
    int i64_argc = 0;
    int i64_argc_stack = 0;

    const int df64_reserved = 0;
    int df64_argc = 0;
    int df64_argc_stack = 0;

    // calculate how many arguments are in registers/stack
    for (int i = 0; i < argc; i++) {
        switch(signature[i]) {
            case 'f':
            case 'd':
                if (df64_argc < 8) df64_argc++; else df64_argc_stack++;
                break;
            case 'p':
            case 'i':
            case 'u':
            case 'I':
            case 'U':
            case 'L':
            case 'l':
            case 'w':
            case 'W':
            case 'c':
            case 'C':
                if (i64_argc < 8) i64_argc++; else i64_argc_stack++;
                break;
        }
    }

    // set up frame pointer if needed
    int fp_offset = 0;
    if (i64_argc > 8 - i64_reserved) fp_offset += (i64_argc + i64_reserved - 8) * 8;
    if (df64_argc > 8 - df64_reserved) fp_offset += (df64_argc + df64_reserved - 8) * 8;
    fp_offset += (i64_argc_stack + df64_argc_stack) * 8;

    // allocate stack
    int stack_size = fp_offset + 16 + argc + 1;
    stack_size = (stack_size + 15) & ~15;
    if (fp_offset > 0) {
        ADD_INSN(base_sub_imm(A64_SF_64, 0, stack_size, A64_REG_SP, A64_REG_SP))
        ADD_INSN(base_stp(A64_SF_64, (fp_offset) / 8, A64_REG_30, A64_REG_SP, A64_REG_29))
        ADD_INSN(base_add_imm(A64_SF_64, 0, fp_offset, A64_REG_SP, A64_REG_29))
    } else {
        ADD_INSN(base_stp_prei(A64_SF_64, -stack_size / 8, A64_REG_30, A64_REG_SP, A64_REG_29))
        ADD_INSN(base_mov_sp(A64_SF_64, A64_REG_SP, A64_REG_29))
    }

    // overwrite first arg (JNIEnv*) with our wrapper
    ADD_INSN(base_movz(A64_SF_64, 0, g_wrapped_jni_env & 0xFFFF, A64_REG_0))
    ADD_INSN(base_movk(A64_SF_64, 1, (g_wrapped_jni_env >> 16) & 0xFFFF, A64_REG_0))
    ADD_INSN(base_movk(A64_SF_64, 2, (g_wrapped_jni_env >> 32) & 0xFFFF, A64_REG_0))
    ADD_INSN(base_movk(A64_SF_64, 3, (g_wrapped_jni_env >> 48) & 0xFFFF, A64_REG_0))

    // shift arguments to make space for reserved
    for (int i = argc - 1; i >= 0; i--) {
        switch(signature[i]) {
            case 'f':
                if (df64_argc_stack > 0) {
                    int src_offset = (i64_argc_stack + df64_argc_stack) * 8 - 8;
                    int i64_off = i64_argc + i64_reserved >= 8 ? (i64_argc + i64_reserved - 8) * 8 : 0;
                    int target_offset = (df64_argc + df64_reserved - 8) * 8 + i64_off + src_offset;
                    ADD_INSN(simd_ldr_imm(0b10, 0b01, (src_offset + stack_size) / 4, A64_REG_SP, A64_REG_18))
                    ADD_INSN(simd_fcvt(0b00, 0b01, A64_REG_18, A64_REG_18))
                    ADD_INSN(simd_str_imm(0b11, 0b00, target_offset / 8, A64_REG_SP, A64_REG_18))
                    df64_argc_stack--;
                } else {
                    if (df64_argc + df64_reserved <= 8) {
                        ADD_INSN(simd_fcvt(0b00, 0b01, df64_argc - 1, df64_argc - 1 + df64_reserved))
                    } else {
                        int i64_off = i64_argc + i64_reserved >= 8 ? (i64_argc + i64_reserved - 8) * 8 : 0;
                        int target_offset = (df64_argc + df64_reserved - 8) * 8 + i64_off - 8;
                        ADD_INSN(simd_fcvt(0b00, 0b01, df64_argc - 1, A64_REG_18))
                        ADD_INSN(simd_str_imm(0b11, 0b00, target_offset / 8, A64_REG_SP, A64_REG_18))
                    }
                    df64_argc--;
                }
                break;
            case 'd':
                if (df64_argc_stack > 0) {
                    int src_offset = (i64_argc_stack + df64_argc_stack) * 8 - 8;
                    int i64_off = i64_argc + i64_reserved >= 8 ? (i64_argc + i64_reserved - 8) * 8 : 0;
                    int target_offset = (df64_argc + df64_reserved - 8) * 8 + i64_off + src_offset;
                    ADD_INSN(simd_ldr_imm(0b11, 0b01, (src_offset + stack_size) / 8, A64_REG_SP, A64_REG_18))
                    ADD_INSN(simd_str_imm(0b11, 0b00, target_offset / 8, A64_REG_SP, A64_REG_18))
                    df64_argc_stack--;
                } else {
                    if (df64_reserved == 0) {
                        df64_argc--;
                        break;
                    }

                    if (df64_argc + df64_reserved <= 8) {
                        ADD_INSN(simd_mov_vec(0, df64_argc - 1, df64_argc - 1, df64_argc - 1 + df64_reserved))
                    } else {
                        int i64_off = i64_argc + i64_reserved >= 8 ? (i64_argc + i64_reserved - 8) * 8 : 0;
                        int target_offset = (df64_argc + df64_reserved - 8) * 8 + i64_off - 8;
                        ADD_INSN(simd_str_imm(0b11, 0b00, target_offset / 8, A64_REG_SP, df64_argc - 1))
                    }
                    df64_argc--;
                }
                break;
            case 'i':
            case 'u':
            case 'w':
            case 'W':
            case 'c':
            case 'C':
                if (i64_argc_stack > 0) {
                    int src_offset = (i64_argc_stack + df64_argc_stack) * 8 - 8;
                    int d64_off = df64_argc + df64_reserved >= 8 ? (df64_argc + df64_reserved - 8) * 8 : 0;
                    int dst_offset = (i64_argc + i64_reserved - 8) * 8 + d64_off + src_offset;
                    ADD_INSN(base_ldr_imm(A64_SF_32, (src_offset + stack_size) / 4, A64_REG_SP, A64_REG_18))
                    ADD_INSN(base_str_imm(A64_SF_32, dst_offset / 4, A64_REG_SP, A64_REG_18))
                    i64_argc_stack--;
                } else {
                    if (i64_reserved == 0) {
                        i64_argc--;
                        break;
                    }

                    if (i64_argc + i64_reserved <= 8) {
                        ADD_INSN(base_mov_reg(A64_SF_32, i64_argc - 1, i64_argc - 1 + i64_reserved))
                    } else {
                        int d64_off = df64_argc + df64_reserved >= 8 ? (df64_argc + df64_reserved - 8) * 8 : 0;
                        int offset = (i64_argc + i64_reserved - 8) * 8 + d64_off - 8;
                        ADD_INSN(base_str_imm(A64_SF_32, offset / 4, A64_REG_SP, i64_argc - 1))
                    }
                    i64_argc--;
                }
                break;
            case 'p':
            case 'I':
            case 'U':
            case 'L':
            case 'l':
                if (i64_argc_stack > 0) {
                    int src_offset = (i64_argc_stack + df64_argc_stack) * 8 - 8;
                    int d64_off = df64_argc + df64_reserved >= 8 ? (df64_argc + df64_reserved - 8) * 8 : 0;
                    int dst_offset = (i64_argc + i64_reserved - 8) * 8 + d64_off + src_offset;
                    ADD_INSN(base_ldr_imm(A64_SF_64, (src_offset + stack_size) / 8, A64_REG_SP, A64_REG_18))
                    ADD_INSN(base_str_imm(A64_SF_64, dst_offset / 8, A64_REG_SP, A64_REG_18))
                    i64_argc_stack--;
                } else {
                    if (i64_reserved == 0) {
                        i64_argc--;
                        break;
                    }

                    if (i64_argc + i64_reserved <= 8) {
                        ADD_INSN(base_mov_reg(A64_SF_64, i64_argc - 1, i64_argc - 1 + i64_reserved))
                    } else {
                        int d64_off = df64_argc + df64_reserved >= 8 ? (df64_argc + df64_reserved - 8) * 8 : 0;
                        int offset = (i64_argc + i64_reserved - 8) * 8 + d64_off - 8;
                        ADD_INSN(base_str_imm(A64_SF_64, offset / 8, A64_REG_SP, i64_argc - 1))
                    }
                    i64_argc--;
                }
                break;
        }
    }

    // put first reserved arg - emulated function ptr
    ADD_INSN(base_movz(A64_SF_64, 0, emulated_fn & 0xFFFF, A64_REG_0))
    ADD_INSN(base_movk(A64_SF_64, 1, (emulated_fn >> 16) & 0xFFFF, A64_REG_0))
    ADD_INSN(base_movk(A64_SF_64, 2, (emulated_fn >> 32) & 0xFFFF, A64_REG_0))
    ADD_INSN(base_movk(A64_SF_64, 3, (emulated_fn >> 48) & 0xFFFF, A64_REG_0))

    // put second reserved arg - emulated function signature
    int signature_offset = fp_offset + 16;
    for (int i = 0; signature[i] != 0;) {
        if (i + 8 <= strlen(signature)) {
            uint64_t chunk = *(uint64_t *)(signature + i);
            ADD_INSN(base_movz(A64_SF_64, 0, chunk & 0xFFFF, A64_REG_18))
            ADD_INSN(base_movk(A64_SF_64, 1, (chunk >> 16) & 0xFFFF, A64_REG_18))
            ADD_INSN(base_movk(A64_SF_64, 2, (chunk >> 32) & 0xFFFF, A64_REG_18))
            ADD_INSN(base_movk(A64_SF_64, 3, (chunk >> 48) & 0xFFFF, A64_REG_18))
            ADD_INSN(base_str_imm(A64_SF_64, (signature_offset + i) / 8, A64_REG_SP, A64_REG_18))
            i += 8;
        } else if (i + 4 <= strlen(signature)) {
            uint32_t chunk = *(uint32_t*)(signature + i);
            ADD_INSN(base_movz(A64_SF_32, 0, chunk & 0xFFFF, A64_REG_18))
            ADD_INSN(base_movk(A64_SF_32, 1, (chunk >> 16) & 0xFFFF, A64_REG_18))
            ADD_INSN(base_str_imm(A64_SF_32, (signature_offset + i) / 4, A64_REG_SP, A64_REG_18))
            i += 4;
        } else {
            ADD_INSN(base_movz(A64_SF_32, 0, signature[i], A64_REG_18))
            ADD_INSN(base_strb_imm(signature_offset + i, A64_REG_SP, A64_REG_18))
            i++;
        }
    }
    ADD_INSN(base_movz(A64_SF_32, 0, 0, A64_REG_18))
    ADD_INSN(base_strb_imm(signature_offset + strlen(signature), A64_REG_SP, A64_REG_18))
    ADD_INSN(base_add_imm(A64_SF_64, 0, signature_offset, A64_REG_SP, A64_REG_1))

/*    // put third reserved arg - emulated function return type
    ADD_INSN(base_movz(A64_SF_32, 0, returnType, A64_REG_2))*/

    // prepare and call RunFunctionFmt
    ADD_INSN(base_movz(A64_SF_64, 0, (uint64_t) &RunFunctionFmt & 0xFFFF, A64_REG_18))
    ADD_INSN(base_movk(A64_SF_64, 1, ((uint64_t) &RunFunctionFmt >> 16) & 0xFFFF, A64_REG_18))
    ADD_INSN(base_movk(A64_SF_64, 2, ((uint64_t) &RunFunctionFmt >> 32) & 0xFFFF, A64_REG_18))
    ADD_INSN(base_movk(A64_SF_64, 3, ((uint64_t) &RunFunctionFmt >> 48) & 0xFFFF, A64_REG_18))
    ADD_INSN(base_blr(A64_REG_18))

    // free stack
    if (fp_offset > 0) {
        ADD_INSN(base_ldp(A64_SF_64, fp_offset / 8, A64_REG_30, A64_REG_SP, A64_REG_29))
        ADD_INSN(base_add_imm(A64_SF_64, 0, stack_size, A64_REG_SP, A64_REG_SP))
    } else {
        ADD_INSN(base_ldp_posti(A64_SF_64, stack_size / 8, A64_REG_30, A64_REG_SP, A64_REG_29))
    }

    // return
    ADD_INSN(base_ret(A64_REG_30))
}

void* zomdroid_emulation_bridge_jni_symbol(EmulatedLib *lib, uint64_t fn, const char* arg_types, char ret_type) {
    uint32_t* code = NULL;
    int code_size = 0;
    assemble_box64_jni_trampoline(&code, &code_size, arg_types, ret_type, fn);

    void* mem = NULL;

    // find a page with enough space for the code
    for (int i = 0; i < lib->page_count; i++) {
        if (lib->page_code_size[i] + code_size <= page_size) {
            mem = lib->mapped_pages[i] + lib->page_code_size[i];
            lib->page_code_size[i] += code_size;
            break;
        }
    }

    // or allocate a new one
    if (mem == NULL) {
        mem = mmap(NULL, page_size, PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (mem == MAP_FAILED) {
            LOGE("Failed to mmap memory: %s", strerror(errno));
            free(code);
            return NULL;
        }

        int new_page_count = lib->page_count + 1;

        void** new_mapped_pages = realloc(lib->mapped_pages, new_page_count * sizeof(*lib->mapped_pages));
        int* new_page_code_size = realloc(lib->page_code_size, new_page_count * sizeof(*lib->page_code_size));

        if (!new_mapped_pages || !new_page_code_size) {
            LOGE("Failed to reallocate memory: %s", strerror(errno));

            munmap(mem, page_size);

            if (new_mapped_pages) lib->mapped_pages = new_mapped_pages;
            if (new_page_code_size) lib->page_code_size = new_page_code_size;

            free(code);
            return NULL;
        }

        lib->mapped_pages = new_mapped_pages;
        lib->page_code_size = new_page_code_size;

        lib->mapped_pages[lib->page_count] = mem;
        lib->page_code_size[lib->page_count] = code_size;

        lib->page_count = new_page_count;
    }

    memcpy(mem, code, code_size);

    free(code);

    __builtin___clear_cache(mem, mem + code_size);

    return mem;
}

static void box64_load_gnu_libc() {
    /* By default box64 uses wrapped bionic libc. It lacks some functionality needed for the game,
     * so we need to load gnu libc to provide it. SONAMEs of the gnu libc and its dependencies are patched, otherwise
     * box64 will recognize them and replace with wrapped
     * */

    needed_libs_t* needed_libs = new_neededlib(3);
    needed_libs->names[0] = strdup("libc.so.6"); // This goes first so its symbols are prioritized during search. Is there a better way?
    needed_libs->names[1] = strdup("_ld-linux-x86-64.so.2");
    needed_libs->names[2] = strdup("_libc.so.6");
    if (AddNeededLib(my_context->maplib, 0, 0, 0, needed_libs, NULL, my_context, thread_get_emu()) != 0) {
        LOGE("Failed to load needed libraries in box64");
        RemoveNeededLib(my_context->maplib, 0, needed_libs, my_context, thread_get_emu());
        free_neededlib(needed_libs);
        return;
    }
    free_neededlib(needed_libs);
}

static void* get_self_handle() {
    Dl_info info;
    if (!dladdr((void*)get_self_handle, &info)) {
        LOGE("dladdr failed.");
        return NULL;
    }
    return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
}

void init_box64() {
    LOGI("Initialising box64...");
    page_size = sysconf(_SC_PAGESIZE);

    LoadEnvVariables();

    box64_pagesize = sysconf(_SC_PAGESIZE);
    if(!box64_pagesize)
        box64_pagesize = 4096;

    box64context_t *context = NewBox64Context(0);
    context->argv[0] = strdup("java"); // needed for wrapped libc, probably other things too

    dlclose(context->box64lib); // by default it's a process handle, which is not what box compiled as shared lib actually needs
    context->box64lib = get_self_handle();

    PrependList(&context->box64_ld_lib, getenv("BOX64_LD_LIBRARY_PATH"), 1);

    if(CalcStackSize(context) != 0) {
        LOGE("CalcStackSize failed at %s", __func__ );
        FreeBox64Context(&context);
        return;
    }

    x64emu_t *emu = NewX64Emu(context, context->ep, (uintptr_t)context->stack, context->stacksz, 0);

    thread_set_emu(emu);
}

int zomdroid_emulation_init() {
    init_box64();

    box64_load_gnu_libc();

    needed_libs_t *needed_lib = new_neededlib(1);
    needed_lib->names[0] = strdup("libjniwrapper.so");
    if (AddNeededLib(my_context->maplib, 0, 0, 0, needed_lib, NULL, my_context, thread_get_emu()) != 0) {
        LOGE("Failed to load jni wrapper library in box64");
        RemoveNeededLib(my_context->maplib, 0, needed_lib, my_context, thread_get_emu());
        free_neededlib(needed_lib);
        return -1;
    }
    free_neededlib(needed_lib);

    uint64_t init_fn = FindGlobalSymbol(my_context->maplib, "jni_wrapper_init", -1, NULL, 0);
    if (init_fn == 0) {
        LOGE("Failed to locate jni_wrapper_init in box64 symbols");
        return -1;
    }
    RunFunction(init_fn, 0);

    g_wrapped_jni_env = FindGlobalSymbol(my_context->maplib, "g_wrapped_jni_env", -1, NULL, 0);
    if (g_wrapped_jni_env == 0) {
        LOGE("Failed to locate g_wrapped_jni_env in box64 symbols");
        return -1;
    }

    g_wrapped_jvm = FindGlobalSymbol(my_context->maplib, "g_wrapped_jvm", -1, NULL, 0);
    if (g_wrapped_jvm == 0) {
        LOGE("Failed to locate g_wrapped_jvm in box64 symbols");
        return -1;
    }

    return 0;
}

// this will fail for double values, smth wrong with vararg
//void test_print_vararg() {
//    uintptr_t print_vararg_fn = FindGlobalSymbol(my_context->maplib, "print_vararg", 0,0,0);
//    RunFunctionFmt(print_vararg_fn, "pidididid","idididid", 1, 1.1, 2, 2.2, 3, 3.3, 4, 4.4);
//    RunFunctionFmt(print_vararg_fn, "pidididid","idididid", 5, 5.5, 6, 6.6, 7, 7.7, 8, 8.8);
//
//    long page_size = sysconf(_SC_PAGESIZE);
//    uint32_t* code = NULL;
//    int code_size = 0;
//    assemble_box64_jni_trampoline(&code, &code_size, "pidididid", print_vararg_fn);
//    void* mem = mmap(NULL, page_size, PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
//    if (mem == MAP_FAILED) {
//        LOGE("mmap failed with error: %s", strerror(errno));
//        return;
//    }
//    memcpy(mem, code, code_size);
//
//    free(code);
//
//    __builtin___clear_cache(mem, mem + code_size);
//
//    void (*mapped_code)(char*, int, double, int, double, int, double, int, double) = (void(*)())mem;
//    mapped_code("idididid", 5, 5.5, 6, 6.6, 7, 7.7, 8, 8.8);
//
//    if (munmap(mem, page_size) != 0) {
//        LOGE("munmap failed with error: %s", strerror(errno));
//    }
//}
//
//void test_print_16int_16double() {
//    uintptr_t print_16int_16double_fn = FindGlobalSymbol(my_context->maplib, "print_16int_16double", 0,0,0);
//    RunFunctionFmt(print_16int_16double_fn, "iiiiiiiiiiiiiiiidddddddddddddddd",
//                   1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
//                   1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12, 1.13, 1.14, 1.15, 1.16);
//
//    long page_size = sysconf(_SC_PAGESIZE);
//    uint32_t* code = NULL;
//    int code_size = 0;
//    assemble_box64_jni_trampoline(&code, &code_size, "iiiiiiiiiiiiiiiidddddddddddddddd",
//                                  print_16int_16double_fn);
//    void* mem = mmap(NULL, page_size, PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
//    if (mem == MAP_FAILED) {
//        LOGE("mmap failed with error: %s", strerror(errno));
//        return;
//    }
//    memcpy(mem, code, code_size);
//
//    free(code);
//
//    __builtin___clear_cache(mem, mem + code_size);
//
//    asm volatile("isb");
//
//    void (*mapped_code)(int, int, int, int, int, int, int, int, int, int, int, int, int, int, int, int,
//            double, double , double , double , double, double, double, double, double, double, double, double, double, double, double, double
//            ) = (void(*)())mem;
//    mapped_code(17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
//                2.17, 2.18, 2.19, 2.20, 2.21, 2.22, 2.23, 2.24, 2.25, 2.26, 2.27, 2.28, 2.29, 2.30, 2.31, 2.32);
//
//    if (munmap(mem, page_size) != 0) {
//        LOGE("munmap failed with error: %s", strerror(errno));
//    }
//}
//
//void test_add_two_numbers() {
//    uintptr_t add_two_numbers_fn = FindGlobalSymbol(my_context->maplib, "add_two_numbers", 0, 0, 0);
//    if (add_two_numbers_fn == 0) {
//        LOGE("FindGlobalSymbol failed");
//        return;
//    }
//    int result = (int) RunFunction(add_two_numbers_fn, 2, 4,5);
//    LOGD("add_two_numbers result: %d", result);
//
//    long page_size = sysconf(_SC_PAGESIZE);
//
//    uint32_t* code = NULL;
//    int code_size = 0;
//    assemble_box64_jni_trampoline(&code, &code_size, "ii", add_two_numbers_fn);
//    void* mem = mmap(NULL, page_size, PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
//    if (mem == MAP_FAILED) {
//        LOGE("mmap failed with error: %s", strerror(errno));
//        return;
//    }
//    memcpy(mem, code, code_size);
//
//    free(code);
//
//    __builtin___clear_cache(mem, mem + code_size);
//
//    int (*mapped_code)(int, int) = (int(*)(int, int))mem;
//    result = (int) mapped_code(2, 3);
//
//    LOGD("add_two_numbers trampoline result: %d", result);
//
//    if (munmap(mem, page_size) != 0) {
//        LOGE("munmap failed with error: %s", strerror(errno));
//    }
//}
//
//void test_print_message() {
//    uintptr_t print_message_fn = FindGlobalSymbol(my_context->maplib, "print_message", 0,0,0);
//    char msg[] = "Hello, World!";
//    RunFunction(print_message_fn, 1, msg);
//}
//
//void test_vaarg() {
//    long page_size = sysconf(_SC_PAGESIZE);
//
//    uint32_t* code = NULL;
//    int code_size = 0;
//    assemble_box64_jni_trampoline(&code, &code_size, "iiiiiiwiwiwi", 0);
//    void* mem = mmap(NULL, page_size, PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
//    if (mem == MAP_FAILED) {
//        LOGE("mmap failed with error: %s", strerror(errno));
//        return;
//    }
//    memcpy(mem, code, code_size);
//
//    free(code);
//
//    __builtin___clear_cache(mem, mem + code_size);
//
//    void (*mapped_code)(int, int, int, int, int, int, short, int, short, int, short, int) = mem;
//    mapped_code(INT_MAX, INT_MAX - 1, INT_MAX - 2, INT_MAX - 3, INT_MAX - 4, INT_MAX - 5,
//                               SHRT_MAX, INT_MAX - 6, SHRT_MAX - 1, INT_MAX - 7, SHRT_MAX - 2, INT_MAX - 8);
//
//    if (munmap(mem, page_size) != 0) {
//        LOGE("munmap failed with error: %s", strerror(errno));
//    }
//}
//
//void test_box64() {
//    test_vaarg();
////    needed_libs_t* needed_lib = new_neededlib(1);
////    needed_lib->names[0] = strdup("/data/data/com.zomdroid/files/libbox64test.so");
////    if (AddNeededLib(my_context->maplib, 0, 0, 0, needed_lib, NULL, my_context, thread_get_emu()) != 0) {
////        LOGE("Failed to load test library in box64");
////        RemoveNeededLib(my_context->maplib, 0, needed_lib, my_context, thread_get_emu());
////        free_neededlib(needed_lib);
////        return;
////    }
////    free_neededlib(needed_lib);
////
////
////    test_add_two_numbers();
////    test_print_message();
////    test_print_vararg();
////    test_print_16int_16double();
//}
//
