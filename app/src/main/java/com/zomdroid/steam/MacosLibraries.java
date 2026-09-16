package com.zomdroid.steam;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** On-disk contract shared with the native Mach-O loader. No proprietary files are bundled. */
public final class MacosLibraries {
    /** The three the loader actually runs. Bullet and Clipper come from TIS's own Android ARM64
     *  builds, RakNet stays on its current path (Inna, 2026-09-11), so their dylibs are neither
     *  downloaded nor required. */
    public static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "libLighting.dylib", "libPZPathFind.dylib", "libPZPopMan.dylib")));
    /** Each library's switch under "Libraries in use" (InstanceSettings.isMacosModuleEnabled). */
    public static final Map<String, String> MODULE_KEYS = orderedMap(
            "libLighting.dylib", "lighting", "libPZPathFind.dylib", "pathfind", "libPZPopMan.dylib", "popman");
    /** The JNI name the native loader knows each library by (linker.c, ZOMDROID_MACHO_SKIP). */
    public static final Map<String, String> JNI_NAMES = orderedMap(
            "libLighting.dylib", "Lighting64", "libPZPathFind.dylib", "PZPathFind64", "libPZPopMan.dylib", "PZPopMan64");
    public static final String LINUX_METADATA = "zomdroid-steam.json";
    private MacosLibraries() {}

    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    public static JSONObject readMetadata(File file) {
        try {
            if (!file.isFile() || file.length() > 65536) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    public static String hash(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    /** manifest.json entries of the libraries that are installed and intact: present, listed and
     *  still the size they were verified at. Any subset counts - the loader takes each library on
     *  its own, so one installed library is used even when the other two are not there. */
    public static JSONObject installedEntries(File game) {
        File folder = new File(game, "macos");
        JSONObject entries = readMetadata(new File(folder, "manifest.json")).optJSONObject("files");
        JSONObject result = new JSONObject();
        if (entries == null) return result;
        for (String name : NAMES) {
            JSONObject entry = entries.optJSONObject(name);
            File file = new File(folder, name);
            if (entry == null || !file.isFile() || file.length() != entry.optLong("size", -1)) continue;
            try { result.put(name, entry); } catch (org.json.JSONException ignored) { }
        }
        return result;
    }

    public static Set<String> installed(File game) {
        JSONObject entries = installedEntries(game);
        Set<String> names = new LinkedHashSet<>();
        for (String name : NAMES) if (entries.has(name)) names.add(name);
        return names;
    }

    /** Recover the old complete set if the process died between the two directory renames. */
    public static void recover(File game) throws IOException {
        File current = new File(game, "macos"), previous = new File(game, ".macos-previous");
        if (!current.exists() && previous.isDirectory()) Files.move(previous.toPath(), current.toPath());
    }

    public static void publish(File game, File stage) throws IOException {
        if (!stage.getCanonicalFile().getParentFile().equals(game.getCanonicalFile())
                || !stage.getName().startsWith(".macos-download-") || Files.isSymbolicLink(stage.toPath()))
            throw new IOException("Invalid macOS staging directory");
        recover(game);
        File current = new File(game, "macos"), previous = new File(game, ".macos-previous");
        // Only a backup produced by this flow may be removed; never follow symlinks.
        if (previous.exists()) deleteTree(previous);
        boolean hadOld = current.exists();
        if (hadOld) Files.move(current.toPath(), previous.toPath());
        try {
            Files.move(stage.toPath(), current.toPath());
        } catch (IOException failure) {
            if (hadOld) Files.move(previous.toPath(), current.toPath());
            throw failure;
        }
        // Keep the previous complete set until the next successful download is ready to publish.
    }

    public static String selectedName(String path) {
        if (path == null) return null;
        String rel = path.replace('\\', '/');
        if (rel.startsWith("/") || rel.contains(":")) return null;
        for (String part : rel.split("/")) if (part.equals("..") || part.equals(".")) return null;
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return NAMES.contains(name) ? name : null;
    }

    private static void deleteTree(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list " + file);
            for (File child : children) deleteTree(child);
        }
        Files.delete(file.toPath());
    }
}
