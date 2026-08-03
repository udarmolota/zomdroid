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

        // Need is decided by reading the class, not by the backup being there: a game updated in
        // place over a patched instance leaves our .bak beside a fresh unpatched class, and a
        // .bak treated as a "done" marker would silence us on exactly that instance.
        File backup = new File(target.getParentFile(), "javafmodJNI.class.zomdroid-4220.bak");

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
            // The patcher has no already-patched flag, so the backup tells the two apart: with
            // one beside it, "no matching calls" means our own output is still in place.
            if (backup.isFile()) return;
            String reason = result.error == null ? "no matching calls" : result.error;
            Log.w(LOG_TAG, "FMOD patch: class left untouched: " + reason);
            return;
        }

        // Replace a stale backup: it must mirror the class that is actually installed now.
        try {
            Files.move(target.toPath(), backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.e(LOG_TAG, "FMOD patch: failed to save original as " + backup.getName(), e);
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
