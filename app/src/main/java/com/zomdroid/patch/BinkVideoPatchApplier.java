package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** Applies the no-Bink fallback to Build 42.20's unpacked LuaManager.GlobalObject class. */
public final class BinkVideoPatchApplier {
    private static final String LOG_TAG = BinkVideoPatchApplier.class.getName();
    private static final String BACKUP_NAME =
            "LuaManager$GlobalObject.class.zomdroid-no-bink.bak";

    private BinkVideoPatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!gameInstance.isBuild4220Plus()) return;

        File target = new File(gameInstance.getGamePath(),
                "zombie/Lua/LuaManager$GlobalObject.class");
        if (!target.isFile()) return;

        byte[] original;
        try {
            original = Files.readAllBytes(target.toPath());
        } catch (IOException e) {
            Log.e(LOG_TAG, "Bink patch: failed to read " + target.getAbsolutePath(), e);
            return;
        }

        byte[] patched = BinkVideoPatcher.patch(original);
        BinkVideoPatcher.Result result = BinkVideoPatcher.lastResult();
        if (patched == null) {
            if (result.alreadyPatched) return;
            String reason = result.error == null ? "target method not found" : result.error;
            Log.w(LOG_TAG, "Bink patch: class left untouched: " + reason);
            return;
        }

        File backup = new File(target.getParentFile(), BACKUP_NAME);
        if (!backup.isFile() && !target.renameTo(backup)) {
            Log.e(LOG_TAG, "Bink patch: failed to save original as " + backup.getName());
            return;
        }

        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(patched);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Bink patch: failed to write patched class", e);
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(original);
            } catch (IOException restoreError) {
                Log.e(LOG_TAG, "Bink patch: failed to restore original class", restoreError);
            }
            return;
        }

        Log.i(LOG_TAG, "Build 42.20+ Bink patch applied: game videos return unavailable");
    }
}
