package com.zomdroid.game;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Performance" block of report.txt: where the last run lost its frames, summarised from the
 * [ZPERF] / [ZSPIKE] / [ZMEM] lines and the -Xlog:gc lines in native.log, plus the world map
 * opening and closing from the game's debug log (logged by the game only while a gamepad is in
 * use). Free of Android, so it is unit-tested on plain JVM.
 */
public final class PerfSummary {

    private static final Pattern PERF = Pattern.compile(
            "^\\[ZPERF\\] (\\d\\d:\\d\\d:\\d\\d) fps=([0-9.]+)(?: ms p50=(\\d+) p95=(\\d+) p99=(\\d+) max=(\\d+) >50=(\\d+) >100=(\\d+))?(.*)$");
    private static final Pattern CPU = Pattern.compile(" (\\S+):(\\d+)%@(-?\\d+)([LMB?])");
    private static final Pattern SWAP = Pattern.compile("swapin=(\\d+)MB swapout=(\\d+)MB");
    private static final Pattern PSS = Pattern.compile("pss=(\\d+)MB");
    private static final Pattern THERM = Pattern.compile("therm=(\\d+)");
    private static final Pattern SPIKE = Pattern.compile("^\\[ZSPIKE\\] (\\d\\d:\\d\\d:\\d\\d) frame=(\\d+)ms");
    private static final Pattern ZMEM = Pattern.compile("^\\[ZMEM\\] rss=(\\d+)MB swap=(\\d+)MB avail=(-?\\d+)MB");
    // [94.408s][info][gc] GC(98) Pause Young (Concurrent Start) (G1 Evacuation Pause) 445M->424M(476M) 11.800ms
    private static final Pattern GC = Pattern.compile("\\[gc\\] GC\\(\\d+\\) Pause .*? (\\d+)M->(\\d+)M\\((\\d+)M\\) ([0-9.]+)ms");
    // [28-10-26 23:59:04.554] ... joypad: set focus to ISWorldMap for player 0.
    private static final Pattern FOCUS = Pattern.compile("^\\[[0-9-]+ (\\d\\d:\\d\\d:\\d\\d)\\.\\d+\\].*joypad: set focus to (\\S+) for player");

    private PerfSummary() {
    }

    public static String format(File nativeLog, File debugLog) {
        List<String> nat = readLines(nativeLog);
        List<String> dbg = readLines(debugLog);
        return format(nat, dbg);
    }

