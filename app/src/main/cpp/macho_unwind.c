/*
 * macho_unwind.c - turns a dylib's Apple compact unwind info into DWARF CFI the unwinder in
 * libzomdroid_macho_eh.so can use.
 *
 * The dylibs carry only __unwind_info (no __eh_frame). Each entry there is one 32-bit word per
 * function: a mode (frame-based or frameless), which callee-saved register pairs the prologue
 * pushed, an optional personality index and an optional LSDA. That is enough to write, for every
 * function, one DWARF FDE saying where the CFA is and where each saved register lives, plus the
 * personality and LSDA in the augmentation - which is exactly what __gxx_personality_v0 needs to
 * run catch clauses and cleanups. The synthesised .eh_frame is handed to the EH runtime once, at
 * load time, and never freed.
 *
 * Register layout follows LLVM libunwind's CompactUnwinder_arm64 (the reference reader of these
 * encodings): in frame mode fp points at the saved {fp, lr} pair, so CFA = fp + 16, fp at CFA-16,
 * lr at CFA-8, and the saved pairs sit below fp in encoding order, x19 first at fp-8. Frameless
 * functions (leaves) get their CFA = sp + size; they cannot be mid-stack during a throw, so their
 * register bits are not translated.
 *
 * Anything this file does not understand - the DWARF escape mode, a third-level page kind, a
 * personality index it cannot resolve - refuses the whole library. A wrong table would only show
 * up as a crash on an error path, which is the worst kind of bug to have shipped.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "logger.h"
#include "macho_unwind.h"

#define LOG_TAG "zomdroid-macho"

/* --- Apple's __unwind_info format (mach-o/compact_unwind_encoding.h) ------------------------ */

#define UNWIND_IS_NOT_A_START                    0x80000000u
#define UNWIND_HAS_LSDA                          0x40000000u
#define UNWIND_PERSONALITY_MASK                  0x30000000u
#define UNWIND_PERSONALITY_SHIFT                 28

#define UNWIND_ARM64_MODE_MASK                   0x0F000000u
#define UNWIND_ARM64_MODE_FRAMELESS              0x02000000u
#define UNWIND_ARM64_MODE_DWARF                  0x03000000u
#define UNWIND_ARM64_MODE_FRAME                  0x04000000u
#define UNWIND_ARM64_FRAMELESS_STACK_SIZE_MASK   0x00FFF000u
#define UNWIND_ARM64_FRAME_X19_X20_PAIR          0x00000001u
#define UNWIND_ARM64_FRAME_X21_X22_PAIR          0x00000002u
#define UNWIND_ARM64_FRAME_X23_X24_PAIR          0x00000004u
#define UNWIND_ARM64_FRAME_X25_X26_PAIR          0x00000008u
#define UNWIND_ARM64_FRAME_X27_X28_PAIR          0x00000010u
#define UNWIND_ARM64_FRAME_D8_D9_PAIR            0x00000100u
#define UNWIND_ARM64_FRAME_D10_D11_PAIR          0x00000200u
#define UNWIND_ARM64_FRAME_D12_D13_PAIR          0x00000400u
#define UNWIND_ARM64_FRAME_D14_D15_PAIR          0x00000800u

#define UNWIND_SECOND_LEVEL_REGULAR              2u
#define UNWIND_SECOND_LEVEL_COMPRESSED           3u

typedef struct {
    uint32_t version;
    uint32_t commonEncodingsArraySectionOffset;
    uint32_t commonEncodingsArrayCount;
    uint32_t personalityArraySectionOffset;
    uint32_t personalityArrayCount;
    uint32_t indexSectionOffset;
    uint32_t indexCount;
} unwind_info_section_header_t;

typedef struct {
    uint32_t functionOffset;
    uint32_t secondLevelPagesSectionOffset;   /* 0 in the final (sentinel) entry */
    uint32_t lsdaIndexArraySectionOffset;
} unwind_info_section_header_index_entry_t;

typedef struct {
    uint32_t functionOffset;
    uint32_t lsdaOffset;
} unwind_info_section_header_lsda_index_entry_t;

typedef struct {
    uint32_t kind;
    uint16_t entryPageOffset;
    uint16_t entryCount;
} unwind_info_regular_second_level_page_header_t;

