package com.zomdroid;

import android.system.ErrnoException;
import android.system.Os;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.InstanceSettings;
import com.zomdroid.steam.MacosLibraries;
import java.io.File;

/** Same instance switches for the client and its separate server process. */
final class NativeLibraryEnvironment {
    private NativeLibraryEnvironment() {}

    static void applyMacos(GameInstance instance, File cacheDirectory) throws ErrnoException {
        InstanceSettings settings = instance.settings();
        boolean enabled = settings.isMacosLibrariesEnabled() && instance.isBuild4220Plus()
                && MacosLibraries.isReady(new File(instance.getGamePath()));
        Os.setenv("ZOMDROID_MACHO_LIBS", enabled ? "1" : "0", true);
        StringBuilder skip = new StringBuilder();
        // Individual module switches retain the same loader contract in both processes.
        if (!settings.isMacosModuleEnabled("lighting")) skip.append("Lighting64,");
        if (!settings.isMacosModuleEnabled("pathfind")) skip.append("PZPathFind64,");
        if (!settings.isMacosModuleEnabled("popman")) skip.append("PZPopMan64,");
        Os.setenv("ZOMDROID_MACHO_SKIP", skip.toString(), true);
        // Preflight enables this option only after successfully loading macOS PathFind.
        Os.setenv("ZOMDROID_MACHO_PATHFIND_OPTIONS", enabled
                ? new File(cacheDirectory, "debug-options.ini").getAbsolutePath() : "", true);
    }
}
