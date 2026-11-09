package com.zomdroid.mods;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copies the mods of one instance's Zomboid/mods into another's. Only real mod folders are
 * copied - a folder with a mod.info at its root (Build 41) or one level down (Build 42: common/,
 * 42.x/) - so our own scaffolding in mods/ (the lowercase path chain) and loose files stay behind.
 * A mod the target already has is left alone. Symbolic links are not followed or copied; the
 * launch-time repairs recreate what they need in the target.
 */
public final class ModCopier {
    private ModCopier() {}

    public static final class Result {
        public final List<String> copied = new ArrayList<>();
        public final List<String> alreadyThere = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
    }

    public interface Progress {
        void onMod(String name, int index, int total);
    }

    public static Result copyAll(File fromMods, File toMods, Progress progress) {
        Result result = new Result();
        File[] dirs = fromMods.listFiles();
        if (dirs == null) return result;
        Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<File> mods = new ArrayList<>();
        for (File d : dirs) if (isModFolder(d)) mods.add(d);

        for (int i = 0; i < mods.size(); i++) {
            File mod = mods.get(i);
            if (progress != null) progress.onMod(mod.getName(), i + 1, mods.size());
            File dest = new File(toMods, mod.getName());
            if (dest.exists()) {
                result.alreadyThere.add(mod.getName());
                continue;
            }
            File tmp = new File(toMods, "." + mod.getName() + ".copying");
            try {
                deleteTree(tmp.toPath());
                copyTree(mod.toPath(), tmp.toPath());
                Files.move(tmp.toPath(), dest.toPath());
                result.copied.add(mod.getName());
            } catch (IOException e) {
                result.failed.add(mod.getName());
                try { deleteTree(tmp.toPath()); } catch (IOException ignored) { }
            }
        }
        return result;
    }

    /** A real directory (not a link) holding mod.info at its root or in a direct subfolder. */
    static boolean isModFolder(File dir) {
        if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory()) return false;
        if (new File(dir, "mod.info").isFile()) return true;
        File[] subs = dir.listFiles();
        if (subs == null) return false;
        for (File s : subs) {
            if (!Files.isSymbolicLink(s.toPath()) && new File(s, "mod.info").isFile()) return true;
        }
        return false;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;   // links and specials are skipped
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
