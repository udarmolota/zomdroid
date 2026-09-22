package com.zomdroid;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;

/**
 * The numbers behind the in-game performance bar (PerfOverlayView), sampled once a second on a
 * background thread.
 *
 * Ported from ValDroid 2026-09-22. The point is diagnosis from a single screenshot: the GPU near
 * 100% means GPU-bound (high render scale, heavy shaders), and a hot phone with the system reporting throttling explains a frame rate that sags
 * over time. (A per-thread "busiest thread" figure was tried and dropped from the bar on request;
 * it needed a walk over every thread's /proc entry each second.)
 *
 * Always readable by an app: its own /proc entries, memory, battery and thermal state from the
 * Android APIs. Best-effort: GPU load comes from vendor sysfs nodes that some devices close to apps
 * (seen 2026-09-21: Adreno gpubusy readable on a Lenovo Y700). Unreadable values stay unset and are
 * shown as "—", never guessed.
 */
final class PerfSampler {
    /** One sample. -1 / NaN = not available. */
    static final class Stats {
        int fps = -1, cpu = -1, gpu = -1, ramPct = -1;
        float watts = Float.NaN, tempC = Float.NaN;
        int thermal = 0;   // PowerManager.THERMAL_STATUS_*
    }

    private final Context ctx;
    private final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
    private final long clkTck;

    private long lastWallMs = 0, lastProcTicks = -1, lastFrames = -1;

    private String gpuNode;                    // the first node that answered, or null
    private boolean gpuProbed;

    // Candidate GPU-load nodes, most specific first. Formats: kgsl "gpubusy" is "busy total";
    // the others print a percentage, optionally followed by " %".
    private static final String[] GPU_NODES = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
    };

    PerfSampler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        long t = 100;
        try { t = Os.sysconf(OsConstants._SC_CLK_TCK); } catch (Throwable ignored) {}
        clkTck = t > 0 ? t : 100;
    }

    /** Call about once a second. {@code frames} = presented frames so far, -1 if unknown. */
    Stats sample(long frames) {
        Stats s = new Stats();
        long now = android.os.SystemClock.elapsedRealtime();
        double dt = (lastWallMs == 0) ? 0 : (now - lastWallMs) / 1000.0;
        lastWallMs = now;

        // FPS
        if (frames >= 0 && lastFrames >= 0 && dt > 0) s.fps = (int) Math.round((frames - lastFrames) / dt);
        lastFrames = frames;

        // CPU: the whole process as a share of all cores
        long procTicks = readTicks(new File("/proc/self/stat"));
        if (procTicks >= 0) {
            if (lastProcTicks >= 0 && dt > 0)
                s.cpu = pct((procTicks - lastProcTicks) / (double) clkTck / dt / cores);
            lastProcTicks = procTicks;
        }

        // GPU (best-effort)
        s.gpu = readGpu();

        // RAM: share of the device's memory in use
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) s.ramPct = (int) Math.round((mi.totalMem - mi.availMem) * 100.0 / mi.totalMem);
        }

        // PWR and TMP from the battery. Current is reported in microamps by the API contract, but
        // several vendors return milliamps — a phone draws between a few hundred mA and a few A,
        // so anything under 20 000 is read as mA. While charging this is the charging power.
        try {
            Intent bat = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int t10 = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (t10 != Integer.MIN_VALUE) s.tempC = t10 / 10f;
                int mv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null && mv > 0) {
                    int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (cur != Integer.MIN_VALUE && cur != 0) {
                        double a = Math.abs((double) cur);
                        double amps = a > 20000 ? a / 1e6 : a / 1e3;
                        double w = amps * mv / 1000.0;
                        if (w > 0 && w < 60) s.watts = (float) w;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Thermal: the system's own throttling verdict
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) s.thermal = pm.getCurrentThermalStatus();
        }
        return s;
    }

    private int readGpu() {
        if (!gpuProbed) {
            gpuProbed = true;
            for (String n : GPU_NODES) if (parseGpu(n) >= 0) { gpuNode = n; break; }
        }
        return gpuNode == null ? -1 : parseGpu(gpuNode);
    }

    /** GPU load in percent from one node, or -1 if unreadable / unparseable. */
    private static int parseGpu(String node) {
        String s = readFirstLine(new File(node));
        if (s == null) return -1;
        try {
            String[] p = s.trim().split("\\s+");
            if (node.endsWith("/gpubusy") && p.length >= 2) {
                long busy = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
                return total > 0 ? (int) Math.min(100, busy * 100 / total) : 0;
            }
            return Math.max(0, Math.min(100, Integer.parseInt(p[0].replace("%", ""))));
        } catch (Exception e) {
            return -1;
        }
    }

    /** utime + stime from a /proc stat file, in clock ticks; -1 if unreadable. */
    private static long readTicks(File stat) {
        String s = readFirstLine(stat);
        if (s == null) return -1;
        int close = s.lastIndexOf(')');   // the command name may contain spaces and parentheses
        if (close < 0 || close + 2 >= s.length()) return -1;
        String[] f = s.substring(close + 2).split(" ");
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);   // fields 14 and 15 of stat
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readFirstLine(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static int pct(double fraction) {
        return (int) Math.round(Math.max(0, fraction) * 100);
    }
}
