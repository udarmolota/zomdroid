package com.zomdroid.game;

import com.zomdroid.LauncherPreferences;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Raises the Java heap of the Build 42 preset on phones with plenty of RAM, at launch only.
 *
 * <p>The preset caps the heap at 2 GB and keeps it tight (SoftMaxHeapSize=1536M,
 * MaxHeapFreeRatio=20) so 6-8 GB phones keep room for the renderer and textures. A long Build 42
 * session needs 1.4-1.8 GB of live heap, so under that cap G1 collects hundreds of times per
 * session with 0.3-0.5 s pauses (GC lines in the reports of 2026-11-02..15), which players read as
 * lag in hordes. With 11 GB or more there is room: the heap may grow to 4 GB and is no longer
 * held small. Phones below that keep the preset as it is until the [ZPERF] data says otherwise.
 *
 * <p>Only an unchanged preset is touched; JVM args a player typed are theirs.
 */
public final class HeapSizing {
    /** MemTotal of a 12 GB phone is about 11 GB; 11 GB phones report a little over 10. */
    static final long MIN_TOTAL_KB = 10L * 1024 * 1024 + 512 * 1024;

    private HeapSizing() {
    }

    /** The args to launch with: the preset raised when the device has the RAM, else unchanged. */
    public static List<String> apply(List<String> args, String storedArgs, long totalKb) {
        if (!applies(storedArgs, totalKb)) return args;
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            if (a.startsWith("-Xmx")) out.add("-Xmx4096M");
            else if (a.startsWith("-XX:SoftMaxHeapSize=") || a.startsWith("-XX:MaxHeapFreeRatio=")) continue;
            else out.add(a);
        }
        return out;
    }

    /** True when the stored args are the Build 42 preset and the device has 11 GB or more. */
    public static boolean applies(String storedArgs, long totalKb) {
        return totalKb >= MIN_TOTAL_KB && isPreset(storedArgs);
    }

    /** True when the stored args are exactly the Build 42 preset (whitespace aside). */
    public static boolean isPreset(String storedArgs) {
        return storedArgs != null && LauncherPreferences.squashWhitespace(storedArgs)
                .equals(LauncherPreferences.squashWhitespace(LauncherPreferences.BUILD_42_JVM_ARGS));
    }

    /** MemTotal in kB, or -1. Read from /proc, so it needs no Context. */
    public static long totalMemKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
