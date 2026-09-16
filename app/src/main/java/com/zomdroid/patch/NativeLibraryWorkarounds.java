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
     * Debug builds only: asks the linker to log every JVM lookup against the native Bullet
     * ([bullet-diag] lines in native.log). Nothing is renamed here - whether Bullet runs natively
     * is decided by disableIncompleteNativeLibraries() for every build type.
     */
    public static boolean prepareBulletDiagnostic(GameInstance instance) {
        if (!com.zomdroid.BuildConfig.DEBUG || !instance.isBuild4220Plus()) return false;
        return new File(instance.getGamePath(), "android/arm64-v8a/libPZBullet64.so").isFile();
    }

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
