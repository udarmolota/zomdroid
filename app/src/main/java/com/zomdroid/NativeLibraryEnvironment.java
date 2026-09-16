package com.zomdroid;

import android.system.ErrnoException;
import android.system.Os;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.InstanceSettings;
import com.zomdroid.steam.MacosLibraries;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Same instance switches for the client and its separate server process. */
final class NativeLibraryEnvironment {
    private NativeLibraryEnvironment() {}

    static void applyMacos(GameInstance instance, File cacheDirectory) throws ErrnoException {
        InstanceSettings settings = instance.settings();
        // No switch above the libraries (2026-09-16): what is installed and verified is used, minus
        // what the player turned off under "Libraries in use". Off and not installed both go to
        // ZOMDROID_MACHO_SKIP, and that matters: with the loader on, linker.c sends a PathFind or
        // PopMan whose dylib is missing to box64, past TIS's own ARM64 build.
        Set<String> installed = instance.isBuild4220Plus()
                ? MacosLibraries.installed(new File(instance.getGamePath())) : Collections.emptySet();
        boolean enabled = false;
        StringBuilder skip = new StringBuilder();
        for (Map.Entry<String, String> library : MacosLibraries.JNI_NAMES.entrySet()) {
            if (installed.contains(library.getKey())
                    && settings.isMacosModuleEnabled(MacosLibraries.MODULE_KEYS.get(library.getKey()))) {
                enabled = true;
            } else {
                skip.append(library.getValue()).append(',');
            }
        }
        Os.setenv("ZOMDROID_MACHO_LIBS", enabled ? "1" : "0", true);
        // Individual module switches retain the same loader contract in both processes.
        Os.setenv("ZOMDROID_MACHO_SKIP", skip.toString(), true);
        // Preflight enables this option only after successfully loading macOS PathFind.
        Os.setenv("ZOMDROID_MACHO_PATHFIND_OPTIONS", enabled
                ? new File(cacheDirectory, "debug-options.ini").getAbsolutePath() : "", true);
    }
}
