package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.ElfSymbols;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** Applies the Build 42.20 ARM64 Lighting compatibility patch to the unpacked game class. */
public final class LightingTransmissionPatchApplier {
    private static final String LOG_TAG = LightingTransmissionPatchApplier.class.getName();
    private static final String BACKUP_NAME = "LightingJNI.class.zomdroid-4220.bak";

    private LightingTransmissionPatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!gameInstance.isBuild4220Plus()) return;
        if (!requiresPatch(gameInstance)) return;

        File target = target(gameInstance);
        if (!target.isFile()) return;

        File backup = new File(target.getParentFile(), BACKUP_NAME);

        byte[] original;
        try {
            original = Files.readAllBytes(target.toPath());
        } catch (IOException e) {
            Log.e(LOG_TAG, "Lighting patch: failed to read " + target.getAbsolutePath(), e);
            return;
        }

        byte[] patched = LightingTransmissionPatcher.patch(original);
        LightingTransmissionPatcher.Result result = LightingTransmissionPatcher.lastResult();
        if (patched == null) {
            if (result.alreadyPatched) return;
            String reason = result.error == null ? "target native method not found" : result.error;
            Log.w(LOG_TAG, "Lighting patch: class left untouched: " + reason);
            return;
        }

        if (!backup.isFile()) {
            if (!target.renameTo(backup)) {
                Log.e(LOG_TAG, "Lighting patch: failed to save original as " + backup.getName());
                return;
            }
        }

        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(patched);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Lighting patch: failed to write patched class", e);
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(original);
            } catch (IOException restoreError) {
                Log.e(LOG_TAG, "Lighting patch: failed to restore original class", restoreError);
            }
            return;
        }

        Log.i(LOG_TAG, "Build 42.20+ Lighting patch applied: "
                + "squareSetLightTransmission() now uses a Java no-op");
    }

    public static boolean isApplied(GameInstance gameInstance) {
        File target = target(gameInstance);
        if (!target.isFile()) return false;
        try {
            LightingTransmissionPatcher.patch(Files.readAllBytes(target.toPath()));
            return LightingTransmissionPatcher.lastResult().alreadyPatched;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean requiresPatch(GameInstance gameInstance) {
        File armDir = new File(gameInstance.getGamePath(), "android/arm64-v8a");
        File library = new File(armDir, "libLighting64.so");
        if (!library.isFile()) library = new File(armDir, "libLighting64.so.disabled");
        if (!library.isFile()) return false;

        java.util.Set<String> symbols = ElfSymbols.readExportedJniSymbols(library);
        // A parser failure is not permission to alter the game class.
        return symbols != null
                && !symbols.contains(LightingTransmissionPatcher.MISSING_JNI_SYMBOL);
    }

    private static File target(GameInstance gameInstance) {
        return new File(gameInstance.getGamePath(), "zombie/iso/LightingJNI.class");
    }
}
