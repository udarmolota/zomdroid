// Performance telemetry for bug reports: where a weak phone loses its frames.
//
// Every presented frame (glfwSwapBuffers) is timed into a histogram; every 10 s the stdio monitor
// thread turns the histogram into one [ZPERF] line in native.log, together with per-thread CPU
// and the core each thread last ran on, swap traffic, PSS, thermal status and the CPU cluster
// frequencies. A single frame of 250 ms or more gets its own [ZSPIKE] line with the wall-clock
// time, so a stall can be matched with what the game log says happened at that moment (the world
// map opening, a horde, a save).
//
// Cost on the render thread: one clock read and two relaxed atomic adds per frame. All /proc and
// /sys reading happens on the monitor thread, once per 10 s.
//
// The same data drives an ADPF performance hint session (Android 13+): the render thread and the
// game's busiest threads are put in a session whose target is one display refresh, and every
// frame's duration is reported to it. A late frame makes Android boost those threads and keep them
// on the big cores, instead of letting the scheduler drift the game's main thread onto a little
// core, which is where the 10-20 ms frames between the 4 ms ones came from (S25, 2026-11-18).

#include <dlfcn.h>
#include <dirent.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "zomdroid_perf.h"

#define BUCKETS 256            // 1 ms buckets; the last one holds everything from 255 ms up
#define SPIKE_MS 250
#define MAX_THREADS 512
#define MAX_CPUS 16
#define TOP_THREADS 6

static atomic_uint g_hist[BUCKETS];
static atomic_uint g_max_ms;
static atomic_ullong g_last_frame_ns;
static atomic_uint g_spikes_pending;   // spikes seen since the last sample
static atomic_uint g_spike_ms[8];      // their lengths, for the monitor thread to print
static atomic_llong g_spike_at[8];     // their wall-clock times (s)

static long long now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// ---- ADPF (APerformanceHint, libandroid, API 33+; setThreads is API 34+) ------------------------
// Looked up with dlsym like the thermal calls, so the app keeps running on Android 11-12.
static void* (*p_hint_manager)(void);
static void* (*p_hint_create)(void*, const int32_t*, size_t, int64_t);
static int (*p_hint_report)(void*, int64_t);
static int (*p_hint_set_threads)(void*, const int32_t*, size_t);
static void* g_hint_session;            // written under g_hint_lock, read under it too
static pthread_mutex_t g_hint_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_render_tid;
#define HINT_MAX_TIDS 5
static int32_t g_hint_tids[HINT_MAX_TIDS];
static int g_hint_ntids;

static void hint_report(long long frame_ns) {
    // The session is not thread-safe and the monitor thread may be changing its threads: skip the
    // report for this frame rather than make the render thread wait.
    if (pthread_mutex_trylock(&g_hint_lock) != 0) return;
    if (g_hint_session) p_hint_report(g_hint_session, frame_ns);
    pthread_mutex_unlock(&g_hint_lock);
}

void zomdroid_perf_frame(void) {
    long long now = now_ns();
    if (atomic_load_explicit(&g_render_tid, memory_order_relaxed) == 0) {
        atomic_store_explicit(&g_render_tid, gettid(), memory_order_relaxed);
    }
    unsigned long long last = atomic_exchange_explicit(&g_last_frame_ns, (unsigned long long) now,
                                                       memory_order_relaxed);
    if (last == 0) return;
    long long ms = (now - (long long) last) / 1000000LL;
    if (ms < 0) return;
    // A gap of minutes is the app in the background, not a frame.
    if (ms > 60000) return;
    if (p_hint_report) hint_report(now - (long long) last);
    unsigned int b = ms >= BUCKETS - 1 ? BUCKETS - 1 : (unsigned int) ms;
    atomic_fetch_add_explicit(&g_hist[b], 1, memory_order_relaxed);
    unsigned int prev = atomic_load_explicit(&g_max_ms, memory_order_relaxed);
    while ((unsigned int) ms > prev
           && !atomic_compare_exchange_weak_explicit(&g_max_ms, &prev, (unsigned int) ms,
                                                     memory_order_relaxed, memory_order_relaxed)) {
    }
    if (ms >= SPIKE_MS) {
        unsigned int i = atomic_fetch_add_explicit(&g_spikes_pending, 1, memory_order_relaxed);
        if (i < 8) {
            atomic_store_explicit(&g_spike_ms[i], (unsigned int) ms, memory_order_relaxed);
            atomic_store_explicit(&g_spike_at[i], (long long) time(NULL), memory_order_relaxed);
        }
    }
}

