package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Takes a Build 41 BetterFPS IsoChunkMap.class back out of a Build 42 game.
 *
 * BetterFPS (Workshop 3022543997) is a Build 41 mod: the Optimization screen copies its
 * IsoChunkMap.class into game/zombie/iso, where it shadows the class in projectzomboid.jar. On
 * Build 42 that class dies in its static initializer with a NoSuchFieldError on
 * IsoFloorBloodSplat.FloorBloodTypes (42.x calls the field FLOOR_BLOOD_TYPES), so the game never
 * starts. Players installed it before the screen was limited to Build 41 instances, or put it in
 * by hand.
 *
 * Only a class that references FloorBloodTypes is touched, so loose classes from other mods are
 * left alone. The installer's IsoChunkMap.class.original is restored when it is a Build 42 class;
 * otherwise the jar's own IsoChunkMap.class is written over it (never just deleted - see repair).
 */
public final class BetterFpsClassRepair {
    private static final String LOG_TAG = BetterFpsClassRepair.class.getSimpleName();

    private static final String CLASS_NAME = "IsoChunkMap.class";
    private static final String BACKUP_NAME = "IsoChunkMap.class.original";
    /** The Build 41 field name; a Build 42 IsoChunkMap never references it. */
    private static final byte[] B41_MARKER = "FloorBloodTypes".getBytes(StandardCharsets.US_ASCII);

    private BetterFpsClassRepair() {}

    public static void repair(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File dir = new File(gameInstance.getGamePath(), "zombie/iso");
        File target = new File(dir, CLASS_NAME);
        try {
            if (!target.isFile() || !isBuild41Class(target)) return;

            File backup = new File(dir, BACKUP_NAME);
            if (backup.isFile() && !isBuild41Class(backup)) {
                Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Log.i(LOG_TAG, "Build 41 BetterFPS IsoChunkMap replaced by " + BACKUP_NAME);
            } else if (copyFromJar(new File(gameInstance.getGamePath(), "projectzomboid.jar"), target)) {
                // Not deleted: a class missing from the folder would load from the jar, and with a
                // signed jar (42.22+) one zombie.iso class from the jar beside the unpacked rest
                // is refused by the JVM ("signer information does not match").
                Log.i(LOG_TAG, "Build 41 BetterFPS IsoChunkMap replaced by the game's own class from the jar");
            } else {
                Log.w(LOG_TAG, "Build 41 BetterFPS IsoChunkMap found but the game's own class is not in the jar; left in place");
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "BetterFPS class repair failed, leaving the game as it is", e);
        }
    }

    /** Writes the jar's zombie/iso/IsoChunkMap.class over target; false when the jar lacks it. */
    private static boolean copyFromJar(File jar, File target) throws IOException {
        if (!jar.isFile()) return false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            java.util.zip.ZipEntry entry = zip.getEntry("zombie/iso/" + CLASS_NAME);
            if (entry == null) return false;
            File tmp = new File(target.getPath() + ".tmp");
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }

    private static boolean isBuild41Class(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        outer:
        for (int i = 0; i <= data.length - B41_MARKER.length; i++) {
            for (int j = 0; j < B41_MARKER.length; j++) {
                if (data[i + j] != B41_MARKER[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
