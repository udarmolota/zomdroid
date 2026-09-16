#include "macho_loader.h"
#include <stdio.h>
#include <dlfcn.h>
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    macho_lib_t* lib = macho_load(argv[1]);
    if (!lib) { puts("FAIL: constructor harness rejected PopMan"); return 1; }
    if (!macho_dlsym(lib, "Java_zombie_popman_ZombiePopulationManager_n_1saveCell")) {
        puts("FAIL: n_saveCell not exported"); return 1;
    }
    puts("PASS: PopMan constructors returned and n_saveCell resolved; no world or save operation attempted");
    const char* names[] = { "__register_frame", "__deregister_frame", "_Unwind_RaiseException", "__gxx_personality_v0" };
    void* runtime = dlopen("libc++_shared.so", RTLD_NOW);
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i)
        printf("UNWIND PROBE %s: libc++=%p default=%p\n", names[i],
               runtime ? dlsym(runtime, names[i]) : NULL, dlsym(RTLD_DEFAULT, names[i]));
    return 0;
}