// ---------------------------------------------------------------------------------------------

typedef struct {
    int tid;
    unsigned long long ticks;
    int seen;
} ThreadTicks;

static ThreadTicks g_threads[MAX_THREADS];
static int g_thread_count;
static long long g_last_sample_ns;
static long g_last_pswpin = -1, g_last_pswpout = -1;
static int g_cpu_count;
static long g_cpu_max_khz[MAX_CPUS];
static char g_cpu_class[MAX_CPUS];     // 'L', 'M' or 'B' by the cluster's top frequency
// android/thermal.h does not compile as C in NDK r27 (enum and struct used without their tags),
// so the two calls we need are looked up in libandroid instead.
static void* g_thermal;
static int (*p_get_thermal_status)(void*);

static void init_thermal(void) {
    void* lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return;
    void* (*acquire)(void) = (void* (*)(void)) dlsym(lib, "AThermal_acquireManager");
    p_get_thermal_status = (int (*)(void*)) dlsym(lib, "AThermal_getCurrentThermalStatus");
    if (acquire && p_get_thermal_status) g_thermal = acquire();
}

static long read_long_file(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

static void init_cpus(void) {
    char path[128];
    long distinct[MAX_CPUS];
    int nd = 0;
    for (int i = 0; i < MAX_CPUS; i++) {
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        long khz = read_long_file(path);
        if (khz <= 0) break;
        g_cpu_max_khz[i] = khz;
        g_cpu_count = i + 1;
        int known = 0;
        for (int k = 0; k < nd; k++) if (distinct[k] == khz) known = 1;
        if (!known) distinct[nd++] = khz;
    }
    long lo = 0, hi = 0;
    for (int k = 0; k < nd; k++) {
        if (!lo || distinct[k] < lo) lo = distinct[k];
        if (distinct[k] > hi) hi = distinct[k];
    }
    for (int i = 0; i < g_cpu_count; i++) {
        g_cpu_class[i] = g_cpu_max_khz[i] == hi ? 'B' : g_cpu_max_khz[i] == lo ? 'L' : 'M';
    }
}

static void read_vmstat(long* pswpin, long* pswpout) {
    *pswpin = *pswpout = -1;
    FILE* f = fopen("/proc/vmstat", "r");
    if (!f) return;
    char key[64];
    long v;
    while (fscanf(f, "%63s %ld", key, &v) == 2) {
        if (!strcmp(key, "pswpin")) *pswpin = v;
        else if (!strcmp(key, "pswpout")) *pswpout = v;
    }
    fclose(f);
}

static long read_pss_mb(void) {
    FILE* f = fopen("/proc/self/smaps_rollup", "r");
    if (!f) return -1;
    char line[256];
    long kb = -1;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "Pss:", 4)) {
            kb = strtol(line + 4, NULL, 10);
            break;
        }
    }
    fclose(f);
    return kb < 0 ? -1 : kb / 1024;
}

typedef struct {
    char name[20];
    double pct;
    int cpu;
    int tid;
} ThreadLoad;

// /proc/self/task/<tid>/stat: "tid (comm) state ..." - comm may hold spaces and parentheses, so
// the fields are counted from the last ')'. utime and stime are fields 14 and 15, processor 39.
static int read_thread(int tid, char* name, size_t name_len, unsigned long long* ticks, int* cpu) {
    char path[64], buf[1024];
    snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    buf[n] = 0;
    char* open = strchr(buf, '(');
    char* close = strrchr(buf, ')');
    if (!open || !close || close < open) return 0;
    size_t len = (size_t) (close - open - 1);
    if (len >= name_len) len = name_len - 1;
    memcpy(name, open + 1, len);
    name[len] = 0;
    for (char* c = name; *c; c++) if (*c == ' ') *c = '_';
    // Field 3 (state) starts two characters after ')'.
    char* p = close + 2;
    unsigned long long utime = 0, stime = 0;
    int field = 3, processor = -1;
    char* save = NULL;
    for (char* tok = strtok_r(p, " ", &save); tok; tok = strtok_r(NULL, " ", &save), field++) {
        if (field == 14) utime = strtoull(tok, NULL, 10);
        else if (field == 15) stime = strtoull(tok, NULL, 10);
        else if (field == 39) {
            processor = atoi(tok);
            break;
        }
    }
    *ticks = utime + stime;
    *cpu = processor;
    return 1;
}

