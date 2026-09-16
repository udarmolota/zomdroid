#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* h = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!h) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }
    const char* names[] = {"JNI_OnLoad", "Java_fmod_javafmodJNI_FMOD_1System_1Create", "globalSystem", "_ZN4FMOD6System9setOutputE15FMOD_OUTPUTTYPE"};
    int failed = 0;
    for (int i = 0; i < 4; i++) {
        void* p = dlsym(h, names[i]);
        Dl_info info = {0};
        if (p) dladdr(p, &info);
        printf("%s = %p owner=%s\n", names[i], p, info.dli_fname ? info.dli_fname : "none");
        if (i > 0 && !p) failed++;
    }
    /* Do not call JNI or initialize audio without ART/HotSpot. */
    return failed ? 1 : 0;
}
