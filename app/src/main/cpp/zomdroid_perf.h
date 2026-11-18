#ifndef ZOMDROID_PERF_H
#define ZOMDROID_PERF_H

/** Called by glfwSwapBuffers after every presented frame. Cheap: safe on the render thread. */
void zomdroid_perf_frame(void);

typedef void (*perf_emit_fn)(const char* line);

/** Called every 10 s by the stdio monitor thread; writes [ZPERF] and [ZSPIKE] lines via emit. */
void zomdroid_perf_sample(perf_emit_fn emit);

#endif // ZOMDROID_PERF_H
