package com.zomdroid.game;

import static org.junit.Assert.assertTrue;

import com.zomdroid.LauncherPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PerfSummaryTest {

    @Test
    public void summarisesFramesThreadsSwapGcAndMap() {
        List<String> nat = Arrays.asList(
                "[ZPERF] 23:58:10 fps=58.0 ms p50=16 p95=20 p99=33 max=40 >50=0 >100=0 | cpu main:85%@7B GLThread:40%@4M | swapin=0MB swapout=2MB pss=2100MB | therm=0 mhz=L1800/M2400/B2800",
                "[ZPERF] 23:59:10 fps=2.1 ms p50=400 p95=480 p99=500 max=812 >50=21 >100=21 | cpu main:99%@2L | swapin=310MB swapout=120MB pss=2300MB | therm=2 mhz=L1800/M2400/B2000",
                "[ZSPIKE] 23:59:05 frame=812ms",
                "[ZPERF] adpf created target=8333us tids=1",
                "[ZPERF] adpf threads target=8333us tids=1,2",
                "[ZMEM] rss=2400MB swap=300MB avail=650MB",
                "[94.408s][info][gc] GC(98) Pause Young (Concurrent Start) (G1 Evacuation Pause) 445M->424M(476M) 11.800ms",
                "[95.147s][info][gc] GC(99) Pause Remark 433M->433M(486M) 8.490ms",
                "[95.421s][info][gc] GC(99) Concurrent Mark Cycle 1013.243ms");
        List<String> dbg = Arrays.asList(
                "[28-10-26 23:59:04.554] LOG  : Lua          f:3361> joypad: set focus to ISWorldMap for player 0.",
                "[29-10-26 00:00:05.008] LOG  : Lua          f:3361> joypad: set focus to nil for player 0.");
        String s = PerfSummary.format(nat, dbg);
        assertTrue(s, s.contains("worst 10 s 2.1 at 23:59:10"));
        assertTrue(s, s.contains("longest 812 ms"));
        assertTrue(s, s.contains("ADPF (fast-core hint): on"));
        assertTrue(s, s.contains("Stalls >= 250 ms: 1, longest: 23:59:05 812ms"));
        assertTrue(s, s.contains("main 92% big") || s.contains("main 92% small/mid"));
        assertTrue(s, s.contains("swap in 310 MB, out 122 MB (worst 10 s: in 310 MB at 23:59:10)"));
        assertTrue(s, s.contains("MemAvailable min 650 MB"));
        // Two pauses; the concurrent mark cycle is not a pause.
        assertTrue(s, s.contains("GC: 2 pauses"));
        assertTrue(s, s.contains("heap after GC up to 433 of 486 MB"));
        assertTrue(s, s.contains("worst status 2"));
        assertTrue(s, s.contains("World map open (gamepad focus): 23:59:04-00:00:05"));
    }

    @Test
    public void saysSoWhenThereIsNoData() {
        String s = PerfSummary.format(Collections.singletonList("nothing here"), Collections.emptyList());
        assertTrue(s, s.contains("no data"));
    }

    @Test
    public void gcThreadsLeaveTheLittleCoresOut() {
        // Snapdragon 778G by capacity: 4 x A55 (~400) + 3 x A78 + 1 x A78 prime.
        int[] c = GcThreads.countsFor(new long[]{410, 410, 410, 410, 960, 960, 960, 1024}, GcThreads.LITTLE_CAPACITY_RATIO);
        assertTrue(c[0] == 4 && c[1] == 1);
        // S25 by capacity (read off her phone): 6 x 765 + 2 x 1024, no little cores: the preset's 6/2.
        c = GcThreads.countsFor(new long[]{765, 765, 765, 765, 765, 765, 1024, 1024}, GcThreads.LITTLE_CAPACITY_RATIO);
        assertTrue(c[0] == 6 && c[1] == 2);
        // 6 little + 2 big: at least 2.
        c = GcThreads.countsFor(new long[]{300, 300, 300, 300, 300, 300, 1024, 1024}, GcThreads.LITTLE_CAPACITY_RATIO);
        assertTrue(c[0] == 2 && c[1] == 1);
        // Clock fallback: 4 @2.0 + 4 @3.2 GHz.
        c = GcThreads.countsFor(new long[]{2016000, 2016000, 2016000, 2016000, 3200000, 3200000, 3200000, 3302000}, GcThreads.LITTLE_FREQ_RATIO);
        assertTrue(c[0] == 4 && c[1] == 1);
        assertTrue(GcThreads.countsFor(null, 0.5) == null);

        String preset = LauncherPreferences.BUILD_42_JVM_ARGS;
        List<String> out = GcThreads.apply(Arrays.asList(preset.split("\\s+")), preset,
                new long[]{410, 410, 410, 410, 960, 960, 960, 1024}, GcThreads.LITTLE_CAPACITY_RATIO);
        assertTrue(out.toString(), out.contains("-XX:ParallelGCThreads=4") && out.contains("-XX:ConcGCThreads=1"));
        List<String> mine = Arrays.asList("-XX:ParallelGCThreads=8");
        assertTrue(GcThreads.apply(mine, "-XX:ParallelGCThreads=8", new long[]{1, 2}, 0.5) == mine);
    }

    @Test
    public void heapRaisedOnlyForTheUnchangedPresetWithEnoughRam() {
        String preset = LauncherPreferences.BUILD_42_JVM_ARGS;
        long twelve = 11L * 1024 * 1024 + 200 * 1024;   // MemTotal of a 12 GB phone
        long eight = 7L * 1024 * 1024 + 400 * 1024;
        assertTrue(HeapSizing.applies(preset, twelve));
        assertTrue(!HeapSizing.applies(preset, eight));
        assertTrue(!HeapSizing.applies("-Xmx6144M", twelve));
        List<String> out = HeapSizing.apply(Arrays.asList(preset.split("\\s+")), preset, twelve);
        assertTrue(out.toString(), out.contains("-Xmx4096M"));
        assertTrue(out.toString(), out.contains("-Xms512M"));
        for (String a : out) {
            assertTrue(a, !a.startsWith("-XX:SoftMaxHeapSize") && !a.startsWith("-XX:MaxHeapFreeRatio"));
        }
    }
}
