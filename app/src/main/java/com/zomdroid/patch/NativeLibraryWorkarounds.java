package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Build-specific native-library fallbacks that must also cover already installed instances. */
public final class NativeLibraryWorkarounds {
    private static final String LOG_TAG = NativeLibraryWorkarounds.class.getName();

    private NativeLibraryWorkarounds() {}

    /**
     * Selects the safe implementation of game libraries after class patches have been applied.
     *
     * PZBullet remains unusable on ARM64 in 42.20 even though its export list looks complete.
     * Lighting is the same story in a nastier form: its ARM64 build is a stale snapshot that
     * binds updateTorch() by name but implements a pre-cone ABI — flashlights and headlights
     * render as omnidirectional circles instead of cones, and the vision cone is wrong too.
     * (An earlier launcher version stubbed its one missing export and force-ENABLED it; that
     * traded a loud bridge crash for silently wrong lighting. The bridge crash was the
     * signature-cache race in linker.c, fixed separately.)
     */
    public static void disableIncompleteNativeLibraries(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        disable(gameInstance, "libPZBullet64.so",
                "missing Bullet.defineVehicleScript()");
        // Symbol-gated on purpose, not name-gated: the missing squareSetLightTransmission export
        // is the marker of the stale snapshot. If TIS ever ships a rebuilt ARM64 Lighting that
        // has it, the library gets its native speed back automatically, with no code change.
        if (LightingTransmissionPatchApplier.isArmLightingStale(gameInstance)) {
            disable(gameInstance, "libLighting64.so",
                    "stale build: torch/headlight cones ignored, squareSetLightTransmission missing");
        }
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
