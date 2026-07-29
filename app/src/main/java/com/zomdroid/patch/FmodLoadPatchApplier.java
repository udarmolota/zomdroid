package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** Applies {@link FmodLoadPatcher} to installed Build 42.20+ files. */
public final class FmodLoadPatchApplier {
    private static final String LOG_TAG = FmodLoadPatchApplier.class.getName();

    private FmodLoadPatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!gameInstance.isBuild4220Plus()) return;

        File target = new File(gameInstance.getGamePath(), "fmod/javafmodJNI.class");
        if (!target.isFile()) return;

        File backup = new File(target.getParentFile(), "javafmodJNI.class.zomdroid-4220.bak");
        if (backup.exists()) return;

        byte[] original;
        try {
            original = Files.readAllBytes(target.toPath());
        } catch (IOException e) {
            Log.e(LOG_TAG, "FMOD patch: failed to read " + target.getAbsolutePath(), e);
            return;
        }

        byte[] patched = FmodLoadPatcher.patch(original);
        FmodLoadPatcher.Result result = FmodLoadPatcher.lastResult();
        if (patched == null) {
            String reason = result.error == null ? "no matching calls" : result.error;
            Log.w(LOG_TAG, "FMOD patch: class left untouched: " + reason);
            return;
        }

        if (!target.renameTo(backup)) {
            Log.e(LOG_TAG, "FMOD patch: failed to save original as " + backup.getName());
            return;
        }

        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(patched);
        } catch (IOException e) {
            Log.e(LOG_TAG, "FMOD patch: failed to write patched class", e);
            if (!backup.renameTo(target)) {
                Log.e(LOG_TAG, "FMOD patch: failed to restore original class");
            }
            return;
        }

        Log.i(LOG_TAG, "Build 42.20+ FMOD patch applied: skipped "
                + result.patchedCalls + " redundant Android library load(s)");
    }
}
