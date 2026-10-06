package com.zomdroid.patch;

import android.content.Context;
import android.util.Log;

import com.zomdroid.FileUtils;
import com.zomdroid.game.GameInstance;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Keeps every Build 42 copy of ZBBetterFPS.jar on our patched Java 21 build.
 *
 * Our asset drops {@code Patch_IsoMovingObject_B42_13}: it writes {@code IsoZombie.networkAi},
 * which 42.20 no longer has, so with the mod's "optimize IsoMovingObject" option ticked the game
 * dies with a NoSuchFieldError the first time a zombie separates. The swap used to happen only when
 * the mod was installed from the Optimization screen, so mods installed before 1.4.9, through the
 * Steam downloader or as a plain mod archive kept a jar that still carries the patch. Running at
 * every launch reaches all of them.
 *
 * Idempotent by content: a jar byte-identical to the asset is left alone. The jar we replace is
 * kept as {@code ZBBetterFPS.jar.ver25}, the name the installer has always used.
 */
public final class ZbBetterFpsJarRepair {
    private static final String LOG_TAG = ZbBetterFpsJarRepair.class.getSimpleName();

    private static final String JAR_NAME = "ZBBetterFPS.jar";
    private static final String BACKUP_NAME = "ZBBetterFPS.jar.ver25";
    private static final String ASSET_PATH = "patches/ZBBetterFPS.jar.ver21";

    private ZbBetterFpsJarRepair() {}

    /** Launch-time pass over every mod installed in the instance. */
    public static void repair(Context context, GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File[] mods = new File(gameInstance.getHomePath(), "Zomboid/mods").listFiles();
        if (mods == null) return;
        try {
            byte[] asset = null;
            for (File mod : mods) {
                // Skips the lowercase alias links LowercasePathAliases keeps next to each mod.
                if (!FileUtils.isWalkableDirectory(mod)) continue;
                if (asset == null) asset = readAsset(context);
                replaceJars(mod, asset);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "ZBBetterFPS jar repair failed, leaving the mod as it is", e);
        }
    }

    /**
     * Replaces ZBBetterFPS.jar in the mod's 42.x folders. The 41/ folder has its own compatible
     * jar and the common/ folder carries none.
     */
    public static void replaceJars(Context context, File modDir) throws IOException {
        replaceJars(modDir, readAsset(context));
    }

    private static void replaceJars(File modDir, byte[] asset) throws IOException {
        File[] children = modDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!FileUtils.isWalkableDirectory(child)) continue;
            String name = child.getName();
            if (!name.equals("42") && !name.startsWith("42.")) continue;

            File jar = findFile(child, JAR_NAME);
            if (jar == null) continue;
            if (Arrays.equals(Files.readAllBytes(jar.toPath()), asset)) continue;

            File backup = new File(jar.getParentFile(), BACKUP_NAME);
            File tmp = new File(jar.getParentFile(), JAR_NAME + ".tmp");
            Files.write(tmp.toPath(), asset);
            Files.move(jar.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.i(LOG_TAG, "Replaced with Java 21 jar: " + jar.getAbsolutePath());
        }
    }

    private static File findFile(File dir, String fileName) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(fileName)) return f;
            if (FileUtils.isWalkableDirectory(f)) {
                File found = findFile(f, fileName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static byte[] readAsset(Context context) throws IOException {
        try (InputStream is = context.getAssets().open(ASSET_PATH)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) out.write(buf, 0, len);
            return out.toByteArray();
        }
    }
}
