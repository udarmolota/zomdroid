#include "macho_string.h"
#include <cassert>
#include <cstring>
#include <climits>
#include <string>
#include <stdexcept>
#include <system_error>
#include <cstdio>
#include <new>

static void check(const macho_string& s, const std::string& expected) {
    assert(macho_string_size(&s) == expected.size());
    assert(std::memcmp(macho_string_data(&s), expected.data(), expected.size()) == 0);
    assert(macho_string_data(&s)[expected.size()] == 0);
    if (expected.size() <= 22) {
        assert((s.words[2] >> 63) == 0);
        assert(reinterpret_cast<const unsigned char*>(&s)[23] == expected.size());
    } else {
        assert((s.words[2] >> 63) == 1);
        assert((s.words[2] & ~(UINT64_C(1) << 63)) > expected.size());
    }
}
int main() {
    // Independent raw Apple short-string image (not built by the shim).
    macho_string fixture{};
    std::memcpy(&fixture, "apple", 5);
    reinterpret_cast<unsigned char*>(&fixture)[23] = 5;
    check(fixture, "apple");
    macho_string_append(&fixture, macho_string_data(&fixture));
    check(fixture, "appleapple");
    macho_string_destroy(&fixture);

    // An externally allocated long image must be consumable and releasable by the shim.
    char* external = static_cast<char*>(::operator new(48));
    std::memset(external, 'a', 30); external[30] = 0;
    fixture.words[0] = reinterpret_cast<uintptr_t>(external);
    fixture.words[1] = 30; fixture.words[2] = (UINT64_C(1) << 63) | 48;
    check(fixture, std::string(30, 'a'));
    macho_string_assign(&fixture, external + 20);
    check(fixture, std::string(10, 'a'));
    macho_string_destroy(&fixture);

    macho_string s{};
    std::string expected;
    for (int i = 0; i < 1000; ++i) {
        char ch = static_cast<char>(i % 127);
        macho_string_push_back(&s, ch); expected.push_back(ch); check(s, expected);
        if (i % 11 == 0) {
            macho_string_append_n(&s, macho_string_data(&s), macho_string_size(&s));
            expected += expected; check(s, expected);
        }
        if (i % 7 == 0) {
            size_t size = i % 60;
            macho_string_resize(&s, size, 'z'); expected.resize(size, 'z'); check(s, expected);
        }
    }
    macho_string_assign(&s, "0123456789012345678901"); check(s, std::string("0123456789012345678901"));
    macho_string_push_back(&s, '2'); check(s, "01234567890123456789012");
    macho_string_insert(&s, 4, macho_string_data(&s) + 20); check(s, "01230124567890123456789012");
    std::string before(macho_string_data(&s), macho_string_size(&s));
    try { macho_string_insert(&s, 10000, "x"); assert(false); } catch (const std::out_of_range&) {}
    check(s, before);
    try { macho_string_resize(&s, SIZE_MAX, 0); assert(false); } catch (const std::length_error&) {}
    check(s, before);
    macho_string_copy(&s, &s); check(s, before);
    macho_string other{}; macho_string_copy(&other, &s); check(other, before);
    macho_string combined = macho_string_concat("prefix:", &s); check(combined, "prefix:" + before);
    macho_string_destroy(&combined); macho_string_destroy(&other); macho_string_destroy(&s);
    for (int n : {0, 1, -1, INT_MIN, INT_MAX}) {
        s = macho_string_from_int(n); check(s, std::to_string(n)); macho_string_destroy(&s);
    }
    assert(macho_string_import("_ZNSt3__19to_stringEi"));
    assert(!macho_string_import("_ZNSt3__1unknown"));
    auto ctor = reinterpret_cast<void(*)(void*, const macho_string*)>(macho_string_import(
        "_ZNSt3__113random_deviceC1ERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE"));
    auto next = reinterpret_cast<unsigned(*)(void*)>(macho_string_import("_ZNSt3__113random_deviceclEv"));
    unsigned char random_object[16]; std::memset(random_object, 0xa5, sizeof(random_object));
    macho_string_assign(&s, "/dev/urandom"); ctor(random_object, &s);
    for (int i = 0; i < 64; ++i) (void)next(random_object);
    for (unsigned char c : random_object) assert(c == 0xa5);
    macho_string_assign(&s, "bad token");
    try { ctor(random_object, &s); assert(false); } catch (const std::system_error&) {}
    macho_string_destroy(&s);
    auto error_ctor = reinterpret_cast<void(*)(void*, const macho_string*)>(macho_string_import(
        "_ZNSt13runtime_errorC2ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE"));
    alignas(std::runtime_error) unsigned char exception_storage[sizeof(std::runtime_error)];
    macho_string_assign(&s, "an Apple string converted at the exception boundary");
    error_ctor(exception_storage, &s);
    auto* error = reinterpret_cast<std::runtime_error*>(exception_storage);
    assert(std::strcmp(error->what(), macho_string_data(&s)) == 0);
    error->~runtime_error();
    macho_string_destroy(&s);
    std::puts("PASS: Apple string fixtures, 1000 differential mutations, aliases, boundaries, exceptions, random_device");
}