static int sample_threads(double interval_s, ThreadLoad* top, int top_max) {
    long hz = sysconf(_SC_CLK_TCK);
    if (hz <= 0) hz = 100;
    for (int i = 0; i < g_thread_count; i++) g_threads[i].seen = 0;
    int ntop = 0;
    DIR* d = opendir("/proc/self/task");
    if (!d) return 0;
    struct dirent* e;
    while ((e = readdir(d))) {
        int tid = atoi(e->d_name);
        if (tid <= 0) continue;
        char name[20];
        unsigned long long ticks;
        int cpu;
        if (!read_thread(tid, name, sizeof name, &ticks, &cpu)) continue;
        ThreadTicks* t = NULL;
        for (int i = 0; i < g_thread_count; i++) if (g_threads[i].tid == tid) t = &g_threads[i];
        if (!t) {
            if (g_thread_count >= MAX_THREADS) continue;
            t = &g_threads[g_thread_count++];
            t->tid = tid;
            t->ticks = ticks;   // first sight: no delta yet
        }
        double pct = interval_s > 0 ? (double) (ticks - t->ticks) * 100.0 / ((double) hz * interval_s) : 0;
        t->ticks = ticks;
        t->seen = 1;
        if (pct < 5.0) continue;
        // Keep the top_max busiest, sorted descending: append (or replace the smallest), then
        // move the new entry up past every smaller one.
        int pos;
        if (ntop < top_max) pos = ntop++;
        else if (pct > top[top_max - 1].pct) pos = top_max - 1;
        else continue;
        snprintf(top[pos].name, sizeof top[pos].name, "%s", name);
        top[pos].pct = pct;
        top[pos].cpu = cpu;
        top[pos].tid = tid;
        while (pos > 0 && top[pos - 1].pct < top[pos].pct) {
            ThreadLoad tmp = top[pos - 1];
            top[pos - 1] = top[pos];
            top[pos] = tmp;
            pos--;
        }
    }
    closedir(d);
    // Forget threads that are gone, so a recycled tid starts over.
    int w = 0;
    for (int i = 0; i < g_thread_count; i++) if (g_threads[i].seen) g_threads[w++] = g_threads[i];
    g_thread_count = w;
    return ntop;
}

static int64_t g_hint_target_ns;
static int g_hint_off;   // 1 = turned off with ZOMDROID_ADPF=0, 2 = and already said so

static void init_hint(void) {
    // ZOMDROID_ADPF=0 in the launcher's Env vars turns the session off, so a tester can compare
    // one session with it and one without on the same phone.
    const char* adpf = getenv("ZOMDROID_ADPF");
    if (adpf && !strcmp(adpf, "0")) {
        g_hint_off = 1;
        return;
    }
    void* lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return;
    void* (*mgr)(void) = (void* (*)(void)) dlsym(lib, "APerformanceHint_getManager");
    p_hint_create = (void* (*)(void*, const int32_t*, size_t, int64_t))
            dlsym(lib, "APerformanceHint_createSession");
    p_hint_set_threads = (int (*)(void*, const int32_t*, size_t)) dlsym(lib, "APerformanceHint_setThreads");
    int (*report)(void*, int64_t) = (int (*)(void*, int64_t))
            dlsym(lib, "APerformanceHint_reportActualWorkDuration");
    if (!mgr || !p_hint_create || !report) return;
    p_hint_manager = mgr;
    // One display refresh: frames faster than the screen can show gain nothing from a boost.
    // ZOMDROID_REFRESH_HZ comes from the launcher (Display.getRefreshRate()).
    const char* hz_env = getenv("ZOMDROID_REFRESH_HZ");
    double hz = hz_env ? atof(hz_env) : 60.0;
    if (hz < 30.0 || hz > 240.0) hz = 60.0;
    g_hint_target_ns = (int64_t) (1e9 / hz);
    p_hint_report = report;
}