typedef struct {
    uint32_t functionOffset;
    uint32_t encoding;
} unwind_info_regular_second_level_entry_t;

typedef struct {
    uint32_t kind;
    uint16_t entryPageOffset;
    uint16_t entryCount;
    uint16_t encodingsPageOffset;
    uint16_t encodingsCount;
} unwind_info_compressed_second_level_page_header_t;

/* --- DWARF bits we emit -------------------------------------------------------------------- */

#define DW_CFA_nop              0x00
#define DW_CFA_offset           0x80   /* | reg (reg < 64), then uleb factored offset */
#define DW_CFA_offset_extended  0x05   /* uleb reg, uleb factored offset */
#define DW_CFA_def_cfa          0x0c   /* uleb reg, uleb offset */
#define DW_EH_PE_absptr         0x00
#define DW_REG_SP               31
#define DW_REG_FP               29
#define DW_REG_LR               30
#define DW_REG_V0               64     /* DWARF AArch64: v0..v31 = 64..95 */

/* A growable byte buffer for the .eh_frame we build. */
typedef struct {
    uint8_t* data;
    size_t len, cap;
    int oom;
} bytes_t;

static void put_bytes(bytes_t* b, const void* p, size_t n) {
    if (b->oom) return;
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap * 2 : 4096;
        while (cap < b->len + n) cap *= 2;
        uint8_t* d = realloc(b->data, cap);
        if (!d) { b->oom = 1; return; }
        b->data = d;
        b->cap = cap;
    }
    memcpy(b->data + b->len, p, n);
    b->len += n;
}
static void put_u8(bytes_t* b, uint8_t v)   { put_bytes(b, &v, 1); }
static void put_u32(bytes_t* b, uint32_t v) { put_bytes(b, &v, 4); }
static void put_u64(bytes_t* b, uint64_t v) { put_bytes(b, &v, 8); }
static void put_uleb(bytes_t* b, uint64_t v) {
    do {
        uint8_t byte = v & 0x7F;
        v >>= 7;
        if (v) byte |= 0x80;
        put_u8(b, byte);
    } while (v);
}
static void put_sleb(bytes_t* b, int64_t v) {
    int more = 1;
    while (more) {
        uint8_t byte = v & 0x7F;
        v >>= 7;
        if ((v == 0 && !(byte & 0x40)) || (v == -1 && (byte & 0x40))) more = 0;
        else byte |= 0x80;
        put_u8(b, byte);
    }
}

/* One CIE: "zPLR" with absolute 8-byte pointers everywhere, code alignment 1, data alignment -8,
 * return column x30, initial rule CFA = sp + 0. Returns its offset in the buffer. */
static size_t emit_cie(bytes_t* b, void* personality) {
    size_t start = b->len;
    put_u32(b, 0);                        /* length, patched below */
    put_u32(b, 0);                        /* CIE id */
    put_u8(b, 1);                         /* version */
    if (personality) put_bytes(b, "zPLR", 5); else put_bytes(b, "zR", 3);
    put_uleb(b, 1);                       /* code alignment factor */
    put_sleb(b, -8);                      /* data alignment factor */
    put_uleb(b, DW_REG_LR);               /* return address register */
    if (personality) {
        put_uleb(b, 1 + 8 + 1 + 1);       /* augmentation data length */
        put_u8(b, DW_EH_PE_absptr);       /* P: personality encoding */
        put_u64(b, (uint64_t)(uintptr_t)personality);
        put_u8(b, DW_EH_PE_absptr);       /* L: LSDA encoding */
        put_u8(b, DW_EH_PE_absptr);       /* R: FDE pointer encoding */
    } else {
        put_uleb(b, 1);
        put_u8(b, DW_EH_PE_absptr);       /* R */
    }
    put_u8(b, DW_CFA_def_cfa); put_uleb(b, DW_REG_SP); put_uleb(b, 0);
    while ((b->len - start) % 8) put_u8(b, DW_CFA_nop);
    uint32_t length = (uint32_t)(b->len - start - 4);
    if (!b->oom) memcpy(b->data + start, &length, 4);
    return start;
}

