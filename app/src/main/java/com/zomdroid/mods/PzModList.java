package com.zomdroid.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Project Zomboid's single-player mod lists, as the game keeps them under {@code Zomboid/}: the list
 * a new world starts with in {@code mods/default.txt}, and each world's own in
 * {@code Saves/<mode>/<world>/mods.txt}. Both read {@code mods { mod = Id, ... }} - the order there
 * is the load order - followed by a {@code maps} block this class never touches. Installed mods come
 * from {@code mods/<folder>} and {@code Workshop/<item>/Contents/mods/<folder>}, each described by
 * its {@code mod.info}. Ported from RimDroid's mods screen (ModList), PZ's files underneath.
 *
 * <p>Multiplayer worlds are left out: their mods come from the server's settings. The game rewrites
 * these files while it runs, so nothing here may write then (the screen checks that).
 *
 * <p>No Android classes: unit-tested on the JVM.
 */
public final class PzModList {
    /** The game's example mod in Workshop/: not a mod anyone installed. */
    private static final String TEMPLATE = "ModTemplate";

    public static final class Mod {
        public final File folder;        // mods/<folder>, or Workshop/<item>/Contents/mods/<folder>
        public final File infoDir;       // where mod.info was read: the folder or a version subfolder
        public final String id, name, author, description;
        public final String versionMin, versionMax;   // "" when not given
        /** Build 42 loads a mod from a 42.x folder, Build 41 from mod.info at the top; false when this
         *  mod has none the instance's build reads, or the game version is outside versionMin/Max. */
        public final boolean fitsBuild;
        /** Only mods in Zomboid/mods (what the launcher installs) are deleted from here. */
        public final boolean deletable;
        // Load-order rules, ids as normalizeId() gives them.
        public final List<String> require = new ArrayList<>(), loadAfter = new ArrayList<>(),
                loadBefore = new ArrayList<>(), incompatible = new ArrayList<>();
        public final String poster;      // file name relative to infoDir, "" when none

        Mod(File folder, File infoDir, Map<String, String> info, boolean fitsBuild, boolean deletable) {
            this.folder = folder;
            this.infoDir = infoDir;
            this.id = normalizeId(info.getOrDefault("id", ""));
            String n = info.getOrDefault("name", "").trim();
            this.name = n.isEmpty() ? id : n;
            this.author = info.getOrDefault("author", "").trim();
            this.description = info.getOrDefault("description", "").trim();
            this.versionMin = info.getOrDefault("versionmin", "").trim();
            this.versionMax = info.getOrDefault("versionmax", "").trim();
            this.poster = info.getOrDefault("poster", "").trim();
            this.fitsBuild = fitsBuild;
            this.deletable = deletable;
            ids(info.get("require"), require);
            ids(info.get("loadmodafter"), loadAfter);
            ids(info.get("loadafter"), loadAfter);
            ids(info.get("loadmodbefore"), loadBefore);
            ids(info.get("loadbefore"), loadBefore);
            ids(info.get("incompatible"), incompatible);
        }

        /** The poster file, or null. */
        public File posterFile() {
            if (poster.isEmpty()) return null;
            File f = new File(infoDir, poster);
            return f.isFile() ? f : null;
        }
    }

    /** A mod list the screen can edit: default.txt or one single-player world's mods.txt. */
    public static final class Target {
        public final File file;
        public final boolean newWorlds;   // default.txt
        public final String mode, world;  // "" for default.txt
        public final long modified;

        Target(File file, boolean newWorlds, String mode, String world, long modified) {
            this.file = file;
            this.newWorlds = newWorlds;
            this.mode = mode;
            this.world = world;
            this.modified = modified;
        }
    }

    private PzModList() {}

