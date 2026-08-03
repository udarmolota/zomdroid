package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Applies {@link ShaderUnitPatcher} to a game instance's ShaderUnit.class on disk, with a .bak
 * of the original. Called from two places: instance creation (InstallerService) and game launch
 * (GameLauncher) — the launch-time call picks up instances that were created by an older
 * launcher whose md5-table didn't know their game version (e.g. 42.12.2, 42.13–42.18).
 *
 * Whether the work is still needed is decided by reading the class, never by the presence of the
 * .bak: a game updated in place over a patched instance leaves our .bak next to a fresh unpatched
 * class, and a .bak used as a "done" marker would silence us for good on exactly the instance
 * that needs patching again. Re-reading costs one 12 KB file per launch, and the patcher reports
 * nothing to do once its own output is in place.
 */
public final class ShaderUnitPatchApplier {
    private static final String LOG_TAG = ShaderUnitPatchApplier.class.getName();

    private ShaderUnitPatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File target = new File(gameInstance.getGamePath(),
                "zombie/core/opengl/ShaderUnit.class");
        if (!target.exists()) return;

        File bak = new File(target.getParentFile(), "ShaderUnit.class.bak");

        byte[] original;
        String md5; // kept purely as "what we saw in the field" telemetry in the log
        try {
            original = java.nio.file.Files.readAllBytes(target.toPath());
            md5 = md5Hex(target);
        } catch (IOException e) {
            Log.e(LOG_TAG, "ShaderUnit patch: failed to read class", e);
            return;
        }

        byte[] patched = ShaderUnitPatcher.patch(original);
        ShaderUnitPatcher.Result r = ShaderUnitPatcher.lastResult();
        if (patched == null) {
            // With a .bak beside it this is our own output being recognised - the steady state.
            if (bak.isFile()) return;
            // Without one we have never touched this class and cannot: either the flag already
            // ships set, or the layout moved again and the patcher needs a new family. That
            // second case is how a future PZ version would break NG_GL4ES silently, so say it.
            Log.w(LOG_TAG, "ShaderUnit patch: nothing to patch (family=" + r.family
                    + ", field=" + r.fieldName + ", md5=" + md5 + ") — leaving class untouched");
            return;
        }

        // A stale .bak from an earlier install is replaced: it must mirror the class that is
        // actually here now, otherwise restoring it would resurrect a different game version.
        try {
            java.nio.file.Files.move(target.toPath(), bak.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.e(LOG_TAG, "ShaderUnit patch: failed to save original as .bak", e);
            return;
        }

        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(patched);
        } catch (IOException e) {
            Log.e(LOG_TAG, "ShaderUnit patch: failed to write patched class", e);
            bak.renameTo(target);
            return;
        }

        Log.i(LOG_TAG, "ShaderUnit combineShaderSources patched in place: family=" + r.family
                + " sites=" + r.patchedSites + " field=" + r.fieldName + " md5=" + md5);
    }

    private static String md5Hex(File file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) md.update(buf, 0, r);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        }
    }
}