/* The CFI for one function. Returns 0 for an encoding this translator does not handle. */
static int emit_rules(bytes_t* b, uint32_t enc) {
    uint32_t mode = enc & UNWIND_ARM64_MODE_MASK;
    if (mode == UNWIND_ARM64_MODE_FRAMELESS) {
        uint32_t size = ((enc & UNWIND_ARM64_FRAMELESS_STACK_SIZE_MASK) >> 12) * 16;
        put_u8(b, DW_CFA_def_cfa); put_uleb(b, DW_REG_SP); put_uleb(b, size);
        return 1;
    }
    if (mode != UNWIND_ARM64_MODE_FRAME) return 0;

    put_u8(b, DW_CFA_def_cfa); put_uleb(b, DW_REG_FP); put_uleb(b, 16);
    put_u8(b, DW_CFA_offset | DW_REG_LR); put_uleb(b, 1);   /* lr at CFA-8  */
    put_u8(b, DW_CFA_offset | DW_REG_FP); put_uleb(b, 2);   /* fp at CFA-16 */

    /* Saved pairs below fp, in the order libunwind pops them: x19 at fp-8 (= CFA-24), x20 at
     * fp-16, ... then d8, d9, ... The factored offset is (distance from CFA) / 8. */
    uint64_t slot = 3;
    static const struct { uint32_t bit; unsigned first; } gpr_pairs[] = {
        { UNWIND_ARM64_FRAME_X19_X20_PAIR, 19 }, { UNWIND_ARM64_FRAME_X21_X22_PAIR, 21 },
        { UNWIND_ARM64_FRAME_X23_X24_PAIR, 23 }, { UNWIND_ARM64_FRAME_X25_X26_PAIR, 25 },
        { UNWIND_ARM64_FRAME_X27_X28_PAIR, 27 },
    };
    for (size_t i = 0; i < sizeof(gpr_pairs) / sizeof(gpr_pairs[0]); i++) {
        if (!(enc & gpr_pairs[i].bit)) continue;
        put_u8(b, DW_CFA_offset | gpr_pairs[i].first);     put_uleb(b, slot++);
        put_u8(b, DW_CFA_offset | (gpr_pairs[i].first + 1)); put_uleb(b, slot++);
    }
    static const struct { uint32_t bit; unsigned first; } fpr_pairs[] = {
        { UNWIND_ARM64_FRAME_D8_D9_PAIR, 8 }, { UNWIND_ARM64_FRAME_D10_D11_PAIR, 10 },
        { UNWIND_ARM64_FRAME_D12_D13_PAIR, 12 }, { UNWIND_ARM64_FRAME_D14_D15_PAIR, 14 },
    };
    for (size_t i = 0; i < sizeof(fpr_pairs) / sizeof(fpr_pairs[0]); i++) {
        if (!(enc & fpr_pairs[i].bit)) continue;
        put_u8(b, DW_CFA_offset_extended); put_uleb(b, DW_REG_V0 + fpr_pairs[i].first);     put_uleb(b, slot++);
        put_u8(b, DW_CFA_offset_extended); put_uleb(b, DW_REG_V0 + fpr_pairs[i].first + 1); put_uleb(b, slot++);
    }
    return 1;
}

static void emit_fde(bytes_t* b, size_t cie_off, uint64_t pc, uint64_t len, uint64_t lsda,
                     int has_personality, uint32_t enc) {
    size_t start = b->len;
    put_u32(b, 0);                                  /* length, patched */
    put_u32(b, (uint32_t)(b->len - cie_off));       /* CIE pointer: distance back to the CIE */
    put_u64(b, pc);
    put_u64(b, len);
    if (has_personality) { put_uleb(b, 8); put_u64(b, lsda); } else put_uleb(b, 0);
    emit_rules(b, enc);
    while ((b->len - start) % 8) put_u8(b, DW_CFA_nop);
    uint32_t length = (uint32_t)(b->len - start - 4);
    if (!b->oom) memcpy(b->data + start, &length, 4);
}

/* --- the walk over __unwind_info ----------------------------------------------------------- */