    /** "\ModId" (B42 spelling, with an empty Workshop part) and "123\ModId" both mean ModId. */
    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int slash = s.lastIndexOf('\\');
        if (slash >= 0) s = s.substring(slash + 1);
        return s.trim();
    }

    private static void ids(String value, List<String> out) {
        if (value == null) return;
        for (String part : value.split("[,;]")) {
            String id = normalizeId(part);
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
    }

    // ---- what is installed ----

    /**
     * Every installed mod, by name. {@code gameVersion} is "42.21.0"-like ("" when unknown),
     * {@code build42} which loader rules apply.
     */
    public static List<Mod> installed(File zomboidDir, String gameVersion, boolean build42) {
        List<Mod> mods = new ArrayList<>();
        scan(new File(zomboidDir, "mods"), true, gameVersion, build42, mods);
        File[] items = new File(zomboidDir, "Workshop").listFiles();
        if (items != null) {
            Arrays.sort(items);
            for (File item : items) {
                if (item.getName().equals(TEMPLATE)) continue;
                scan(new File(item, "Contents/mods"), false, gameVersion, build42, mods);
            }
        }
        Collections.sort(mods, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return mods;
    }

    private static void scan(File dir, boolean deletable, String gameVersion, boolean build42, List<Mod> out) {
        File[] folders = dir.listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (!folder.isDirectory() || Files.isSymbolicLink(folder.toPath())) continue;
            File versioned = versionFolder(folder, gameVersion, build42 ? 42 : 41);
            File root = new File(folder, "mod.info").isFile() ? folder : null;
            // Build 42 reads only a 42.x folder; Build 41 only mod.info at the top. A mod the build
            // cannot load is still listed (from whatever mod.info it has), marked as not fitting.
            File infoDir;
            boolean fits;
            if (build42) {
                infoDir = versioned != null ? versioned : root != null ? root : anyVersionFolder(folder);
                fits = versioned != null;
            } else {
                infoDir = root != null ? root : versioned != null ? versioned : anyVersionFolder(folder);
                fits = root != null;
            }
            if (infoDir == null) continue;
            Map<String, String> info = readInfo(new File(infoDir, "mod.info"));
            if (info == null || normalizeId(info.get("id")).isEmpty()) continue;
            String min = info.getOrDefault("versionmin", "").trim(), max = info.getOrDefault("versionmax", "").trim();
            boolean inRange = gameVersion.isEmpty()
                    || ((min.isEmpty() || compareVersions(gameVersion, min) >= 0)
                    && (max.isEmpty() || compareVersions(gameVersion, max) <= 0));
            out.add(new Mod(folder, infoDir, info, fits && inRange, deletable));
        }
    }

    /**
     * The version folder the game would pick: the highest "N" or "N.x" folder of this major version
     * not newer than the game, holding a mod.info. Without a known game version, the highest.
     */
    static File versionFolder(File modFolder, String gameVersion, int major) {
        File[] children = modFolder.listFiles();
        if (children == null) return null;
        File best = null;
        for (File c : children) {
            String n = c.getName();
            if (!c.isDirectory() || !n.matches("\\d+(\\.\\d+)*")) continue;
            if (!n.equals(String.valueOf(major)) && !n.startsWith(major + ".")) continue;
            if (!new File(c, "mod.info").isFile()) continue;
            if (!gameVersion.isEmpty() && compareVersions(n, gameVersion) > 0) continue;
            if (best == null || compareVersions(n, best.getName()) > 0) best = c;
        }
        return best;
    }

    private static File anyVersionFolder(File modFolder) {
        File[] children = modFolder.listFiles();
        if (children == null) return null;
        Arrays.sort(children);
        for (File c : children)
            if (c.isDirectory() && new File(c, "mod.info").isFile()) return c;
        return null;
    }

    /** 42 < 42.1 < 42.9 < 42.10 < 42.21.0; non-numbers count as 0. */
    static int compareVersions(String a, String b) {
        String[] pa = a.trim().split("\\."), pb = b.trim().split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? num(pa[i]) : 0, nb = i < pb.length ? num(pb[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** mod.info's key=value lines, keys lower case; null when unreadable. */
    static Map<String, String> readInfo(File file) {
        try {
            Map<String, String> map = new HashMap<>();
            for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\r?\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                if (!map.containsKey(key)) map.put(key, line.substring(eq + 1).trim());
            }
            return map;
        } catch (IOException e) {
            return null;
        }
    }

    /** "42.21.0" from Zomboid/version.txt ("42.21.0 4a0e9546ec"), "" when there is none. */
    public static String gameVersion(File zomboidDir) {
        try {
            String text = new String(Files.readAllBytes(new File(zomboidDir, "version.txt").toPath()),
                    StandardCharsets.UTF_8).trim();
            String first = text.split("\\s+")[0];
            return first.matches("\\d+(\\.\\d+)*") ? first : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** id -> mod, the first folder winning, as for the rows of the active list. */
    public static Map<String, Mod> byId(List<Mod> mods) {
        Map<String, Mod> map = new HashMap<>();
        for (Mod m : mods) if (!map.containsKey(m.id)) map.put(m.id, m);
        return map;
    }

    /** Removes a mod installed in Zomboid/mods. */
    public static boolean delete(Mod mod) {
        if (!mod.deletable) return false;
        return deleteTree(mod.folder);
    }

    private static boolean deleteTree(File f) {
        if (!Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        return f.delete() || !f.exists();
    }

    // ---- the lists ----

    /** default.txt first, then the single-player worlds by mode, newest first within a mode. */
    public static List<Target> targets(File zomboidDir) {
        List<Target> out = new ArrayList<>();
        File def = new File(zomboidDir, "mods/default.txt");
        out.add(new Target(def, true, "", "", def.lastModified()));
        List<Target> worlds = new ArrayList<>();
        File[] modes = new File(zomboidDir, "Saves").listFiles();
        if (modes != null) for (File mode : modes) {
            if (!mode.isDirectory() || mode.getName().equalsIgnoreCase("Multiplayer")) continue;
            File[] saves = mode.listFiles();
            if (saves == null) continue;
            for (File save : saves) {
                File txt = new File(save, "mods.txt");
                if (!save.isDirectory() || !txt.isFile()) continue;
                File played = new File(save, "map_t.bin");
                long when = Math.max(txt.lastModified(), played.isFile() ? played.lastModified() : save.lastModified());
                worlds.add(new Target(txt, false, mode.getName(), save.getName(), when));
            }
        }
        // By mode (alphabetically), newest world first inside it: world folders are named by date.
        Collections.sort(worlds, (a, b) -> {
            int byMode = a.mode.compareToIgnoreCase(b.mode);
            return byMode != 0 ? byMode : b.world.compareToIgnoreCase(a.world);
        });
        out.addAll(worlds);
        return out;
    }

    /** The ids in load order, as normalizeId() gives them; empty for a missing default.txt; null
     *  when the file cannot be read or has no mods block. */
    public static List<String> readOrder(File file) {
        if (!file.isFile()) return new ArrayList<>();
        try {
            Parsed p = parse(file);
            if (p.blockStart < 0) return null;
            return new ArrayList<>(p.raw.keySet());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Replaces the mods block with these ids, in this order. Entries already in the file keep their
     * spelling ("\Id" stays "\Id"); the rest of the file is left as it was. The previous file is
     * kept as mods.txt.bak (default.txt.bak); a missing default.txt is created as the game writes it.
     */
    public static void writeOrder(File file, List<String> ids) throws IOException {
        Parsed p = file.isFile() ? parse(file) : null;
        String nl = p != null ? p.newline : "\n";
        List<String> lines;
        int start, end;
        if (p == null) {
            lines = new ArrayList<>(Arrays.asList("VERSION = 1,", "", "mods", "{", "}", "", "maps", "{", "}", ""));
            start = 3;
            end = 4;
        } else if (p.blockStart < 0) {
            throw new IOException("No mods block in " + file);
        } else {
            lines = p.lines;
            start = p.blockStart;
            end = p.blockEnd;
        }
        List<String> block = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) continue;
            String raw = p != null && p.raw.containsKey(id) ? p.raw.get(id) : id;
            block.add("    mod = " + raw + ",");
        }
        List<String> out = new ArrayList<>(lines.subList(0, start + 1));
        out.addAll(block);
        out.addAll(lines.subList(end, lines.size()));
        String text = String.join(nl, out);
        if (p != null && p.endsWithNewline && !text.endsWith(nl)) text += nl;

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        if (file.isFile()) Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File tmp = new File(file.getPath() + ".zdtmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class Parsed {
        List<String> lines;
        String newline;
        boolean endsWithNewline;
        int blockStart = -1, blockEnd = -1;   // the "{" line and the "}" line of the mods block
        final Map<String, String> raw = new LinkedHashMap<>();   // id -> spelling in the file
    }

    private static Parsed parse(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        Parsed p = new Parsed();
        p.newline = text.contains("\r\n") ? "\r\n" : "\n";
        p.endsWithNewline = text.endsWith("\n");
        p.lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        if (p.endsWithNewline) p.lines.remove(p.lines.size() - 1);
        for (int i = 0; i < p.lines.size(); i++) {
            if (!p.lines.get(i).trim().equalsIgnoreCase("mods")) continue;
            int open = i + 1;
            while (open < p.lines.size() && p.lines.get(open).trim().isEmpty()) open++;
            if (open >= p.lines.size() || !p.lines.get(open).trim().equals("{")) continue;
            for (int j = open + 1; j < p.lines.size(); j++) {
                String line = p.lines.get(j).trim();
                if (line.equals("}")) {
                    p.blockStart = open;
                    p.blockEnd = j;
                    return p;
                }
                if (!line.toLowerCase(Locale.ROOT).startsWith("mod")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String raw = line.substring(eq + 1).trim();
                if (raw.endsWith(",")) raw = raw.substring(0, raw.length() - 1).trim();
                String id = normalizeId(raw);
                if (!id.isEmpty() && !p.raw.containsKey(id)) p.raw.put(id, raw);
            }
            return p;   // unterminated block: treated as unreadable
        }
        return p;
    }
}
