package com.zomdroid.patch;

import android.content.Context;
import android.util.Log;

import com.zomdroid.FileUtils;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Gives Project Viewpoint a settings file before its first run.
 *
 * Viewpoint keeps its settings in {@code Zomboid/viewpoint-live.properties} and, while that file
 * does not say {@code onboarding.finished=true}, opens a first-run setup window that freezes on
 * Android. Our asset is the mod's own "Potato" preset written out in full with the setup marked
 * finished, so the mod skips the window and starts on its lightest settings.
 *
 * Runs at every launch so a Viewpoint installed any way at all is covered. A file that already
 * exists is never touched: it is either ours from an earlier launch or the player's own.
 */
public final class ViewpointSettingsSeed {
    private static final String LOG_TAG = ViewpointSettingsSeed.class.getSimpleName();

    private static final String SETTINGS_NAME = "viewpoint-live.properties";
    private static final String ASSET_PATH = "patches/viewpoint-live.properties";
    private static final String MOD_ID_LINE = "id=Viewpoint";
    private static final String[] MOD_INFO_PATHS = {"mod.info", "common/mod.info", "42/mod.info"};

    private ViewpointSettingsSeed() {}

    public static void seed(Context context, GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File profile = new File(gameInstance.getHomePath(), "Zomboid");
        File settings = new File(profile, SETTINGS_NAME);
        if (settings.exists() || !hasViewpoint(new File(profile, "mods"))) return;

        File tmp = new File(profile, SETTINGS_NAME + ".tmp");
        try (InputStream is = context.getAssets().open(ASSET_PATH)) {
            Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), settings.toPath());
            Log.i(LOG_TAG, "Seeded Viewpoint's Potato settings: " + settings.getAbsolutePath());
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.e(LOG_TAG, "Could not seed Viewpoint's settings", e);
        }
    }

    private static boolean hasViewpoint(File modsDir) {
        File[] mods = modsDir.listFiles();
        if (mods == null) return false;
        for (File mod : mods) {
            // Skips the lowercase alias links LowercasePathAliases keeps next to each mod.
            if (!FileUtils.isWalkableDirectory(mod)) continue;
            for (String path : MOD_INFO_PATHS) {
                File info = new File(mod, path);
                if (info.isFile() && declaresViewpoint(info)) return true;
            }
        }
        return false;
    }

    private static boolean declaresViewpoint(File modInfo) {
        try {
            List<String> lines = Files.readAllLines(modInfo.toPath());
            for (String line : lines) {
                if (line.trim().equals(MOD_ID_LINE)) return true;
            }
        } catch (IOException ignored) {
            // An unreadable mod.info is simply not Viewpoint's.
        }
        return false;
    }
}
