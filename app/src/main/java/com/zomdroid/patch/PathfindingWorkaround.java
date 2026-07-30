package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Selects PZ's Java pathfinder for Build 42.20+ while its bundled ARM64 PathFind library is being
 * evaluated. The game already owns this fallback; the option only changes which implementation it
 * initializes.
 */
public final class PathfindingWorkaround {
    private static final String LOG_TAG = PathfindingWorkaround.class.getName();
    private static final String OPTION_NAME = "Pathfind.UseNativeCode";
    private static final String OPTION_LINE = OPTION_NAME + "=false";

    private PathfindingWorkaround() {}

    public static void forceJavaPathfinderFor4220(GameInstance gameInstance) {
        if (!gameInstance.isBuild4220Plus()) return;

        File zomboidDir = new File(gameInstance.getHomePath(), "Zomboid");
        File optionsFile = new File(zomboidDir, "debug-options.ini");
        List<String> lines = new ArrayList<>();

        try {
            if (optionsFile.isFile()) {
                lines.addAll(Files.readAllLines(optionsFile.toPath(), StandardCharsets.UTF_8));
            } else {
                lines.add("Version=1");
            }

            boolean found = false;
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int equals = line.indexOf('=');
                if (equals < 0 || !OPTION_NAME.equals(line.substring(0, equals).trim())) continue;

                found = true;
                if (!OPTION_LINE.equals(line.trim())) {
                    lines.set(i, OPTION_LINE);
                    changed = true;
                }
            }
            if (!found) {
                lines.add(OPTION_LINE);
                changed = true;
            }
            if (!changed && optionsFile.isFile()) {
                Log.i(LOG_TAG, "Build 42.20+ Java pathfinder is already selected");
                return;
            }

            if (!zomboidDir.isDirectory() && !zomboidDir.mkdirs()) {
                throw new IOException("Failed to create " + zomboidDir.getAbsolutePath());
            }

            File temporary = new File(zomboidDir, "debug-options.ini.zomdroid.tmp");
            Files.write(temporary.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary.toPath(), optionsFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), optionsFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            Log.i(LOG_TAG, "Build 42.20+ pathfinder switched to the game's Java implementation");
        } catch (IOException | RuntimeException e) {
            Log.e(LOG_TAG, "Failed to select the Build 42.20+ Java pathfinder", e);
        }
    }
}
