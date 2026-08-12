package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.FileUtils;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The case-sensitivity workaround for Build 42.13+, applied both when a mod is installed and again
 * at every game launch.
 *
 * <p>Build 42.13 regressed file lookup on case-sensitive filesystems - 41.78 is fine. It is
 * reported to the Indie Stone ("[42.13] Regression in Build 42.13: Linux filename case-sensitivity
 * issue") and still open, so this is ours to carry. The game mangles paths in three distinct ways
 * and each needs its own answer:
 *
 * <ol>
 *   <li>The file is requested in lowercase: "media/scripts/recipes/recipes_ladders.txt" while the
 *       mod ships "recipes_Ladders.txt". Answered by giving every capitalised entry a lowercase
 *       alias beside it. The multiplayer client check compares the same relative path, so the
 *       aliases have to live in the real mod folder - a copy off to the side is why joining a
 *       server still failed.</li>
 *   <li>The mod's whole absolute path is lowercased and appended to the mods root, yielding
 *       "&lt;mods&gt;/data/user/0/.../zomboid/mods/&lt;mod&gt;/...". No amount of aliasing inside
 *       the mod creates that prefix, so the prefix is materialised and its last component points
 *       back at the real mod.</li>
 *   <li>{@code AdvancedAnimator} lowercases the absolute path and then uses it <em>as</em> an
 *       absolute path, so it looks for "instances/project zomboid/zomboid/mods/...". That never
 *       reaches the mods folder at all - it fails at the instance directory, above anything the
 *       other two answers can build. Two links per instance cover it for every mod at once.</li>
 * </ol>
 *
 * <p>Form 3 is not cosmetic: a missing animation file fails the Lua reload, which leaves
 * {@code globals} null, which throws a NullPointerException out of
 * {@code ConnectToServerState.Finish} - the player sees a dead screen with unclickable buttons when
 * joining a modded server. Any mod carrying animations can trigger it (damnlib, which the popular
 * KI5 vehicles build on, is how we found it).
 *
 * <p>Everything here is symlinks. Measured on the Ladders mod: 206 aliases plus one mod link added
 * 0 KB, where the lowercase copy this replaces cost 1.2 MB - exactly the size of the mod.
 *
 * <h3>Why it also runs at launch</h3>
 *
 * <p>Form 2 bakes an absolute path into a directory chain, and the link at the end of that chain
 * stores an absolute target. Both go stale the moment anything above the mod changes - a renamed or
 * recreated instance, or a build with a different applicationId. A tester who moved his mods from
 * "Project Zomboid" to "Project Zomboid02" kept every file yet lost every form-2 lookup, because
 * the chain still spelled out the old instance. Nothing tells the installer that happened, so the
 * repair belongs at launch, where the current path is known. It is also the only thing that can
 * help mods installed before any of this existed.
 *
 * <h3>What is deliberately not done</h3>
 *
 * <p>No lowercase alias is created for a mod's own directory inside "mods". It looks like the
 * obvious fourth form, but the game scans that folder one level deep looking for
 * "&lt;entry&gt;/common/mod.info", follows symlinks while doing it, and would find the same mod
 * twice under two names. Worse, whichever it picked could hand {@code ScriptManager} a path through
 * the link: it builds its base URI from {@code getCanonicalFile()} while {@code ZomboidFileSystem}
 * uses the raw path, so the two disagree, {@code URI.relativize} silently returns the argument
 * unchanged, and every script's "relative" name comes back absolute - which resolves to null and
 * throws out of {@code NetChecksum.addFile} once per script file. Observed mod folder names are
 * already lowercase, so this buys nothing and risks that.
 */
public final class LowercasePathAliases {
    private static final String LOG_TAG = LowercasePathAliases.class.getName();

    private LowercasePathAliases() {}

    // -------------------- LAUNCH-TIME REPAIR --------------------

    /**
     * Bring an instance's aliases up to date with where it actually lives right now. Safe to call
     * on every launch: existing links are left alone, and an instance can be created, renamed or
     * copied between launches without anything else noticing.
     */
    public static void repair(GameInstance gameInstance) {
        File instanceDir = new File(gameInstance.getHomePath());
        if (!instanceDir.isDirectory()) return;

        // Form 3, the instance level. Listing instances reads the preferences JSON rather than
        // scanning this directory, so the extra entry cannot show up as a duplicate instance.
        link(instanceDir.getParentFile(), instanceDir.getName());
        link(instanceDir, "Zomboid");

        repairInstalledMods(new File(instanceDir, "Zomboid/mods"));
    }

    /** Re-apply forms 1 and 2 to every mod already sitting in the folder. */
    private static void repairInstalledMods(File modsDir) {
        File[] entries = modsDir.listFiles();
        if (entries == null) return;

        // The form-2 chain hangs off a single directory named after the first component of the
        // lowercased absolute path ("data" on Android). Walking into our own scaffolding would
        // build a chain inside a chain, so it is recognised and stepped over.
        String scaffolding = doubledRootName(modsDir);

        long startedAt = System.currentTimeMillis();
        int repaired = 0;
        for (File modDir : entries) {
            if (!modDir.isDirectory()) continue;
            if (Files.isSymbolicLink(modDir.toPath())) continue;
            if (modDir.getName().equals(scaffolding)) continue;
            applyToMod(modDir, modsDir);
            repaired++;
        }
        if (repaired > 0) {
            Log.i(LOG_TAG, "Case workaround refreshed for " + repaired + " mod(s) in "
                    + (System.currentTimeMillis() - startedAt) + " ms");
        }
    }

    private static String doubledRootName(File modsDir) {
        String path = stripLeadingSlashes(modsDir.getAbsolutePath().toLowerCase(Locale.US));
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    // -------------------- PER-MOD WORKAROUND --------------------

    /** Apply forms 1 and 2 to one mod. Called at install time and again at every launch. */
    public static void applyToMod(File modDir, File modsDir) {
        int aliases = createLowercaseAliases(modDir);

        // The doubled path is derived from the mod's own absolute path rather than assembled from
        // pieces. The game lowercases that whole path and appends it to the mods root, so mirroring
        // it is exact by construction - and it stops depending on things we do not control: the
        // package name used to be hardcoded as "com.zomdroid" here, which silently broke every
        // build with a different applicationId (a .test build reported paths under
        // com.zomdroie.test, so nothing under the doubled path ever resolved and every modded
        // server join failed). Instance name, data-dir location and package all come along for free.
        String doubled = stripLeadingSlashes(modDir.getAbsolutePath().toLowerCase(Locale.US));
        File modLink = new File(modsDir, doubled);
        File inceptionDir = modLink.getParentFile();
        if (inceptionDir != null) inceptionDir.mkdirs();
        // Rebuilt rather than kept: an existing link can point at the mod folder of the instance
        // this one was copied from, which resolves fine and is still wrong. Clears equally the full
        // copy left behind by installs made before this existed - deleteDirectory drops a link
        // without touching what it points at.
        try {
            if (modLink.exists() || Files.isSymbolicLink(modLink.toPath()))
                FileUtils.deleteDirectory(modLink);
            Files.createSymbolicLink(modLink.toPath(), modDir.toPath());
        } catch (IOException | UnsupportedOperationException e) {
            Log.w(LOG_TAG, "Failed to link " + modLink + " -> " + modDir, e);
        }

        if (aliases > 0) Log.i(LOG_TAG, "Case workaround for " + modDir.getName() + ": " + aliases + " alias(es)");
    }

    /** Give every entry whose name is not already lowercase a lowercase alias beside it. */
    private static int createLowercaseAliases(File root) {
        List<File> entries = new ArrayList<>();
        collectMixedCaseEntries(root, entries);
        int created = 0;
        for (File entry : entries) {
            File alias = new File(entry.getParentFile(), entry.getName().toLowerCase(Locale.US));
            if (alias.exists() || Files.isSymbolicLink(alias.toPath())) continue;
            try {
                // Relative target: the alias keeps working if the tree is moved or renamed.
                Files.createSymbolicLink(alias.toPath(), Paths.get(entry.getName()));
                created++;
            } catch (IOException | UnsupportedOperationException e) {
                Log.w(LOG_TAG, "Failed to alias " + alias, e);
            }
        }
        return created;
    }

    // The whole tree is collected before a single alias is created, so the walk never meets one.
    private static void collectMixedCaseEntries(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (!f.getName().equals(f.getName().toLowerCase(Locale.US))) out.add(f);
            if (f.isDirectory()) collectMixedCaseEntries(f, out);
        }
    }

    // -------------------- SHARED --------------------

    /**
     * Give {@code name} inside {@code parent} a lowercase alias beside it. The link target is
     * relative, so the pair survives the tree being moved.
     */
    private static void link(File parent, String name) {
        if (parent == null) return;
        String lower = name.toLowerCase(Locale.US);
        if (lower.equals(name)) return;               // already lowercase, nothing to alias
        if (!new File(parent, name).exists()) return; // the real directory is not there (yet)

        File alias = new File(parent, lower);
        if (Files.isSymbolicLink(alias.toPath())) {
            if (alias.exists()) return; // ours from a previous launch and still resolving
            alias.delete();             // dangling (the target was renamed) - replace it
        } else if (alias.exists()) {
            return;                     // a real file or directory lives there, never touch it
        }

        try {
            Files.createSymbolicLink(alias.toPath(), Paths.get(name));
            Log.i(LOG_TAG, "Lowercase alias created: " + alias.getAbsolutePath() + " -> " + name);
        } catch (IOException | UnsupportedOperationException e) {
            Log.w(LOG_TAG, "Failed to alias " + alias.getAbsolutePath()
                    + " - mods with animations may fail to load", e);
        }
    }

    private static String stripLeadingSlashes(String path) {
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }
}
