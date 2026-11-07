package com.zomdroid;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Diagnosis:" block at the top of report.txt: known failure signatures found in the logs the
 * report collects, each with one line saying what it means. Every rule here is a case we once
 * worked out by hand from a report; the block saves doing it again. It only points: the logs
 * themselves are still in the zip, and no finding means "nothing known", not "nothing wrong".
 *
 * <p>Free of Android so the rules can be tested on plain strings.
 */
public final class ReportDiagnosis {
    /** Only the end of each log is read: failures are at the end, and logs can be huge. */
    private static final int TAIL_BYTES = 4 * 1024 * 1024;

    /** A signature in the logs and what it means. */
    private static final class Rule {
        final Pattern pattern;
        final String finding;
        Rule(String regex, String finding) {
            this.pattern = Pattern.compile(regex);
            this.finding = finding;
        }
    }

    private static final Rule[] LOG_RULES = {
            new Rule("UnsupportedClassVersionError",
                    "Game classes need a newer Java than the one started: GL4ES (Java 21) on Build 42. Use NG_GL4ES, ZINK or MOBILEGLUES."),
            new Rule("NoSuchFieldError[^\\n]*FloorBloodTypes|FloorBloodTypes[^\\n]*NoSuchFieldError",
                    "A Build 41 BetterFPS IsoChunkMap.class is in a Build 42 game."),
            new Rule("signer information does not match",
                    "Unpacked game classes mixed with the signed projectzomboid.jar (42.22+)."),
            new Rule("n_updateRealZombies|NoSuchMethodError[^\\n]*writeToLog|writeToLog[^\\n]*MapCollisionData",
                    "macOS libraries from another game build: download them again for this game version."),
            new Rule("java\\.lang\\.OutOfMemoryError",
                    "Java ran out of heap (-Xmx too small for this world or mod list)."),
            new Rule("zink: DEVICE LOST|VK_ERROR_DEVICE_LOST",
                    "The Vulkan driver crashed (device lost): try another Vulkan driver or renderer."),
            new Rule("AdvancedAnimator\\.buildChecksum",
                    "Server: a mod animation file could not be matched (mods folder link); fixed in the coop agent since 1.5.3."),
            new Rule("Server Terminated",
                    "The hosted/dedicated server stopped: the cause is just above \"Server Terminated\" in coop-console.txt."),
            new Rule("\\[pzopt\\] loaded override",
                    "PZ_Optimization is active: it replaces game classes and some of its options use a lot of memory."),
    };

    /** Workshop/mod ids that once turned out to cause a launcher-looking complaint. */
    private static final String[][] KNOWN_MODS = {
            {"NC_NaturesCall", "can break the right stick"},
            {"Buttstroke", "can remove the push animation"},
            {"BicycleMod", "can make the character turn slowly on foot"},
    };

    private static final Pattern MOD_LOADING = Pattern.compile("> loading (\\S+)");

    private ReportDiagnosis() {}

    /**
     * Findings for a report. {@code build} is "41"/"42" or null, {@code renderer} the instance's
     * renderer name, {@code lastExit} the newest exit line ("killed by signal SIGABRT (6)", "LOW
     * MEMORY kill"...) or null, {@code logs} the files to scan (missing ones are skipped).
     */
    public static List<String> diagnose(String build, String renderer, String lastExit, List<File> logs) {
        StringBuilder text = new StringBuilder();
        for (File log : logs) {
            if (log == null || !log.isFile()) continue;
            try {
                text.append(tail(log)).append('\n');
            } catch (IOException ignored) {
                // An unreadable log just adds nothing.
            }
        }
        return diagnoseText(build, renderer, lastExit, text.toString());
    }

    /** {@link #diagnose} on text already read. Package-private for tests. */
    static List<String> diagnoseText(String build, String renderer, String lastExit, String text) {
        Set<String> findings = new LinkedHashSet<>();
        if ("42".equals(build) && "GL4ES".equals(renderer)) {
            findings.add("GL4ES is set on a Build 42 instance: it cannot run Build 42.");
        }
        if ("41".equals(build) && ("NG_GL4ES".equals(renderer) || "MOBILEGLUES_EXPERIMENTAL".equals(renderer))) {
            findings.add(renderer + " is set on a Build 41 instance: it is made for Build 42.");
        }
        if (lastExit != null) {
            String exit = lastExit.toLowerCase(Locale.ROOT);
            if (exit.contains("low memory") || exit.contains("sigkill")) {
                findings.add("Android killed the app for memory at the last exit: close other apps, lower the render scale or -Xmx.");
            } else if (exit.contains("native crash") || exit.contains("sigsegv") || exit.contains("sigabrt") || exit.contains("sigbus")) {
                findings.add("The last exit was a native crash/abort (" + lastExit.trim() + "): the logs stop without a Java error.");
            }
        }
        for (Rule rule : LOG_RULES) {
            if (rule.pattern.matcher(text).find()) findings.add(rule.finding);
        }
        Set<String> mods = new LinkedHashSet<>();
        Matcher m = MOD_LOADING.matcher(text);
        while (m.find()) mods.add(m.group(1));
        for (String[] known : KNOWN_MODS) {
            if (mods.contains(known[0])) findings.add("Mod " + known[0] + " is enabled: it " + known[1] + ".");
        }
        return new ArrayList<>(findings);
    }

    /** The block for report.txt. */
    public static String format(List<String> findings) {
        if (findings.isEmpty()) return "Diagnosis: nothing known found\n";
        StringBuilder sb = new StringBuilder("Diagnosis:\n");
        for (String f : findings) sb.append("  - ").append(f).append('\n');
        return sb.toString();
    }

    private static String tail(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            long start = Math.max(0, len - TAIL_BYTES);
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
