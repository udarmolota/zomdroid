package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.FileUtils;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The case-sensitivity workaround for Build 42.13+, applied when a mod is installed and again at
 * every game launch.
 *
 * <p>Build 42.13 regressed file lookup on case-sensitive filesystems - 41.78 is fine. It is
 * reported to the Indie Stone ("[42.13] Regression in Build 42.13: Linux filename case-sensitivity
 * issue") and still open, so this is ours to carry. Two mangled lookups were identified, and each
 * got its own mechanism. <b>Only the second is still in use.</b>
 *
 * <ol>
 *   <li><b>Form 1, WITHDRAWN - do not bring it back without reading the next section.</b> The file
 *       is requested in lowercase: "media/scripts/recipes/recipes_ladders.txt" while the mod ships
 *       "recipes_Ladders.txt". This was answered by giving every capitalised entry a lowercase
 *       alias beside it. See {@link #PER_ENTRY_ALIASES_ENABLED}: it is off, and the aliases are
 *       swept from every mod at launch.</li>
 *   <li><b>Form 2, live.</b> The mod's whole absolute path is lowercased and appended to the mods
 *       root, yielding "&lt;mods&gt;/data/user/0/.../zomboid/mods/&lt;mod&gt;/...". No amount of
 *       aliasing inside the mod creates that prefix, so the prefix is materialised and its last
 *       component points back at the real mod. It fixes a really observed FileNotFoundException
 *       and multiplies nothing - one extra route per mod, not per file.</li>
 * </ol>
 *
 * <h3>Why form 1 was withdrawn, and why the multiplayer argument for it does not stand</h3>
 *
 * <p>Aliasing every mixed-case entry <i>including directories</i> makes a file reachable by 2^k
 * paths. It was the cause of the "mods broke on 1.4.8" wave: proven on device, then confirmed by
 * four players across animation, clothing and translation mods. The arithmetic and the evidence
 * are on {@link #PER_ENTRY_ALIASES_ENABLED}.
 *
 * <p>This text used to assert, as fact, that "the multiplayer client check compares the same
 * relative path, so the aliases have to live in the real mod folder - a copy off to the side is why
 * joining a server still failed". <b>That claim is retracted; the dates do not support it.</b> The
 * per-entry symlinks landed in a3edeed (2026-08-02) and the launch-time repair in b39a80a (08-12),
 * while the actual cause of the join failures - the /data/data vs /data/user/0 key mismatch in
 * ActiveFileMap - was only fixed by aec9d84 (08-13). Every "it still failed" observation predates
 * the real fix, so none of them is evidence for form 1. PZ lowercases the relative paths it sends
 * to the server itself; nothing requires a second physical spelling on disk.
 *
 * <p><b>If a genuinely mixed-case mod ever surfaces, fix it in the agent, not on disk:</b> exact
 * lookup first, and only on a miss, and only inside the mod folder, resolve each path component
 * case-insensitively; return the true physical path while the lowercase key stays the map and
 * network key; log an ambiguous "Foo"/"foo" rather than guessing. Any filesystem scheme - including
 * a single fully-lowercase mirror - has a floor of two routes per file, and the only figure ever
 * measured as safe is zero duplicates.
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
 * <h3>Two things that look like more of the same and are not</h3>
 *
 * <p><b>Lowercase aliases for the instance folders</b> ("project zomboid02" beside "Project
 * Zomboid02"). Shipped in b39a80a and removed again: they answered a lookup that does not exist.
 * {@code AdvancedAnimator.buildChecksum} reports {@code couldn't find "<lowercased absolute path>"},
 * which reads like a file it failed to open, but the call behind it is
 * {@code ZomboidFileSystem.getAbsolutePath}, i.e. {@code activeFileMap.get(key.toLowerCase())} - a
 * HashMap lookup that never touches disk. The string is a key, not a path, and it is absolute
 * because the game failed to shorten it: {@code AdvancedAnimator.loadModMedia} builds its base URI
 * from {@code getCanonicalFile()} but enumerates children from the raw path, so when the two
 * spellings of the app data directory differ, {@code URI.relativize} finds no common prefix and
 * hands back its argument untouched. That is fixed where it starts, in
 * {@code AppStorage} - see the note there. The aliases also left a dangling link beside a deleted
 * instance, since deletion removes the instance directory and nothing next to it.
 *
 * <p><b>A lowercase alias for a mod's own directory inside "mods".</b> The game scans that folder
 * one level deep for "&lt;entry&gt;/common/mod.info" and follows symlinks, so it would find every
 * mod twice under two names. Observed mod folder names are already lowercase, so it buys nothing.
 */
public final class LowercasePathAliases {
    private static final String LOG_TAG = LowercasePathAliases.class.getName();

    private LowercasePathAliases() {}

    /**
     * Kill switch for form 1 (the per-entry lowercase aliases). When false, existing aliases are
     * removed from every mod and no new ones are created; form 2 (the doubled path) is untouched.
     *
     * <p>Why it exists: aliasing every mixed-case entry INCLUDING directories makes a file
     * reachable by 2^k paths, k being its mixed-case ancestors - `anims_X|anims_x` × `Bob|bob` ×
     * `Foo.glb|foo.glb` is eight routes to one file. Measured on ZomboRut: 1611 files become 8688
     * reachable paths, and the game's own log agrees, reporting ~7100 self-collisions where 1.4.7
     * reported 49. The suspicion is `media/AnimSets`, which goes from 48 registrations to 8346 -
     * exactly the animation-transition content whose `pickup -> BwdDrag -> BwdDragHead` promote is
     * the one thing observed broken on 1.4.8, on every importer we tried.
     *
     * <p><b>It did prove the cause, and the switch is now the shipped state, not a diagnostic.</b>
     * On her device the ZomboRut scene played again - "bridge done" at t=32 and 8.5 s of animation
     * against 0.05 s - and overrides fell from 14 442 to 147. Four players then confirmed it across
     * three classes of mod: animation, clothing and translation. It also holds regardless of which
     * jassimp is loaded, since that run used the game's own x86_64 importer through box64.
     *
     * <p><b>Do not replace this with a lowercase mirror.</b> Any filesystem scheme needs both
     * spellings to exist, so its floor is two routes per file; the only value measured as safe is
     * zero. And the multiplayer justification that used to be given for keeping aliases inside the
     * mod folder is retracted - see the class javadoc for the dates that refute it.
     */
    public static final boolean PER_ENTRY_ALIASES_ENABLED = false;

    // -------------------- LAUNCH-TIME REPAIR --------------------

    /**
     * Bring an instance's mod aliases up to date with where it actually lives right now. Safe to
     * call on every launch: existing links are left alone, and an instance can be created, renamed
     * or copied between launches without anything else noticing.
     */
    public static void repair(GameInstance gameInstance) {
        File instanceDir = new File(gameInstance.getHomePath());
        if (!instanceDir.isDirectory()) return;

        removeInstanceAliases(instanceDir);
        File installed = new File(instanceDir, "Zomboid/mods");
        repairInstalledMods(installed);

        // Hosting reaches the same mods through the coop-probe/mods link (HostingProfileMods), and
        // the game lowercases the path it sees - coop-probe/mods/<mod> - so that spelling needs a
        // chain of its own. It lives in the shared folder under the same scaffolding directory.
        File hostingMods = new File(instanceDir, "coop-probe/mods");
        if (Files.isSymbolicLink(hostingMods.toPath())) repairProfileView(installed, hostingMods);
    }

    /** Form 2 for every installed mod as seen through another profile's link to the folder. */
    private static void repairProfileView(File modsDir, File seenModsDir) {
        File[] entries = modsDir.listFiles();
        if (entries == null) return;
        String scaffolding = doubledRootName(modsDir);
        for (File modDir : entries) {
            if (!modDir.isDirectory()) continue;
            if (Files.isSymbolicLink(modDir.toPath())) continue;
            if (modDir.getName().equals(scaffolding)) continue;
            linkDoubledPath(modDir, new File(seenModsDir, modDir.getName()), seenModsDir);
        }
    }

    /**
     * Drop the instance-level aliases b39a80a created. The whole instances root is swept, not just
     * this instance: deleting an instance removes its directory and nothing beside it, so an alias
     * whose instance is already gone survives as a dangling link wearing the instance's name - which
     * reads, in a file manager, as an instance that refused to delete.
     *
     * <p>Recognised precisely - a symlink whose relative target is its own name in a different case
     * - so nothing else can be caught by this. The sweep can go once b39a80a-era builds are out of
     * circulation.
     */
    private static void removeInstanceAliases(File instanceDir) {
        File instancesRoot = instanceDir.getParentFile();
        if (instancesRoot != null) {
            File[] entries = instancesRoot.listFiles();
            if (entries != null) for (File entry : entries) unlinkIfLowercaseAlias(entry);
        }
        unlinkIfLowercaseAlias(new File(instanceDir, "zomboid"));
    }

    /** @return true if this really was one of our aliases and it is now gone. */
    private static boolean unlinkIfLowercaseAlias(File alias) {
        Path path = alias.toPath();
        try {
            if (!Files.isSymbolicLink(path)) return false;
            // Target is relative and differs from the link name only in case - i.e. ours. Read
            // rather than resolved, so a link whose instance is gone is still recognised.
            String target = Files.readSymbolicLink(path).toString();
            if (!target.toLowerCase(Locale.US).equals(alias.getName())) return false;
            if (target.equals(alias.getName())) return false;
            Files.delete(path);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            Log.w(LOG_TAG, "Failed to remove obsolete alias " + path, e);
            return false;
        }
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
        linkDoubledPath(modDir, modDir, modsDir);

        // Negative means the kill switch is off and that many stale aliases were swept instead.
        if (aliases > 0) Log.i(LOG_TAG, "Case workaround for " + modDir.getName() + ": " + aliases + " alias(es)");
        else if (aliases < 0) Log.i(LOG_TAG, "Case workaround DISABLED for " + modDir.getName()
                + ": removed " + (-aliases) + " per-entry alias(es)");
    }

    /**
     * Materialise "&lt;seen mods root&gt;/&lt;lowercased absolute path of seenModDir&gt;" as a link to
     * the real mod. seenModDir is the mod as the game addresses it: the real folder for normal
     * play, or the same folder through coop-probe/mods for hosting.
     */
    private static void linkDoubledPath(File modDir, File seenModDir, File seenModsDir) {
        String doubled = stripLeadingSlashes(seenModDir.getAbsolutePath().toLowerCase(Locale.US));
        File modLink = new File(seenModsDir, doubled);
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
    }

    /** Give every entry whose name is not already lowercase a lowercase alias beside it. */
    private static int createLowercaseAliases(File root) {
        if (!PER_ENTRY_ALIASES_ENABLED) return -removeLowercaseAliases(root);

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

    /**
     * Remove the form-1 aliases from a mod, returning how many went. Walks the real tree only:
     * a directory alias is deleted without ever being descended into, so the 2^k duplication that
     * created the mess is not re-walked while cleaning it up. Recognised by the same precise shape
     * as {@link #unlinkIfLowercaseAlias} - a symlink whose relative target is its own name in a
     * different case - so a mod's own symlinks, if any, are never touched.
     */
    private static int removeLowercaseAliases(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;

        int removed = 0;
        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath())) {
                if (unlinkIfLowercaseAlias(f)) removed++;
                continue; // never descend into a link, ours or the mod's own
            }
            if (f.isDirectory()) removed += removeLowercaseAliases(f);
        }
        return removed;
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

    private static String stripLeadingSlashes(String path) {
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }
}