// Threads that are not the game's frame work: JIT compilers, GC, audio, the JVM's own.
static int hint_skips(const char* name) {
    static const char* prefixes[] = {"C1_", "C2_", "G1_", "GC", "VM_", "FMOD", "Signal", "Common-Cleaner",
                                     "Finalizer", "Reference", "Service_Thread", "Monitor_Deflat"};
    for (size_t i = 0; i < sizeof prefixes / sizeof prefixes[0]; i++) {
        if (!strncmp(name, prefixes[i], strlen(prefixes[i]))) return 1;
    }
    return 0;
}

/** Puts the render thread and the busiest game threads (40%+ of a core) in the session. */
static void update_hint(const ThreadLoad* top, int ntop, perf_emit_fn emit) {
    if (g_hint_off == 1) {
        emit("[ZPERF] adpf off (ZOMDROID_ADPF=0)");
        g_hint_off = 2;
    }
    if (!p_hint_manager) return;
    int32_t tids[HINT_MAX_TIDS];
    int n = 0;
    int render = atomic_load_explicit(&g_render_tid, memory_order_relaxed);
    if (render > 0) tids[n++] = render;
    for (int i = 0; i < ntop && n < HINT_MAX_TIDS; i++) {
        if (top[i].pct < 40.0 || hint_skips(top[i].name)) continue;
        int dup = 0;
        for (int k = 0; k < n; k++) if (tids[k] == top[i].tid) dup = 1;
        if (!dup) tids[n++] = top[i].tid;
    }
    if (n == 0) return;
    // Same set as now (in any order): nothing to do. The busiest list wobbles, so a thread that
    // drops under 40% for one window stays in until the set really changes shape.
    int same = n <= g_hint_ntids;
    for (int i = 0; same && i < n; i++) {
        int found = 0;
        for (int k = 0; k < g_hint_ntids; k++) if (g_hint_tids[k] == tids[i]) found = 1;
        if (!found) same = 0;
    }
    if (same && g_hint_session) return;

    const char* what = NULL;
    pthread_mutex_lock(&g_hint_lock);
    if (!g_hint_session) {
        void* mgr = p_hint_manager();
        if (mgr) g_hint_session = p_hint_create(mgr, tids, (size_t) n, g_hint_target_ns);
        what = g_hint_session ? "created" : "unavailable";
        if (!g_hint_session) p_hint_manager = NULL;   // do not retry every 10 s
    } else if (p_hint_set_threads) {
        what = p_hint_set_threads(g_hint_session, tids, (size_t) n) == 0 ? "threads" : NULL;
    }
    if (what && g_hint_session) {
        memcpy(g_hint_tids, tids, sizeof(int32_t) * (size_t) n);
        g_hint_ntids = n;
    }
    pthread_mutex_unlock(&g_hint_lock);

    if (what) {
        char line[256];
        int len = snprintf(line, sizeof line, "[ZPERF] adpf %s target=%lldus tids=", what,
                           (long long) (g_hint_target_ns / 1000));
        for (int i = 0; i < n && len < (int) sizeof line - 16; i++) {
            len += snprintf(line + len, sizeof line - len, "%s%d", i ? "," : "", tids[i]);
        }
        emit(line);
    }
}

static unsigned int percentile(const unsigned int* hist, unsigned long total, double q) {
    unsigned long want = (unsigned long) (q * (double) total);
    unsigned long acc = 0;
    for (unsigned int b = 0; b < BUCKETS; b++) {
        acc += hist[b];
        if (acc > want) return b;
    }
    return BUCKETS - 1;
}

