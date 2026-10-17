package com.zomdroid.optimization;

import androidx.annotation.Nullable;

import com.zomdroid.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mods the Optimization screen installs itself, adapted to Android: imported through Import mods
 * they arrive unadapted (ZBBetterFPS with a jar for the wrong Java, PZ Optimization's loose
 * classes that are not a Lua mod at all). Recognised by the Workshop ID in a downloaded zip's name
 * ("Title_ID.zip"), or by the zip's content: a marker file or the mod.info id / name.
 */
public final class HelperMods {
    private HelperMods() {}

    private static final Pattern WORKSHOP_ID = Pattern.compile("_(\\d{6,})\\.zip$", Pattern.CASE_INSENSITIVE);
    private static final int MOD_INFO_MAX = 64 * 1024;

    /** The Optimization section's title for the mod, by the zip's file name; null when unknown. */
    @Nullable
    public static Integer byFileName(@Nullable String fileName) {
        if (fileName == null) return null;
        Matcher m = WORKSHOP_ID.matcher(fileName);
        if (!m.find()) return null;
        switch (m.group(1)) {
            case "3805285544": return R.string.optimization_group_pzopt;
            case "3622986450": return R.string.optimization_group_zbbetterfps;
            case "3619862853": return R.string.optimization_group_zombiebuddy;
            case "2970823607": return R.string.optimization_group_rlz;
            default: return null;
        }
    }

    /** The same, by reading the zip; stops at the first match. Blocking. */
    @Nullable
    public static Integer byContent(InputStream zipStream) throws IOException {
        ZipInputStream zip = new ZipInputStream(zipStream);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String name = e.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
            String file = name.substring(name.lastIndexOf('/') + 1);
            if (file.equals("pzopt-files.txt") || name.endsWith("pzopt/buildinfo.class"))
                return R.string.optimization_group_pzopt;
            if (file.equals("zbbetterfps.jar")) return R.string.optimization_group_zbbetterfps;
            if (file.equals("mod.info")) {
                Integer found = byModInfo(readLimited(zip));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Nullable
    private static Integer byModInfo(String modInfo) {
        String id = "", title = "";
        for (String line : modInfo.split("\\r?\\n")) {
            String l = line.trim();
            String lower = l.toLowerCase(Locale.ROOT);
            if (lower.startsWith("id=")) id = l.substring(3).trim().toLowerCase(Locale.ROOT);
            else if (lower.startsWith("name=")) title = l.substring(5).trim().toLowerCase(Locale.ROOT);
        }
        String both = id + " " + title;
        if (id.equals("pz_optimization") || both.contains("pz optimization")) return R.string.optimization_group_pzopt;
        if (both.contains("zbbetterfps") || both.contains("zed's better fps")) return R.string.optimization_group_zbbetterfps;
        if (id.equals("zombiebuddy")) return R.string.optimization_group_zombiebuddy;
        if (both.contains("renderlesszombie")) return R.string.optimization_group_rlz;
        if (both.contains("every texture optimized") || id.startsWith("eto_")) return R.string.optimization_group_eto;
        if (both.contains("betterfps")) return R.string.optimization_group_betterfps;
        return null;
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < MOD_INFO_MAX) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
