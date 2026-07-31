package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.ElfSymbols;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Heals instances where an earlier launcher version stubbed LightingJNI.
 *
 * History: the ARM64 libLighting64.so TIS ships lacks squareSetLightTransmission(), so Zomdroid
 * used to replace that native method with a Java no-op and force-ENABLE the ARM64 library. That
 * library then turned out to be stale wholesale, not just missing one export: its updateTorch()
 * binds by name but implements a pre-cone ABI, so flashlights and vehicle headlights rendered as
 * omnidirectional circles around the source instead of directional cones. Verified live
 * 2026-07-31 on a 42.20 instance: with the original class restored and Lighting running emulated
 * (the x86_64 build through box64), the torch cones and the player's vision cone come back
 * correct.
 *
 * The stub is therefore retired. This class now only RESTORES the original LightingJNI.class from
 * the backup the old patch kept, and {@link NativeLibraryWorkarounds} disables the stale ARM64
 * library so the complete Linux build runs through box64 instead (which needs the
 * signature-cache fix in linker.c to load reliably).
 */
public final class LightingTransmissionPatchApplier {
    private static final String LOG_TAG = LightingTransmissionPatchApplier.class.getName();
    private static final String BACKUP_NAME = "LightingJNI.class.zomdroid-4220.bak";

    private LightingTransmissionPatchApplier() {}

    /** Puts the original LightingJNI.class back if a previous launcher version stubbed it. */
    public static void restoreOriginalIfStubbed(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File target = target(gameInstance);
        File backup = new File(target.getParentFile(), BACKUP_NAME);
        if (!backup.isFile()) return; // never stubbed, or already healed

        // Only restore over a class we verifiably stubbed. If the class on disk is NOT ours
        // (e.g. the user reinstalled game files since), the backup may be from an older game
        // version — overwriting a fresh class with it would be worse than leaving both alone.
        if (!isApplied(gameInstance)) return;

        try {
            Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.i(LOG_TAG, "Lighting stub removed: original LightingJNI.class restored from "
                    + BACKUP_NAME);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to restore LightingJNI.class from backup; "
                    + "squareSetLightTransmission stays a Java no-op this run", e);
        }
    }

    /**
     * True when this instance ships the stale ARM64 Lighting build. Detected by its missing
     * squareSetLightTransmission export — the same stale snapshot also mis-implements torch
     * cones, which a symbol check cannot see, so the missing export serves as the marker for
     * the whole build. Checks the .disabled name too so the answer is stable across launches.
     */
    public static boolean isArmLightingStale(GameInstance gameInstance) {
        File armDir = new File(gameInstance.getGamePath(), "android/arm64-v8a");
        File library = new File(armDir, "libLighting64.so");
        if (!library.isFile()) library = new File(armDir, "libLighting64.so.disabled");
        if (!library.isFile()) return false;

        java.util.Set<String> symbols = ElfSymbols.readExportedJniSymbols(library);
        // A parser failure is not proof of staleness — leave the library alone in that case.
        return symbols != null
                && !symbols.contains(LightingTransmissionPatcher.MISSING_JNI_SYMBOL);
    }

    // Detects our stub in the class on disk (the patcher reports alreadyPatched).
    private static boolean isApplied(GameInstance gameInstance) {
        File target = target(gameInstance);
        if (!target.isFile()) return false;
        try {
            LightingTransmissionPatcher.patch(Files.readAllBytes(target.toPath()));
            return LightingTransmissionPatcher.lastResult().alreadyPatched;
        } catch (IOException e) {
            return false;
        }
    }

    private static File target(GameInstance gameInstance) {
        return new File(gameInstance.getGamePath(), "zombie/iso/LightingJNI.class");
    }
}
