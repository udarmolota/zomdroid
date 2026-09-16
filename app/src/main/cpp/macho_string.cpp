#include "macho_string.h"
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <new>
#include <stdexcept>
#include <system_error>
#include <limits>

static_assert(sizeof(void*) == 8 && sizeof(size_t) == 8, "Only 64-bit ABI supported");
static_assert(sizeof(macho_string) == 24 && alignof(macho_string) == 8, "Darwin string ABI");
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error Darwin string shims require little endian
#endif

static constexpr uint64_t long_flag = UINT64_C(1) << 63;
static constexpr size_t short_capacity = 22;
static bool is_long(const macho_string* s) { return (s->words[2] & long_flag) != 0; }
static char* data(macho_string* s) {
    return is_long(s) ? reinterpret_cast<char*>(static_cast<uintptr_t>(s->words[0]))
                      : reinterpret_cast<char*>(s);
}
extern "C" size_t macho_string_size(const macho_string* s) {
    return is_long(s) ? s->words[1] : reinterpret_cast<const unsigned char*>(s)[23];
}
extern "C" const char* macho_string_data(const macho_string* s) {
    return data(const_cast<macho_string*>(s));
}
static size_t checked_add(size_t a, size_t b) {
    constexpr size_t limit = (std::numeric_limits<size_t>::max() >> 1) - 32;
    if (a > limit || b > limit - a) throw std::length_error("Darwin string too large");
    return a + b;
}
static macho_string allocate(size_t size) {
    checked_add(size, 0);
    macho_string result{};
    if (size <= short_capacity) {
        reinterpret_cast<unsigned char*>(&result)[23] = static_cast<unsigned char>(size);
    } else {
        // Count includes the terminator. Match libc++'s 16-byte capacity rounding.
        size_t count = (size + 16) & ~size_t(15);
        result.words[0] = reinterpret_cast<uintptr_t>(::operator new(count));
        result.words[1] = size;
        result.words[2] = long_flag | count;
    }
    data(&result)[size] = '\0';
    return result;
}
extern "C" void macho_string_destroy(macho_string* s) {
    if (is_long(s)) ::operator delete(data(s));
    std::memset(s, 0, sizeof(*s));
}
static void commit(macho_string* target, macho_string value) {
    macho_string_destroy(target);
    *target = value;
}
// Construct before destroying the original: preserves aliases (s += s.data()) and the strong
// exception guarantee. Deliberately prioritize correctness over capacity reuse in this first shim.
extern "C" macho_string* macho_string_append_n(macho_string* s, const char* text, size_t count) {
    const size_t old = macho_string_size(s);
    macho_string result = allocate(checked_add(old, count));
    if (old) std::memcpy(data(&result), data(s), old);
    if (count) std::memcpy(data(&result) + old, text, count);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_append(macho_string* s, const char* text) {
    return macho_string_append_n(s, text, std::strlen(text));
}
static macho_string* assign_n(macho_string* s, const char* text, size_t count) {
    macho_string result = allocate(count);
    if (count) std::memcpy(data(&result), text, count);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_assign(macho_string* s, const char* text) {
    return assign_n(s, text, std::strlen(text));
}
extern "C" macho_string* macho_string_copy(macho_string* s, const macho_string* source) {
    if (s != source) assign_n(s, macho_string_data(source), macho_string_size(source));
    return s;
}
extern "C" macho_string* macho_string_insert(macho_string* s, size_t position, const char* text) {
    const size_t old = macho_string_size(s);
    if (position > old) throw std::out_of_range("Darwin string insert");
    const size_t count = std::strlen(text);
    macho_string result = allocate(checked_add(old, count));
    if (position) std::memcpy(data(&result), data(s), position);
    if (count) std::memcpy(data(&result) + position, text, count);
    if (old != position) std::memcpy(data(&result) + position + count, data(s) + position, old - position);
    commit(s, result);
    return s;
}
extern "C" void macho_string_resize(macho_string* s, size_t count, char fill) {
    const size_t old = macho_string_size(s), copy = old < count ? old : count;
    macho_string result = allocate(count);
    if (copy) std::memcpy(data(&result), data(s), copy);
    if (count > copy) std::memset(data(&result) + copy, static_cast<unsigned char>(fill), count - copy);
    commit(s, result);
}
extern "C" void macho_string_push_back(macho_string* s, char ch) { macho_string_append_n(s, &ch, 1); }
extern "C" macho_string macho_string_concat(const char* prefix, const macho_string* suffix) {
    size_t a = std::strlen(prefix), b = macho_string_size(suffix);
    macho_string result = allocate(checked_add(a, b));
    if (a) std::memcpy(data(&result), prefix, a);
    if (b) std::memcpy(data(&result) + a, macho_string_data(suffix), b);
    return result;
}
extern "C" macho_string macho_string_from_int(int value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%d", value);
    macho_string result{};
    return *macho_string_assign(&result, buffer);
}
static void random_construct(void*, const macho_string* token) {
    // Darwin's arc4random implementation owns no file descriptor. Never write an Android
    // random_device into the (potentially one-byte) Apple object.
    if (macho_string_size(token) != 12 || std::memcmp(macho_string_data(token), "/dev/urandom", 12))
        throw std::system_error(std::make_error_code(std::errc::no_such_file_or_directory), "random_device");
}
static void random_destroy(void*) {}
static unsigned random_next(void*) {
    unsigned result;
    arc4random_buf(&result, sizeof(result));
    return result;
}
static void runtime_error_string(void* self, const macho_string* text) {
    // Use the Android char* constructor, never pass Apple string storage into Android libc++.
    new (self) std::runtime_error(macho_string_data(text));
}

extern "C" void* macho_string_import(const char* name) {
    struct binding { const char* name; void* address; };
#define B(s, f) {s, reinterpret_cast<void*>(f)}
#define S "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
    static const binding imports[] = {
        B(S "6appendEPKc", macho_string_append), B(S "6appendEPKcm", macho_string_append_n),
        B(S "6assignEPKc", macho_string_assign), B(S "6insertEmPKc", macho_string_insert),
        B(S "6resizeEmc", macho_string_resize), B(S "9push_backEc", macho_string_push_back),
        B(S "aSERKS5_", macho_string_copy), B(S "D1Ev", macho_string_destroy), B(S "D2Ev", macho_string_destroy),
        B("_ZNSt3__19to_stringEi", macho_string_from_int),
        B("_ZNSt3__1plIcNS_11char_traitsIcEENS_9allocatorIcEEEENS_12basic_stringIT_T0_T1_EEPKS6_RKS9_", macho_string_concat),
        B("_ZNSt3__113random_deviceC1ERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE", random_construct),
        B("_ZNSt3__113random_deviceD1Ev", random_destroy), B("_ZNSt3__113random_deviceclEv", random_next),
        B("_ZNSt13runtime_errorC2ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE", runtime_error_string),
    };
#undef S
#undef B
    for (const auto& entry : imports) if (std::strcmp(name, entry.name) == 0) return entry.address;
    return nullptr;
}
