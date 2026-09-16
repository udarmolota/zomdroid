#include "macho_pathfind.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ctype.h>

/* Called before the game JVM reads options, only after successful cached Mach-O loading. */
int macho_pathfind_enable_option(const char* path) {
    FILE* input = fopen(path, "r");
    if (!input) return 0;
    char* temporary = malloc(strlen(path) + 32);
    if (!temporary) { fclose(input); return 0; }
    sprintf(temporary, "%s.macho-XXXXXX", path);
    int fd = mkstemp(temporary);
    FILE* output = fd < 0 ? NULL : fdopen(fd, "w");
    if (!output) { if (fd >= 0) { close(fd); unlink(temporary); } free(temporary); fclose(input); return 0; }
    char* line = NULL;
    size_t capacity = 0;
    int found = 0, ok = 1;
    while (getline(&line, &capacity, input) >= 0) {
        char* start = line;
        while (*start && isspace((unsigned char)*start)) ++start;
        const char* key = "Pathfind.UseNativeCode";
        size_t n = strlen(key);
        int match = strncmp(start, key, n) == 0;
        if (match) { start += n; while (*start && isspace((unsigned char)*start)) ++start; match = *start == '='; }
        if (match) { found = 1; if (fputs("Pathfind.UseNativeCode=true\n", output) < 0) ok = 0; }
        else if (fputs(line, output) < 0) ok = 0;
    }
    if (ferror(input)) ok = 0;
    free(line);
    fclose(input);
    if (fflush(output) || fsync(fd)) ok = 0;
    if (fclose(output)) ok = 0;
    if (!found) ok = 0; /* Java must have prepared the option first. */
    if (ok && rename(temporary, path)) ok = 0;
    if (!ok) unlink(temporary);
    free(temporary);
    return ok;
}