    static String format(List<String> nativeLines, List<String> debugLines) {
        // native.log holds one run (truncated at every start), so everything in it is the last run.
        List<Double> fps = new ArrayList<>();
        List<Integer> p95 = new ArrayList<>();
        int worstP99 = 0, longest = 0;
        String worstFpsAt = null;
        double worstFps = Double.MAX_VALUE;
        long swapIn = 0, swapOut = 0, worstSwapIn = 0;
        String worstSwapAt = null;
        int maxPss = 0, maxTherm = -1, minAvail = Integer.MAX_VALUE;
        Map<String, double[]> threads = new LinkedHashMap<>();   // name -> {sum%, samples, bigCount}
        List<String[]> spikes = new ArrayList<>();                 // {time, ms}
        int gcCount = 0, maxHeapAfter = 0, committedAtMax = 0;
        double gcTotal = 0, gcMax = 0;
        List<Double> pauses = new ArrayList<>();
        int samples = 0;
        String adpf = null;

        for (String line : nativeLines) {
            if (line.startsWith("[ZPERF] adpf ")) {
                // created / threads / unavailable / off: the first word after "adpf" tells it.
                String state = line.substring("[ZPERF] adpf ".length()).split(" ")[0];
                if (state.equals("off")) adpf = "off (ZOMDROID_ADPF=0)";
                else if (state.equals("unavailable")) adpf = "unavailable on this phone";
                else if (adpf == null) adpf = "on";
                continue;
            }
            Matcher m = PERF.matcher(line);
            if (m.find()) {
                samples++;
                double f = Double.parseDouble(m.group(2));
                if (m.group(3) != null) {
                    fps.add(f);
                    p95.add(Integer.parseInt(m.group(4)));
                    worstP99 = Math.max(worstP99, Integer.parseInt(m.group(5)));
                    longest = Math.max(longest, Integer.parseInt(m.group(6)));
                    if (f < worstFps) {
                        worstFps = f;
                        worstFpsAt = m.group(1);
                    }
                }
                String rest = m.group(9);
                Matcher c = CPU.matcher(rest);
                while (c.find()) {
                    double[] t = threads.computeIfAbsent(c.group(1), k -> new double[3]);
                    t[0] += Integer.parseInt(c.group(2));
                    t[1]++;
                    if ("B".equals(c.group(4))) t[2]++;
                }
                Matcher s = SWAP.matcher(rest);
                if (s.find()) {
                    long in = Long.parseLong(s.group(1));
                    swapIn += in;
                    swapOut += Long.parseLong(s.group(2));
                    if (in > worstSwapIn) {
                        worstSwapIn = in;
                        worstSwapAt = m.group(1);
                    }
                }
                Matcher p = PSS.matcher(rest);
                if (p.find()) maxPss = Math.max(maxPss, Integer.parseInt(p.group(1)));
                Matcher th = THERM.matcher(rest);
                if (th.find()) maxTherm = Math.max(maxTherm, Integer.parseInt(th.group(1)));
                continue;
            }
            m = SPIKE.matcher(line);
            if (m.find()) {
                spikes.add(new String[]{m.group(1), m.group(2)});
                continue;
            }
            m = ZMEM.matcher(line);
            if (m.find()) {
                int avail = Integer.parseInt(m.group(3));
                if (avail >= 0) minAvail = Math.min(minAvail, avail);
                continue;
            }
            m = GC.matcher(line);
            if (m.find()) {
                gcCount++;
                int after = Integer.parseInt(m.group(2));
                if (after > maxHeapAfter) {
                    maxHeapAfter = after;
                    committedAtMax = Integer.parseInt(m.group(3));
                }
                double ms = Double.parseDouble(m.group(4));
                gcTotal += ms;
                gcMax = Math.max(gcMax, ms);
                pauses.add(ms);
            }
        }

        StringBuilder sb = new StringBuilder("Performance (last run):\n");
        if (samples == 0 && gcCount == 0) {
            sb.append("           no data (the game did not reach a frame, or a build before 1.5.4)\n");
            return sb.toString();
        }
        if (!fps.isEmpty()) {
            sb.append(String.format(Locale.US, "           FPS %s median, worst 10 s %.1f at %s; frame p95 %d ms median, p99 worst %d ms, longest %d ms\n",
                    fmt(median(fps)), worstFps, worstFpsAt, (int) Math.round(median(intsToDoubles(p95))), worstP99, longest));
        }
        if (adpf != null) sb.append("           ADPF (fast-core hint): ").append(adpf).append('\n');
        if (!spikes.isEmpty()) {
            List<String[]> top = new ArrayList<>(spikes);
            top.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
            sb.append("           Stalls >= 250 ms: ").append(spikes.size()).append(", longest:");
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                sb.append(' ').append(top.get(i)[0]).append(' ').append(top.get(i)[1]).append("ms");
            }
            sb.append('\n');
        }
        if (!threads.isEmpty() && samples > 0) {
            List<Map.Entry<String, double[]>> list = new ArrayList<>(threads.entrySet());
            final int n = samples;
            list.sort((a, b) -> Double.compare(b.getValue()[0] / n, a.getValue()[0] / n));
            sb.append("           Busiest threads (avg % of a core):");
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                double[] t = list.get(i).getValue();
                sb.append(String.format(Locale.US, " %s %.0f%%%s", list.get(i).getKey(), t[0] / n,
                        t[2] * 2 >= t[1] ? " big" : " small/mid"));
                if (i < Math.min(6, list.size()) - 1) sb.append(',');
            }
            sb.append('\n');
        }
        if (worstSwapAt != null || maxPss > 0 || minAvail != Integer.MAX_VALUE) {
            sb.append("           Memory: swap in ").append(swapIn).append(" MB, out ").append(swapOut).append(" MB");
            if (worstSwapAt != null) sb.append(" (worst 10 s: in ").append(worstSwapIn).append(" MB at ").append(worstSwapAt).append(')');
            if (maxPss > 0) sb.append(", PSS max ").append(maxPss).append(" MB");
            if (minAvail != Integer.MAX_VALUE) sb.append(", MemAvailable min ").append(minAvail).append(" MB");
            sb.append('\n');
        }
        if (gcCount > 0) {
            Collections.sort(pauses);
            double gcP95 = pauses.get(Math.min(pauses.size() - 1, (int) (pauses.size() * 0.95)));
            sb.append(String.format(Locale.US, "           GC: %d pauses, %.1f s in total, p95 %.0f ms, max %.0f ms; heap after GC up to %d of %d MB\n",
                    gcCount, gcTotal / 1000.0, gcP95, gcMax, maxHeapAfter, committedAtMax));
        }
        if (maxTherm >= 0) {
            sb.append("           Thermal: worst status ").append(maxTherm)
                    .append(" (0 none, 1 light, 2 moderate, 3 severe, 4 critical)\n");
        }
        // World map: open from the focus line until the focus moves to anything else.
        StringBuilder map = new StringBuilder();
        String openedAt = null;
        for (String line : debugLines) {
            Matcher m = FOCUS.matcher(line);
            if (!m.find()) continue;
            boolean isMap = m.group(2).equals("ISWorldMap");
            if (isMap && openedAt == null) {
                openedAt = m.group(1);
            } else if (!isMap && openedAt != null) {
                map.append(' ').append(openedAt).append('-').append(m.group(1));
                openedAt = null;
            }
        }
        if (openedAt != null) map.append(' ').append(openedAt).append("-end");
        if (map.length() > 0) sb.append("           World map open (gamepad focus):").append(map).append('\n');
        return sb.toString();
    }

    private static List<Double> intsToDoubles(List<Integer> in) {
        List<Double> out = new ArrayList<>(in.size());
        for (int v : in) out.add((double) v);
        return out;
    }

    private static double median(List<Double> values) {
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (Exception ignored) {
        }
        return out;
    }
}