void zomdroid_perf_sample(perf_emit_fn emit) {
    long long now = now_ns();
    if (g_last_sample_ns == 0) {
        // First call: set the baselines, report nothing.
        init_cpus();
        init_thermal();
        init_hint();
        read_vmstat(&g_last_pswpin, &g_last_pswpout);
        ThreadLoad dummy[TOP_THREADS];
        sample_threads(0, dummy, TOP_THREADS);
        g_last_sample_ns = now;
        return;
    }
    double interval_s = (double) (now - g_last_sample_ns) / 1e9;
    g_last_sample_ns = now;
    // Nothing to say before the first frame, nor in the dedicated server process, which never
    // presents one. The thread baselines still move on, so the first line covers 10 s only.
    if (atomic_load_explicit(&g_last_frame_ns, memory_order_relaxed) == 0) {
        ThreadLoad dummy[TOP_THREADS];
        sample_threads(interval_s, dummy, TOP_THREADS);
        read_vmstat(&g_last_pswpin, &g_last_pswpout);
        return;
    }

    char line[1024];
    int len = 0;
    time_t wall = time(NULL);
    struct tm tm;
    localtime_r(&wall, &tm);
    len += snprintf(line + len, sizeof line - len, "[ZPERF] %02d:%02d:%02d", tm.tm_hour, tm.tm_min, tm.tm_sec);

    // Frames: take the histogram and start the next window empty.
    unsigned int hist[BUCKETS];
    unsigned long frames = 0;
    for (int b = 0; b < BUCKETS; b++) {
        hist[b] = atomic_exchange_explicit(&g_hist[b], 0, memory_order_relaxed);
        frames += hist[b];
    }
    unsigned int max_ms = atomic_exchange_explicit(&g_max_ms, 0, memory_order_relaxed);
    if (frames > 0) {
        unsigned long over50 = 0, over100 = 0;
        for (int b = 51; b < BUCKETS; b++) over50 += hist[b];
        for (int b = 101; b < BUCKETS; b++) over100 += hist[b];
        len += snprintf(line + len, sizeof line - len,
                        " fps=%.1f ms p50=%u p95=%u p99=%u max=%u >50=%lu >100=%lu",
                        (double) frames / interval_s, percentile(hist, frames, 0.50),
                        percentile(hist, frames, 0.95), percentile(hist, frames, 0.99),
                        max_ms, over50, over100);
    } else {
        len += snprintf(line + len, sizeof line - len, " fps=0");
    }

    ThreadLoad top[TOP_THREADS];
    int ntop = sample_threads(interval_s, top, TOP_THREADS);
    update_hint(top, ntop, emit);
    len += snprintf(line + len, sizeof line - len, " | cpu");
    for (int i = 0; i < ntop && len < (int) sizeof line - 64; i++) {
        char cls = top[i].cpu >= 0 && top[i].cpu < g_cpu_count ? g_cpu_class[top[i].cpu] : '?';
        len += snprintf(line + len, sizeof line - len, " %s:%.0f%%@%d%c",
                        top[i].name, top[i].pct, top[i].cpu, cls);
    }

    long pswpin, pswpout;
    read_vmstat(&pswpin, &pswpout);
    long page_kb = sysconf(_SC_PAGESIZE) / 1024;
    if (pswpin >= 0 && g_last_pswpin >= 0) {
        len += snprintf(line + len, sizeof line - len, " | swapin=%ldMB swapout=%ldMB",
                        (pswpin - g_last_pswpin) * page_kb / 1024,
                        (pswpout - g_last_pswpout) * page_kb / 1024);
    }
    g_last_pswpin = pswpin;
    g_last_pswpout = pswpout;
    long pss = read_pss_mb();
    if (pss >= 0) len += snprintf(line + len, sizeof line - len, " pss=%ldMB", pss);

    if (g_thermal) {
        len += snprintf(line + len, sizeof line - len, " | therm=%d",
                        p_get_thermal_status(g_thermal));
    }
    // Current frequency of the first core of each cluster, lowest cluster first.
    len += snprintf(line + len, sizeof line - len, " mhz=");
    long shown = -1;
    int first = 1;
    for (int i = 0; i < g_cpu_count; i++) {
        if (g_cpu_max_khz[i] == shown) continue;
        shown = g_cpu_max_khz[i];
        char path[128];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", i);
        long khz = read_long_file(path);
        len += snprintf(line + len, sizeof line - len, "%s%c%ld", first ? "" : "/", g_cpu_class[i],
                        khz > 0 ? khz / 1000 : -1);
        first = 0;
    }
    emit(line);

    unsigned int spikes = atomic_exchange_explicit(&g_spikes_pending, 0, memory_order_relaxed);
    for (unsigned int i = 0; i < spikes && i < 8; i++) {
        time_t at = (time_t) atomic_load_explicit(&g_spike_at[i], memory_order_relaxed);
        struct tm st;
        localtime_r(&at, &st);
        snprintf(line, sizeof line, "[ZSPIKE] %02d:%02d:%02d frame=%ums", st.tm_hour, st.tm_min,
                 st.tm_sec, atomic_load_explicit(&g_spike_ms[i], memory_order_relaxed));
        emit(line);
    }
    if (spikes > 8) {
        snprintf(line, sizeof line, "[ZSPIKE] +%u more", spikes - 8);
        emit(line);
    }
}
