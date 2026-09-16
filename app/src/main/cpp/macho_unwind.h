#ifndef ZOMDROID_MACHO_UNWIND_H
#define ZOMDROID_MACHO_UNWIND_H
/*
 * Compact unwind (Apple __unwind_info) -> DWARF .eh_frame, for the EH runtime in
 * libzomdroid_macho_eh.so. See macho_unwind.c for the why and the limits.
 */
#include <stddef.h>
#include <stdint.h>

/*
 * base / image_size: the mapped dylib. unwind_info / unwind_info_size: its __unwind_info section
 * (inside that mapping). expected_personality: the address every personality slot must hold,
 * i.e. the EH runtime's __gxx_personality_v0 after binding. On success *eh_frame_out receives a
 * malloc'ed, terminated .eh_frame the caller must keep alive forever, and the counts say what
 * went into it. Returns NULL on success or a short reason; nothing is allocated on failure.
 */
const char* macho_unwind_translate(const uint8_t* base, size_t image_size,
                                   const uint8_t* unwind_info, uint32_t unwind_info_size,
                                   void* expected_personality,
                                   const void** eh_frame_out, uint32_t* fde_count, uint32_t* lsda_count);

#endif /* ZOMDROID_MACHO_UNWIND_H */
