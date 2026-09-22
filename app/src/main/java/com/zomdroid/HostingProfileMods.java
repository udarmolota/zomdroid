package com.zomdroid;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One mods folder for both profiles of an instance.
 *
 * <p>Hosting (in-game Host and the dedicated server) runs PZ with {@code -cachedir=coop-probe}, so
 * the game looks for mods in {@code coop-probe/mods}. Everything that installs mods - the mod
 * import, ZombieBuddy's Lua part, the case workaround - writes to {@code Zomboid/mods}, so a
 * hosting player had no way to get mods onto the server short of copying folders by hand, and a
 * copy goes out of date with every mod update (server and clients then disagree on versions).
 * Making {@code coop-probe/mods} a link to {@code Zomboid/mods} gives both profiles the same mods;
 * saves, settings and memory stay separate. Decided 2026-09-21.
 *
 * <p>The link target is relative, so it survives a renamed or copied instance.
 */
public final class HostingProfileMods {
    private static final String LOG_TAG = "HostingProfileMods";
    static final String PROFILE = "coop-probe";
    private static final Path TARGET = Paths.get("../Zomboid/mods");

    private HostingProfileMods() {}

    /** Makes {@code coop-probe/mods} a link to {@code Zomboid/mods}. Cheap when already done. */
    public static void link(GameInstance instance) throws IOException {
        File home = new File(instance.getHomePath());
        File installed = new File(home, "Zomboid/mods");
        if (!installed.isDirectory() && !installed.mkdirs()) throw new IOException("Cannot create " + installed);
        File profile = new File(home, PROFILE);
        if (!profile.isDirectory() && !profile.mkdirs()) throw new IOException("Cannot create " + profile);

        File mods = new File(profile, "mods");
        Path path = mods.toPath();
        if (Files.isSymbolicLink(path)) {
            if (Files.readSymbolicLink(path).equals(TARGET)) return;
            // The dedicated server used to link with an absolute target, which breaks on rename.
            Files.delete(path);
        } else if (mods.isDirectory()) {
            migrate(mods, installed);
        } else if (mods.exists() && !mods.delete()) {
            throw new IOException("Cannot replace " + mods);
        }
        Files.createSymbolicLink(path, TARGET);
        Log.i(LOG_TAG, mods + " -> " + TARGET);
    }

    /**
     * A real {@code coop-probe/mods} exists: made by the game or the server before this link, and
     * possibly filled by hand. Mods the main folder lacks move there; nothing is deleted. What
     * cannot move (same name already installed, or the case workaround's scaffolding) stays in
     * {@code coop-probe/mods-before-shared}.
     */
    private static void migrate(File mods, File installed) throws IOException {
        // The case workaround's chain hangs off a directory named after the first component of the
        // lowercased absolute path ("data" on Android); it spells out coop-probe paths, so it stays.
        String scaffolding = mods.getAbsolutePath().toLowerCase(java.util.Locale.US).replaceFirst("^/+", "");
        if (scaffolding.indexOf('/') >= 0) scaffolding = scaffolding.substring(0, scaffolding.indexOf('/'));
        int moved = 0, kept = 0;
        File[] children = mods.listFiles();
        if (children != null) for (File child : children) {
            File target = new File(installed, child.getName());
            if (child.getName().equals(scaffolding) || Files.isSymbolicLink(child.toPath()) || target.exists()
                    || Files.isSymbolicLink(target.toPath()) || !child.renameTo(target)) {
                kept++;
            } else {
                moved++;
            }
        }
        if (kept == 0) {
            if (!mods.delete()) throw new IOException("Cannot remove " + mods);
        } else {
            File backup = new File(mods.getParentFile(), "mods-before-shared");
            for (int i = 2; backup.exists(); i++) backup = new File(mods.getParentFile(), "mods-before-shared-" + i);
            if (!mods.renameTo(backup)) throw new IOException("Cannot move " + mods + " to " + backup);
        }
        Log.i(LOG_TAG, "Hosting mods merged into " + installed + ": moved " + moved + ", kept " + kept);
    }
}
