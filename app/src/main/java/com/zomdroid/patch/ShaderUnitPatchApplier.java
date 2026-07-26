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
 * Cheap and self-quenching: once the .bak exists (or there is nothing to patch) every later
 * call is a single stat/no-op, so running it on every launch costs nothing.
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
        if (bak.exists()) return; // already patched

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
            Log.w(LOG_TAG, "ShaderUnit patch: nothing to patch (family=" + r.family
                    + ", md5=" + md5 + ") — leaving class untouched");
            return;
        }

        if (!target.renameTo(bak)) {
            Log.e(LOG_TAG, "ShaderUnit patch: failed to save original as .bak");
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
