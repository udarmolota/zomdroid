package com.zomdroid.game;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Sizes the Build 42 preset's GC worker threads to the cores that can carry them, at launch only.
 *
 * <p>The preset asks for 6 parallel and 2 concurrent G1 threads whatever the phone. A G1 pause
 * ends when its slowest worker finishes, and on a phone with a cluster of little cores (Cortex
 * A55/A510/A520) some of the six land there and stretch every pause. So the little cluster is left
 * out: parallel = the other cores, 2..6; concurrent = 6 -> 2 as in the preset, 4 -> 1, at least 1.
 *
 * <p>Little is judged by the scheduler's own per-core capacity (/sys/.../cpu_capacity, 1024 = the
 * fastest core), which knows the microarchitecture: a Snapdragon 778G's A55s clock at 0.79 of its
 * A78s yet do far less work, while the S25's 3.5 GHz Oryon cores (capacity 765 of 1024) are no
 * little cores at all. Clock speed is the fallback when the kernel does not expose capacity.
 *
 * <p>Only an unchanged preset is touched; JVM args a player typed are theirs.
 */
public final class GcThreads {
    /** A core with less than half the capacity of the fastest one is a little core. */
    static final double LITTLE_CAPACITY_RATIO = 0.5;
    /** Without capacity: a cluster clocked this much lower than the fastest one is little. */
    static final double LITTLE_FREQ_RATIO = 0.65;

    private GcThreads() {
    }

    /** The args to launch with, sizing from this device's cores. */
    public static List<String> apply(List<String> args, String storedArgs) {
        long[] capacity = readPerCore("cpu_capacity");
        return capacity != null
                ? apply(args, storedArgs, capacity, LITTLE_CAPACITY_RATIO)
                : apply(args, storedArgs, readPerCore("cpufreq/cpuinfo_max_freq"), LITTLE_FREQ_RATIO);
    }

    /** The args to launch with: the preset's GC thread counts sized to the cores, else unchanged. */
    static List<String> apply(List<String> args, String storedArgs, long[] perCore, double littleRatio) {
        if (!HeapSizing.isPreset(storedArgs)) return args;
        int[] counts = countsFor(perCore, littleRatio);
        if (counts == null) return args;
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            if (a.startsWith("-XX:ParallelGCThreads=")) out.add("-XX:ParallelGCThreads=" + counts[0]);
            else if (a.startsWith("-XX:ConcGCThreads=")) out.add("-XX:ConcGCThreads=" + counts[1]);
            else out.add(a);
        }
        return out;
    }

    /** {parallel, concurrent} from a per-core performance figure, or null when it cannot be read. */
    static int[] countsFor(long[] perCore, double littleRatio) {
        if (perCore == null || perCore.length == 0) return null;
        long hi = 0;
        for (long v : perCore) {
            if (v <= 0) return null;
            hi = Math.max(hi, v);
        }
        int usable = 0;
        for (long v : perCore) if (v >= hi * littleRatio) usable++;
        int parallel = Math.max(2, Math.min(6, usable));
        int concurrent = Math.max(1, (parallel + 2) / 4);
        return new int[]{parallel, concurrent};
    }

    /** /sys/devices/system/cpu/cpuN/{file} for every core, or null. No Context needed. */
    private static long[] readPerCore(String file) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            try (BufferedReader r = new BufferedReader(new FileReader(
                    "/sys/devices/system/cpu/cpu" + i + "/" + file))) {
                out.add(Long.parseLong(r.readLine().trim()));
            } catch (Exception e) {
                break;
            }
        }
        if (out.isEmpty()) return null;
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }
}
