#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
/* Darwin arm64 alternate libc++ string: 22 inline chars + NUL + size/flag byte.
 * Long: pointer, size, allocation-count | bit63. No Android std::string crosses this API. */
typedef struct { uint64_t words[3]; } macho_string;
size_t macho_string_size(const macho_string* value);
const char* macho_string_data(const macho_string* value);
void macho_string_destroy(macho_string* value);
macho_string* macho_string_assign(macho_string* value, const char* text);
macho_string* macho_string_copy(macho_string* value, const macho_string* source);
macho_string* macho_string_append_n(macho_string* value, const char* text, size_t count);
macho_string* macho_string_append(macho_string* value, const char* text);
macho_string* macho_string_insert(macho_string* value, size_t position, const char* text);
void macho_string_resize(macho_string* value, size_t count, char fill);
void macho_string_push_back(macho_string* value, char ch);
macho_string macho_string_concat(const char* prefix, const macho_string* suffix);
macho_string macho_string_from_int(int value);
/* Input is the ELF spelling: exactly one Mach-O underscore must already be removed. */
void* macho_string_import(const char* name);
#ifdef __cplusplus
}
#endif