/* One function's worth of unwind facts, collected first and emitted grouped by CIE (below). */
typedef struct {
    uint32_t func, len, enc;
    uint64_t lsda;
    uint8_t pidx;
} uw_rec_t;

typedef struct {
    const uint8_t* sect;
    uint32_t sect_size;
    const uint8_t* base;             /* mapped image, function offsets are relative to it */
    size_t image_size;
    const uint32_t* common;
    uint32_t ncommon;
    void* personality[4];            /* [0] unused: encodings count from 1 */
    uw_rec_t* recs;
    uint32_t nrecs, cap;
    bytes_t out;
    uint32_t nfde, nlsda;
    char err[160];
} uw_ctx_t;

static const void* uw_at(uw_ctx_t* c, uint32_t off, size_t need) {
    if ((uint64_t)off + need > c->sect_size) return NULL;
    return c->sect + off;
}

static int fail(uw_ctx_t* c, const char* what) {
    snprintf(c->err, sizeof(c->err), "%s", what);
    return 0;
}

/* LSDA for a function, from the sorted lsda index array of its first-level entry. */
static uint64_t uw_find_lsda(uw_ctx_t* c, const unwind_info_section_header_lsda_index_entry_t* l,
                             uint32_t count, uint32_t func) {
    uint32_t lo = 0, hi = count;
    while (lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        if (l[mid].functionOffset < func) lo = mid + 1;
        else if (l[mid].functionOffset > func) hi = mid;
        else return l[mid].lsdaOffset;
    }
    return 0;
}

static int uw_emit_function(uw_ctx_t* c, uint32_t func, uint32_t next_func, uint32_t enc,
                            const unwind_info_section_header_lsda_index_entry_t* lsdas, uint32_t nlsda) {
    if (next_func <= func) return fail(c, "function offsets not increasing");
    if ((uint64_t)next_func > c->image_size) return fail(c, "function past the image");
    uint32_t mode = enc & UNWIND_ARM64_MODE_MASK;
    if (mode == UNWIND_ARM64_MODE_DWARF) return fail(c, "compact unwind defers to DWARF (no __eh_frame here)");
    if (mode != UNWIND_ARM64_MODE_FRAME && mode != UNWIND_ARM64_MODE_FRAMELESS) {
        snprintf(c->err, sizeof(c->err), "unknown compact unwind mode 0x%08x", enc);
        return 0;
    }
    uint32_t pidx = (enc & UNWIND_PERSONALITY_MASK) >> UNWIND_PERSONALITY_SHIFT;
    uint64_t lsda = 0;
    if (enc & UNWIND_HAS_LSDA) {
        uint32_t off = uw_find_lsda(c, lsdas, nlsda, func);
        if (!off || off >= c->image_size) return fail(c, "LSDA missing for a function that declares one");
        lsda = (uint64_t)(uintptr_t)(c->base + off);
        c->nlsda++;
    }
    if (pidx && !c->personality[pidx]) return fail(c, "personality index without a personality");
    if (lsda && !pidx) return fail(c, "LSDA without a personality");

    if (c->nrecs == c->cap) {
        uint32_t cap = c->cap ? c->cap * 2 : 256;
        uw_rec_t* r = realloc(c->recs, (size_t)cap * sizeof(*r));
        if (!r) return fail(c, "out of memory collecting unwind entries");
        c->recs = r;
        c->cap = cap;
    }
    uw_rec_t* r = &c->recs[c->nrecs++];
    r->func = func;
    r->len = next_func - func;
    r->enc = enc;
    r->lsda = lsda;
    r->pidx = (uint8_t)pidx;
    return 1;
}

/*
 * The .eh_frame layout: each CIE immediately followed by every FDE that uses it, never another
 * CIE in between. libunwind's section registration walks the section in order and decodes each
 * FDE against the most recent CIE it has parsed, not the one the FDE points at - seen on the
 * phone 2026-09-12: with a personality-less CIE emitted mid-stream, every handler-carrying
 * function after it lost its catch clauses. Functions without a personality join the first
 * personality's group with an empty LSDA, which the personality answers with "keep unwinding",
 * so a dylib with one personality - all of the game's - gets exactly one CIE.
 */
