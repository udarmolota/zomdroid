#ifndef ZOMDROID_EMULATION_H
#define ZOMDROID_EMULATION_H

#include "box64/src/include/box64context.h"
#include <stdbool.h>

typedef struct  {
    const char* name;
    library_t* handle;
    void** mapped_pages;
    int page_count;
    int* page_code_size;
    bool is_emulated;
} EmulatedLib;

int zomdroid_emulation_init();
void* zomdroid_emulation_bridge_jni_symbol(EmulatedLib *lib, uint64_t fn, const char* arg_types, char ret_type);
/* Copies code_size bytes of AArch64 code into an executable page owned by lib and returns the
 * address to call. Shared by the box64 trampolines and the Mach-O bridges (macho_bridge.c). */
void* zomdroid_emulation_install_code(EmulatedLib* lib, const uint32_t* code, int code_size);

#endif //ZOMDROID_EMULATION_H
