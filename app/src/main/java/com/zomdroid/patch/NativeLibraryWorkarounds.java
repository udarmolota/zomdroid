package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.ElfSymbols;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** Build-specific native-library fallbacks that must also cover already installed instances. */
public final class NativeLibraryWorkarounds {
    private static final String LOG_TAG = NativeLibraryWorkarounds.class.getName();

    /** The export whose supposed absence got the ARM64 Bullet disabled in the first place. */
    private static final String BULLET_MARKER_SYMBOL =
            "Java_zombie_core_physics_Bullet_defineVehicleScript";

    private NativeLibraryWorkarounds() {}

    /**
     * Selects the safe implementation of game libraries after class patches have been applied.
     *
     * PZBullet: the ARM64 build used to be disabled for "missing Bullet.defineVehicleScript()".
     * That was never true - the file exports it, and its 97 JNI entry points match the Linux and
     * macOS builds one for one. The lookups failed because our dlopen cache in linker.c handed out
     * a handle without bionic counting the open, so an unrelated dlclose unmapped the library
     * under the JVM (fixed 2026-09-10). On 42.20+, the build it was verified on in game, the
     * library is therefore switched back on - including on instances an older launcher already
     * disabled. Older 42 builds keep box64 until someone verifies them.
     *
     * Lighting is a real case: its ARM64 build is a stale snapshot that binds updateTorch() by
     * name but implements a pre-cone ABI - flashlights and headlights render as omnidirectional
     * circles instead of cones, and the vision cone is wrong too. (An earlier launcher version
     * stubbed its one missing export and force-ENABLED it; that traded a loud bridge crash for
     * silently wrong lighting. The bridge crash was the signature-cache race in linker.c, fixed
     * separately.)
     *
     * Both decisions are symbol-gated, not name-gated: a file that really lacks the export stays
     * off, and a rebuilt one gets its native speed back with no code change.
     */
    public static void disableIncompleteNativeLibraries(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        Set<String> bulletSymbols = readArmJniSymbols(gameInstance, "libPZBullet64.so");
        if (!gameInstance.isBuild4220Plus()) {
            disable(gameInstance, "libPZBullet64.so", "not verified before 42.20");
        } else if (bulletSymbols != null && bulletSymbols.contains(BULLET_MARKER_SYMBOL)) {
            enable(gameInstance, "libPZBullet64.so");
        } else if (bulletSymbols != null) {
            disable(gameInstance, "libPZBullet64.so", "missing Bullet.defineVehicleScript()");
        }
        // bulletSymbols == null on 42.20+: no file, or the parser could not read it. Neither is
        // proof of anything, so the library is left exactly as it is.

        if (LightingTransmissionPatchApplier.isArmLightingStale(gameInstance)) {
            disable(gameInstance, "libLighting64.so",
                    "stale build: torch/headlight cones ignored, squareSetLightTransmission missing");
        }

        disableArgumentMismatches(gameInstance);
    }

    private static final String ABI_CACHE = ".zomdroid-abi-checked";

    /**
     * Switches off an ARM64 build whose JNI functions take a different number of arguments than
     * the game passes (JniAbiCheck). The export list cannot see this: 42.20's Lighting had an
     * updateTorch() of the right name and the wrong arguments. Only libraries with a Linux twin
     * are judged, so there is always a working fallback through box64. The verdict per file
     * (name, size, date) is cached next to the libraries: the check reads the DWARF once, not on
     * every launch.
     */
    private static void disableArgumentMismatches(GameInstance gameInstance) {
        File game = new File(gameInstance.getGamePath());
        File jar = new File(game, "projectzomboid.jar");
        File armDir = new File(game, "android/arm64-v8a");
        File[] libs = armDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (!jar.isFile() || libs == null) return;

        File cacheFile = new File(armDir, ABI_CACHE);
        java.util.Map<String, String> cache = new java.util.HashMap<>();
        try {
            if (cacheFile.isFile()) {
                for (String line : Files.readAllLines(cacheFile.toPath())) {
                    int tab = line.lastIndexOf('\t');
                    if (tab > 0) cache.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException ignored) {
        }
        boolean changed = false;
        String jarKey = jar.length() + ":" + jar.lastModified();
        for (File lib : libs) {
            if (!new File(game, lib.getName()).isFile()) continue;   // no Linux twin to fall back to
            String key = lib.getName() + ":" + lib.length() + ":" + lib.lastModified() + ":" + jarKey;
            String verdict = cache.get(key);
            if (verdict == null) {
                java.util.List<String> bad = com.zomdroid.JniAbiCheck.mismatches(lib, jar);
                verdict = bad == null ? "unknown" : bad.isEmpty() ? "ok" : String.join("; ", bad);
                cache.put(key, verdict);
                changed = true;
            }
            if (!"ok".equals(verdict) && !"unknown".equals(verdict)) {
                disable(gameInstance, lib.getName(), "JNI arguments differ from the game: " + verdict);
            }
        }
        if (changed) {
            StringBuilder out = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : cache.entrySet())
                out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            try {
                Files.write(cacheFile.toPath(), out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(LOG_TAG, "Cannot store the JNI argument check results", e);
            }
        }
    }

    /** JNI exports of the game's ARM64 build of a library, active or disabled; null if unknown. */
    private static Set<String> readArmJniSymbols(GameInstance gameInstance, String libraryName) {
        File armDir = new File(gameInstance.getGamePath(), "android/arm64-v8a");
        File library = new File(armDir, libraryName);
        if (!library.isFile()) library = new File(armDir, libraryName + ".disabled");
        if (!library.isFile()) return null;
        return ElfSymbols.readExportedJniSymbols(library);
    }

    private static void enable(GameInstance gameInstance, String libraryName) {
        File active = new File(gameInstance.getGamePath(), "android/arm64-v8a/" + libraryName);
        File disabled = new File(active.getParentFile(), active.getName() + ".disabled");
        if (active.isFile() || !disabled.isFile()) return;

        try {
            Files.move(disabled.toPath(), active.toPath());
        } catch (IOException e) {
            // Not fatal: the Linux x86_64 build through box64 is a working equivalent.
            Log.w(LOG_TAG, "Could not re-enable ARM64 " + libraryName
                    + "; the Linux x86_64 library will run through box64", e);
            return;
        }

        Log.i(LOG_TAG, "Re-enabled ARM64 " + libraryName + "; it will load natively");
    }

    private static void disable(GameInstance gameInstance, String libraryName, String reason) {
        File active = new File(gameInstance.getGamePath(), "android/arm64-v8a/" + libraryName);
        if (!active.isFile()) return;

        File disabled = new File(active.getParentFile(), active.getName() + ".disabled");
        try {
            Files.move(active.toPath(), disabled.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to disable incomplete ARM64 "
                    + active.getName(), e);
        }

        Log.w(LOG_TAG, "Disabled incomplete ARM64 " + libraryName + " (" + reason + "); "
                + "the Linux x86_64 library will run through box64");
    }

}
