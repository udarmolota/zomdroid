package com.zomdroid.game;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zomdroid.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One launch of an instance with the recommended settings, no mods and default game options, to
 * tell "this instance is broken by its mods or settings" from "the game does not start here".
 *
 * <p>The game runs on its own profile ({@code -cachedir=<home>/safe-start}, emptied before each
 * run), so none of the player's mods, options or saves take part; the launcher settings come from
 * {@link SuggestedPreset#forSafeStart} and are written nowhere.
 *
 * <p>The game runs in the launcher's process and takes it down when it crashes, so the outcome is
 * kept on disk: "running" when the run starts, "passed" once the game leaves the terms-of-service
 * state for the main menu. A "running" found by a later process means that run died first.
 *
 * <p>What a failed run cannot rule out is the game files themselves: mods that copy classes into
 * game/ (BetterFPS, PZ Optimization) are still there. {@link #findReplacedFiles} lists them.
 */
public final class SafeStart {
    private static final String LOG_TAG = "SafeStart";

    public static final String PROFILE_DIR = "safe-start";
    private static final String STATE_FILE = "safe-start.properties";

    public static final String RUNNING = "running";
    public static final String PASSED = "passed";
    public static final String FAILED = "failed";

    /** Logged by every build when the game leaves the terms-of-service screen for the main menu. */
    private static final String[] MENU_MARKERS = {
            "STATE: exit zombie.gameStates.TermsOfServiceState",
            "STATE: enter zombie.gameStates.MainScreenState",
    };

    /**
     * Classes our own launch-time patchers write into game/ (patch package). They differ from the
     * jar by design and are not a mod's doing. Inner classes of these are covered too.
     */
    private static final Set<String> OUR_CLASSES = new HashSet<>(Arrays.asList(
            "zombie/core/opengl/ShaderUnit",
            "zombie/Lua/LuaManager$GlobalObject",
            "fmod/javafmodJNI",
            "zombie/core/Core",
            "zombie/core/znet/ZNetStatistics",
            "zombie/gameStates/MainScreenState",
            "zombie/iso/LightingJNI"));

    /** Top-level game folders that hold no classes; walking media/ alone means tens of thousands of files. */
    private static final Set<String> NO_CLASSES = new HashSet<>(Arrays.asList(
            "media", "android", "macos", "controls", "mods", "Workshop", "natives", "license"));

    /** Set while a Safe start runs in this process; a new process starts without it. */
    private static volatile String runningInstance;

    private SafeStart() {}

    public static File profileDir(GameInstance instance) {
        return new File(instance.getHomePath(), PROFILE_DIR);
    }

    /** Empties the profile and records the run as started. */
    public static void begin(GameInstance instance) throws IOException {
        File profile = profileDir(instance);
        if (profile.exists()) FileUtils.deleteDirectory(profile);
        if (!profile.mkdirs()) throw new IOException("Cannot create " + profile);
        runningInstance = instance.getName();
        write(instance, RUNNING, -1);
    }

    public static void markPassed(GameInstance instance) {
        write(instance, PASSED, -1);
    }

    /**
     * Turns a run that a previous process left "running" into "failed" and returns the replaced
     * game files (possibly none), or null when there was no such run. Never judges a run of this
     * process: that one may simply still be going. Does disk work; not on the main thread.
     */
    public static List<String> resolveFailedRun(GameInstance instance) {
        if (instance.getName().equals(runningInstance)) return null;
        if (!RUNNING.equals(read(instance).getProperty("state"))) return null;
        List<String> replaced = findReplacedFiles(instance);
        write(instance, FAILED, replaced.size());
        return replaced;
    }

    /** One line for the bug report. */
    public static String describe(GameInstance instance) {
        Properties p = read(instance);
        String state = p.getProperty("state");
        if (state == null) return "never tried";
        String time = p.getProperty("time", "?");
        String replaced = p.getProperty("replaced");
        return state + " " + time + (replaced != null ? ", " + replaced + " replaced game file(s)" : "");
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Game files a mod has replaced or added, as paths relative to game/, sorted.
     *
     * <p>Build 42 keeps its classes in projectzomboid.jar, so a loose class that differs from its
     * jar entry - or has none - is not the game's. Build 41 has no jar to compare with; there only
     * the backups mods leave next to the class they overwrote ({@code X.class.original}, which our
     * own BetterFPS installer writes) give a replacement away. Files under media/ are not checked:
     * there is nothing to compare them with.
     */
    public static List<String> findReplacedFiles(GameInstance instance) {
        try {
            return findReplacedFiles(new File(instance.getGamePath()));
        } catch (IOException e) {
            Log.w(LOG_TAG, "Cannot compare game classes with the game jar", e);
            return new ArrayList<>();
        }
    }

    /** {@link #findReplacedFiles(GameInstance)} on a game folder; free of Android for tests. */
    static List<String> findReplacedFiles(File game) throws IOException {
        Set<String> found = new TreeSet<>();
        List<String> classes = new ArrayList<>();
        File[] top = game.listFiles();
        if (top == null) return new ArrayList<>();
        for (File f : top) {
            if (f.isDirectory() && !NO_CLASSES.contains(f.getName()) && !f.getName().startsWith("jre")) {
                collect(f, f.getName(), classes, found);
            }
        }

        File jar = new File(game, "projectzomboid.jar");
        if (jar.isFile()) {
            try (ZipFile zip = new ZipFile(jar)) {
                for (String rel : classes) {
                    if (isOurs(rel)) continue;
                    ZipEntry entry = zip.getEntry(rel);
                    File file = new File(game, rel);
                    if (entry == null || entry.getSize() != file.length() || entry.getCrc() != crc(file)) {
                        found.add(rel);
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Collects every .class below dir, and the classes a ".class.original" backup sits next to. */
    private static void collect(File dir, String rel, List<String> classes, Set<String> found) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String childRel = rel + "/" + child.getName();
            if (child.isDirectory()) {
                if (FileUtils.isWalkableDirectory(child)) collect(child, childRel, classes, found);
            } else if (child.getName().endsWith(".class")) {
                classes.add(childRel);
            } else if (child.getName().endsWith(".class.original")) {
                String base = childRel.substring(0, childRel.length() - ".original".length());
                if (new File(dir, child.getName().substring(0, child.getName().length() - ".original".length())).isFile()
                        && !isOurs(base)) {
                    found.add(base);
                }
            }
        }
    }

    private static boolean isOurs(String rel) {
        String name = rel.substring(0, rel.length() - ".class".length());
        if (OUR_CLASSES.contains(name)) return true;
        int inner = name.indexOf('$');
        while (inner > 0) {
            if (OUR_CLASSES.contains(name.substring(0, inner))) return true;
            inner = name.indexOf('$', inner + 1);
        }
        return false;
    }

    private static long crc(File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    // ---------------------------------------------------------------------------------------

    private static Properties read(GameInstance instance) {
        Properties p = new Properties();
        File file = new File(instance.getHomePath(), STATE_FILE);
        if (!file.isFile()) return p;
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Cannot read " + file, e);
        }
        return p;
    }

    private static void write(GameInstance instance, String state, int replaced) {
        Properties p = new Properties();
        p.setProperty("state", state);
        p.setProperty("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date()));
        if (replaced >= 0) p.setProperty("replaced", String.valueOf(replaced));
        File file = new File(instance.getHomePath(), STATE_FILE);
        try (OutputStream out = new FileOutputStream(file)) {
            p.store(out, null);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Cannot write " + file, e);
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Watches the run's console.txt for the main menu and calls {@code onPassed} once, on the main
     * thread. Reads only what was appended since the last look.
     */
    public static final class Watcher implements AutoCloseable {
        private static final long POLL_MS = 1000;

        private final GameInstance instance;
        private final File console;
        private final Runnable onPassed;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Thread thread;
        private volatile boolean closed;
        private long offset;
        private String carry = "";

        public Watcher(GameInstance instance, Runnable onPassed) {
            this.instance = instance;
            this.console = new File(profileDir(instance), "console.txt");
            this.onPassed = onPassed;
            this.thread = new Thread(this::run, "safe-start-watcher");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            while (!closed) {
                if (reachedMenu()) {
                    markPassed(instance);
                    main.post(() -> { if (!closed) onPassed.run(); });
                    return;
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private boolean reachedMenu() {
            if (!console.isFile() || console.length() <= offset) return false;
            try (RandomAccessFile f = new RandomAccessFile(console, "r")) {
                long len = f.length();
                if (len < offset) offset = 0; // the game started the file over
                byte[] buf = new byte[(int) Math.min(len - offset, 4 * 1024 * 1024)];
                f.seek(offset);
                f.readFully(buf);
                offset += buf.length;
                String text = carry + new String(buf, StandardCharsets.UTF_8);
                for (String marker : MENU_MARKERS) if (text.contains(marker)) return true;
                // A marker may be split between two reads.
                carry = text.substring(Math.max(0, text.length() - 128));
            } catch (IOException e) {
                Log.w(LOG_TAG, "Cannot read " + console, e);
            }
            return false;
        }

        @Override
        public void close() {
            closed = true;
            thread.interrupt();
        }
    }

    /** The replaced files as a bullet list for a dialog, at most {@code max} lines plus a count. */
    public static String summarize(List<String> files, int max) {
        List<String> shown = files.size() > max ? files.subList(0, max) : files;
        StringBuilder sb = new StringBuilder();
        for (String f : shown) sb.append("• ").append(f).append('\n');
        if (files.size() > max) sb.append("… +").append(files.size() - max);
        return sb.toString().trim();
    }
}
