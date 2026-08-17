package com.zomdroid.game;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * The launcher's half of the F10 backup: finding one, offering it after a crash, and putting it
 * back. The agent writes backups from inside the game (see QuickSave in zomdroid-agent); restore
 * deliberately lives here instead, because the launcher runs before the JVM and can swap the save
 * directory while nothing has it open - the mod this design is read from has to quit to the main
 * menu and shut SQLite down mid-flight to do the same.
 *
 * <p>On-disk layout, all under {@code <instance>/zomdroid_backups} - outside {@code Zomboid/Saves}
 * on purpose, or the game would list the backup as a playable world:
 *
 * <pre>
 * session.running                  written while a world is loaded, removed by a shutdown hook;
 *                                  surviving = the game did not exit normally
 * &lt;gameMode&gt;/&lt;world&gt;/current       name of the finished generation
 * &lt;gameMode&gt;/&lt;world&gt;/gen-N          the backup itself, "complete" marker inside
 * </pre>
 *
 * <p>Restore is transactional and never deletes the live world first: copy the generation beside
 * the live world, then two renames, then cleanup. A kill at any point leaves a state
 * {@link #cleanupInterruptedRestore} can put right on the next launch.
 */
public final class BackupManager {
    private static final String LOG_TAG = BackupManager.class.getName();

    public static final String BACKUP_DIR_NAME = "zomdroid_backups";
    private static final String MARKER = "session.running";
    private static final String RESTORE_NEW_SUFFIX = ".__restore_new";
    private static final String RESTORE_OLD_SUFFIX = ".__restore_old";

    private BackupManager() {}

    /** Everything the two dialogs need to say: which world, when, how big. */
    public static final class Backup {
        public final String worldRel;   // "<gameMode>/<world>"
        public final File generation;   // the complete backup directory
        public final long timestamp;    // when it was taken (from the generation name)
        public final long sizeBytes;
        public final boolean crashed;   // session.running survived for this world

        Backup(String worldRel, File generation, long timestamp, long sizeBytes, boolean crashed) {
            this.worldRel = worldRel;
            this.generation = generation;
            this.timestamp = timestamp;
            this.sizeBytes = sizeBytes;
            this.crashed = crashed;
        }
    }

    public static File backupRoot(GameInstance instance) {
        return new File(instance.getHomePath(), BACKUP_DIR_NAME);
    }

    /**
     * The backup to offer, or null. When the crash marker survived it names the world; otherwise
     * the world is read from Zomboid/latestSave.ini (two lines: save folder, then game mode - the
     * game rewrites it on every load, so it is current by construction).
     */
    @Nullable
    public static Backup find(GameInstance instance) {
        File root = backupRoot(instance);
        if (!root.isDirectory()) return null;

        String worldRel = null;
        boolean crashed = false;
        File marker = new File(root, MARKER);
        if (marker.isFile()) {
            try {
                List<String> lines = Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    worldRel = lines.get(0).trim();
                    crashed = true;
                }
            } catch (IOException e) {
                Log.w(LOG_TAG, "Unreadable session marker", e);
            }
        }
        if (worldRel == null) worldRel = worldFromLatestSaveIni(instance);
        if (worldRel == null || worldRel.isEmpty()) return null;

        File worldRoot = new File(root, worldRel);
        File currentFile = new File(worldRoot, "current");
        if (!currentFile.isFile()) return null;
        try {
            String gen = new String(Files.readAllBytes(currentFile.toPath()), StandardCharsets.UTF_8).trim();
            File genDir = new File(worldRoot, gen);
            if (!new File(genDir, "complete").isFile()) return null;
            long ts = 0;
            if (gen.startsWith("gen-")) {
                try { ts = Long.parseLong(gen.substring(4)); } catch (NumberFormatException ignored) {}
            }
            return new Backup(worldRel, genDir, ts, treeSize(genDir.toPath()), crashed);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Unreadable backup state for " + worldRel, e);
            return null;
        }
    }

    /** The crash dialog fires only on this: an abnormal end AND something to offer. */
    @Nullable
    public static Backup findCrashed(GameInstance instance) {
        Backup b = find(instance);
        return (b != null && b.crashed) ? b : null;
    }

    /**
     * Cleared when the player chooses either side of the crash dialog, so it is asked once per
     * crash rather than on every launch. The agent writes a fresh marker when a world loads again.
     */
    public static void clearCrashMarker(GameInstance instance) {
        //noinspection ResultOfMethodCallIgnored
        new File(backupRoot(instance), MARKER).delete();
    }

    /**
     * Put the backup where the live world is. Blocking - run off the UI thread.
     *
     * <p>Copy first, rename second, delete last: the live world exists untouched until the copy is
     * complete and verified beside it, and the old world is only removed after the new one is in
     * place. Interrupt anywhere and {@link #cleanupInterruptedRestore} recovers.
     */
    public static void restore(GameInstance instance, Backup backup) throws IOException {
        File savesRoot = new File(instance.getHomePath(), "Zomboid/Saves");
        File live = new File(savesRoot, backup.worldRel);
        File fresh = new File(savesRoot, backup.worldRel + RESTORE_NEW_SUFFIX);
        File old = new File(savesRoot, backup.worldRel + RESTORE_OLD_SUFFIX);

        deleteTree(fresh.toPath()); // leftovers of an interrupted attempt
        deleteTree(old.toPath());

        long[] copied = copyTree(backup.generation.toPath(), fresh.toPath());
        // The generation carries our "complete" marker; it has no business inside a live save.
        //noinspection ResultOfMethodCallIgnored
        new File(fresh, "complete").delete();
        Log.i(LOG_TAG, "Backup copied beside the live world: " + copied[0] + " files, "
                + (copied[1] >> 20) + " MB");

        if (live.exists() && !live.renameTo(old))
            throw new IOException("Could not set aside the live world " + live);
        if (!fresh.renameTo(live)) {
            // Put the world back rather than leave the save missing.
            if (old.exists() && !old.renameTo(live))
                throw new IOException("Restore failed AND the live world could not be put back: " + live);
            throw new IOException("Could not move the restored world into place: " + live);
        }
        deleteTree(old.toPath());
        Log.i(LOG_TAG, "Restored " + backup.worldRel + " from " + backup.generation.getName());
    }

    /**
     * Heal whatever an interrupted restore left behind. Cheap when there is nothing to do; called
     * before every launch.
     */
    public static void cleanupInterruptedRestore(GameInstance instance) {
        File savesRoot = new File(instance.getHomePath(), "Zomboid/Saves");
        File[] modes = savesRoot.listFiles(File::isDirectory);
        if (modes == null) return;
        for (File mode : modes) {
            File[] entries = mode.listFiles(File::isDirectory);
            if (entries == null) continue;
            for (File e : entries) {
                try {
                    if (e.getName().endsWith(RESTORE_NEW_SUFFIX)) {
                        // Never renamed into place - incomplete by definition.
                        deleteTree(e.toPath());
                        Log.i(LOG_TAG, "Dropped interrupted restore copy " + e);
                    } else if (e.getName().endsWith(RESTORE_OLD_SUFFIX)) {
                        String worldName = e.getName().substring(0,
                                e.getName().length() - RESTORE_OLD_SUFFIX.length());
                        File live = new File(mode, worldName);
                        if (live.exists()) {
                            deleteTree(e.toPath()); // restore finished, old copy is surplus
                        } else if (!e.renameTo(live)) { // killed between the two renames
                            Log.w(LOG_TAG, "Could not put the world back from " + e);
                        } else {
                            Log.i(LOG_TAG, "Recovered " + live + " from an interrupted restore");
                        }
                    }
                } catch (IOException ex) {
                    Log.w(LOG_TAG, "Restore cleanup failed for " + e, ex);
                }
            }
        }
    }

    // -------------------- helpers --------------------

    @Nullable
    private static String worldFromLatestSaveIni(GameInstance instance) {
        File ini = new File(instance.getHomePath(), "Zomboid/latestSave.ini");
        if (!ini.isFile()) return null;
        try {
            List<String> lines = Files.readAllLines(ini.toPath(), StandardCharsets.UTF_8);
            if (lines.size() < 2) return null;
            String folder = lines.get(0).trim();
            String mode = lines.get(1).trim();
            if (folder.isEmpty() || mode.isEmpty()) return null;
            return mode + "/" + folder;
        } catch (IOException e) {
            return null;
        }
    }

    private static long treeSize(Path root) {
        final long[] bytes = {0};
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> bytes[0] += p.toFile().length());
        } catch (IOException e) {
            return 0;
        }
        return bytes[0];
    }

    private static long[] copyTree(Path source, Path target) throws IOException {
        final long[] acc = new long[2];
        try (var stream = Files.walk(source)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                    acc[0]++;
                    acc[1] += Files.size(dest);
                }
            }
        }
        return acc;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                //noinspection ResultOfMethodCallIgnored
                p.toFile().delete();
            });
        }
    }
}
