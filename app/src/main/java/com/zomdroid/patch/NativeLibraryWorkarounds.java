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
     * Lighting is the opposite: its ARM64 build only misses one method that Zomdroid patches to a
     * Java no-op, while the x86_64 library crashes in getVisibleRooms() through the JNI bridge.
     */
    public static void disableIncompleteNativeLibraries(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        disable(gameInstance, "libPZBullet64.so",
                "missing Bullet.defineVehicleScript()");
        if (gameInstance.isBuild4220Plus()
                && LightingTransmissionPatchApplier.isApplied(gameInstance)) {
            enable(gameInstance, "libLighting64.so",
                    "squareSetLightTransmission() is patched in Java");
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

    private static void enable(GameInstance gameInstance, String libraryName, String reason) {
        File active = new File(gameInstance.getGamePath(), "android/arm64-v8a/" + libraryName);
        if (active.isFile()) return;

        File disabled = new File(active.getParentFile(), active.getName() + ".disabled");
        if (!disabled.isFile()) return;

        try {
            Files.move(disabled.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to enable patched ARM64 "
                    + active.getName(), e);
        }

        Log.i(LOG_TAG, "Enabled ARM64 " + libraryName + " (" + reason + ")");
    }
}