static void uw_emit_all(uw_ctx_t* c) {
    int any_personality = 0;
    for (int p = 1; p <= 3; p++) if (c->personality[p]) any_personality = 1;

    if (!any_personality) {
        size_t cie = emit_cie(&c->out, NULL);
        for (uint32_t i = 0; i < c->nrecs; i++) {
            uw_rec_t* r = &c->recs[i];
            emit_fde(&c->out, cie, (uint64_t)(uintptr_t)(c->base + r->func), r->len, 0, 0, r->enc);
            c->nfde++;
        }
        return;
    }
    int first = 0;
    for (int p = 1; p <= 3 && !first; p++) if (c->personality[p]) first = p;
    for (int p = 1; p <= 3; p++) {
        if (!c->personality[p]) continue;
        size_t cie = emit_cie(&c->out, c->personality[p]);
        for (uint32_t i = 0; i < c->nrecs; i++) {
            uw_rec_t* r = &c->recs[i];
            int group = r->pidx ? r->pidx : first;
            if (group != p) continue;
            emit_fde(&c->out, cie, (uint64_t)(uintptr_t)(c->base + r->func), r->len, r->lsda, 1, r->enc);
            c->nfde++;
        }
    }
}

/* A regular page: absolute function offsets, one encoding per entry. */
static int uw_walk_regular(uw_ctx_t* c, uint32_t page_off, uint32_t page_end_func,
                           const unwind_info_section_header_lsda_index_entry_t* lsdas, uint32_t nlsda) {
    const unwind_info_regular_second_level_page_header_t* h = uw_at(c, page_off, sizeof(*h));
    if (!h) return fail(c, "truncated regular page");
    const unwind_info_regular_second_level_entry_t* e =
            uw_at(c, page_off + h->entryPageOffset, (size_t)h->entryCount * sizeof(*e));
    if (!e) return fail(c, "regular page entries outside the section");
    for (uint32_t i = 0; i < h->entryCount; i++) {
        uint32_t next = (i + 1 < h->entryCount) ? e[i + 1].functionOffset : page_end_func;
        if (!uw_emit_function(c, e[i].functionOffset, next, e[i].encoding, lsdas, nlsda)) return 0;
    }
    return 1;
}

/* A compressed page: 24-bit function offsets relative to the first-level entry, and an 8-bit
 * encoding index into the common array first, the page's own array after it. */
static int uw_walk_compressed(uw_ctx_t* c, uint32_t page_off, uint32_t base_func, uint32_t page_end_func,
                              const unwind_info_section_header_lsda_index_entry_t* lsdas, uint32_t nlsda) {
    const unwind_info_compressed_second_level_page_header_t* h = uw_at(c, page_off, sizeof(*h));
    const uint32_t* e = uw_at(c, page_off + h->entryPageOffset, (size_t)h->entryCount * 4);
    const uint32_t* penc = uw_at(c, page_off + h->encodingsPageOffset, (size_t)h->encodingsCount * 4);
    for (uint32_t i = 0; i < h->entryCount; i++) {
        uint32_t func = base_func + (e[i] & 0x00FFFFFFu);
        uint32_t idx = e[i] >> 24;
        uint32_t enc;
        if (idx < c->ncommon) enc = c->common[idx];
        else if (idx - c->ncommon < h->encodingsCount) enc = penc[idx - c->ncommon];
        else return fail(c, "compressed entry encoding index out of range");
        uint32_t next = (i + 1 < h->entryCount) ? base_func + (e[i + 1] & 0x00FFFFFFu) : page_end_func;
        if (!uw_emit_function(c, func, next, enc, lsdas, nlsda)) return 0;
    }
    return 1;
}

