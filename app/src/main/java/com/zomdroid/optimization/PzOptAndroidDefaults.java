package com.zomdroid.optimization;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * PZ Optimization's one starting setting on Android. The launcher installs and uninstalls the mod;
 * its settings are the player's. The exception: in the newest builds the foliage-sway patch gives
 * every tile shader a second fragment output even with sway off, and MobileGlues and ZINK_ZFA cannot
 * link it - the tile-depth shaders end up null and the world is black with the mod's own defaults.
 * The switch for that (devSwayNoPatch) is not in the mod's menu, so a player could not turn it off.
 * Builds that predate the sway code (Willing-Upstairs-580's 42.20.4 build) ignore the key.
 *
 * <p>Written to Zomboid/pzopt/options.ini - the file the mod's own Optimizations tab writes - and
 * only when the player has none yet.
 */
public final class PzOptAndroidDefaults {
    private PzOptAndroidDefaults() {}

    private static final String OPTIONS =
            "# Written by Zomdroid when it installed PZ Optimization. Without this line the newest\n"
            + "# builds show a black world on MobileGlues and ZINK_ZFA (foliage sway shader patch).\n"
            + "devSwayNoPatch=true\n";

    /** Writes the starting settings unless the player already has an options.ini. True if written. */
    public static boolean seedIfMissing(File zomboidDir) throws IOException {
        File options = new File(zomboidDir, "pzopt/options.ini");
        if (options.exists()) return false;
        File dir = options.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        try (FileOutputStream out = new FileOutputStream(options)) {
            out.write(OPTIONS.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }
}