const char* macho_unwind_translate(const uint8_t* base, size_t image_size,
                                   const uint8_t* unwind_info, uint32_t unwind_info_size,
                                   void* expected_personality,
                                   const void** eh_frame_out, uint32_t* fde_count, uint32_t* lsda_count) {
    static char err[200];
    uw_ctx_t c;
    memset(&c, 0, sizeof(c));
    c.sect = unwind_info;
    c.sect_size = unwind_info_size;
    c.base = base;
    c.image_size = image_size;

    const unwind_info_section_header_t* h = uw_at(&c, 0, sizeof(*h));
    if (!h) return "unwind info too small";
    if (h->version != 1) { snprintf(err, sizeof(err), "unwind info version %u", h->version); return err; }
    c.common = uw_at(&c, h->commonEncodingsArraySectionOffset, (size_t)h->commonEncodingsArrayCount * 4);
    if (!c.common && h->commonEncodingsArrayCount) return "common encodings outside the section";
    c.ncommon = h->commonEncodingsArrayCount;

    /* Personalities are image offsets of GOT slots; the slot holds the bound function pointer,
     * which after our binding must be the EH runtime's __gxx_personality_v0. */
    if (h->personalityArrayCount > 3) return "more than three personalities";
    const uint32_t* pers = uw_at(&c, h->personalityArraySectionOffset, (size_t)h->personalityArrayCount * 4);
    if (!pers && h->personalityArrayCount) return "personality array outside the section";
    for (uint32_t i = 0; i < h->personalityArrayCount; i++) {
        if ((uint64_t)pers[i] + 8 > image_size) return "personality slot outside the image";
        void* p = *(void* const*)(base + pers[i]);
        if (p != expected_personality) {
            snprintf(err, sizeof(err), "personality %u is not the EH runtime's (slot %p holds %p)", i + 1,
                     (void*)(base + pers[i]), p);
            return err;
        }
        c.personality[i + 1] = p;
    }

    const unwind_info_section_header_index_entry_t* idx =
            uw_at(&c, h->indexSectionOffset, (size_t)h->indexCount * sizeof(*idx));
    if (!idx || h->indexCount < 2) return "first-level index missing or too short";

    for (uint32_t i = 0; i + 1 < h->indexCount; i++) {
        if (idx[i].secondLevelPagesSectionOffset == 0) break;   /* sentinel reached early */
        uint32_t lsda_off = idx[i].lsdaIndexArraySectionOffset;
        uint32_t lsda_end = idx[i + 1].lsdaIndexArraySectionOffset;
        if (lsda_end < lsda_off) return "LSDA index array runs backwards";
        uint32_t nlsda = (lsda_end - lsda_off) / (uint32_t)sizeof(unwind_info_section_header_lsda_index_entry_t);
        const unwind_info_section_header_lsda_index_entry_t* lsdas = uw_at(&c, lsda_off, (size_t)nlsda * sizeof(*lsdas));
        if (!lsdas && nlsda) return "LSDA index array outside the section";

        uint32_t page_off = idx[i].secondLevelPagesSectionOffset;
        uint32_t page_end_func = idx[i + 1].functionOffset;
        const uint32_t* kind = uw_at(&c, page_off, 4);
        if (!kind) return "second-level page outside the section";
        int ok;
        if (*kind == UNWIND_SECOND_LEVEL_COMPRESSED) {
            const unwind_info_compressed_second_level_page_header_t* ch = uw_at(&c, page_off, sizeof(*ch));
            if (!ch || !uw_at(&c, page_off + ch->entryPageOffset, (size_t)ch->entryCount * 4)
                || !uw_at(&c, page_off + ch->encodingsPageOffset, (size_t)ch->encodingsCount * 4))
                return "compressed page tables outside the section";
            ok = uw_walk_compressed(&c, page_off, idx[i].functionOffset, page_end_func, lsdas, nlsda);
        } else if (*kind == UNWIND_SECOND_LEVEL_REGULAR) {
            ok = uw_walk_regular(&c, page_off, page_end_func, lsdas, nlsda);
        } else {
            snprintf(err, sizeof(err), "unknown second-level page kind %u", *kind);
            free(c.recs);
            return err;
        }
        if (!ok) { snprintf(err, sizeof(err), "%s", c.err); free(c.recs); return err; }
    }

    uw_emit_all(&c);
    free(c.recs);
    put_u32(&c.out, 0);   /* section terminator */
    if (c.out.oom) { free(c.out.data); return "out of memory building the unwind tables"; }
    if (!c.nfde) { free(c.out.data); return "no functions in the unwind info"; }

    *eh_frame_out = c.out.data;
    *fde_count = c.nfde;
    *lsda_count = c.nlsda;
    return NULL;
}
