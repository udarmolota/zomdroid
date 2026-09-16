package com.zomdroid.steam;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.json.JSONObject;

/** B41 uses the existing native-loader location; preserve other user-installed native files. */
public final class MultiplayerLibraries {
    private MultiplayerLibraries() {}
    public static File directory(File game) { return new File(game, "android/arm64-v8a"); }

    public static boolean hasFiles(File game) {
        for (String name : LibraryPack.B41_MULTIPLAYER.names)
            if (!new File(directory(game), name).isFile()) return false;
        return true;
    }

    public static boolean isReady(File game) {
        JSONObject meta = MacosLibraries.readMetadata(new File(directory(game), LibraryPack.B41_MULTIPLAYER.metadataName));
        JSONObject files = meta.optJSONObject("files");
        if (files == null || !Long.toUnsignedString(LibraryPack.B41_MULTIPLAYER.manifest)
                .equals(meta.optString("manifestGid"))) return false;
        for (String name : LibraryPack.B41_MULTIPLAYER.names) {
            JSONObject entry = files.optJSONObject(name);
            File file = new File(directory(game), name);
            if (entry == null || !file.isFile() || file.length() != entry.optLong("size", -1)) return false;
        }
        return true;
    }

    public static void verifyArm64(File file) throws IOException {
        byte[] header = new byte[20];
        try (java.io.DataInputStream in = new java.io.DataInputStream(Files.newInputStream(file.toPath()))) {
            in.readFully(header);
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1 || header[16] != 3 || header[17] != 0
                || (header[18] & 255) != 183 || header[19] != 0)
            throw new IOException("Not an ARM64 ELF shared library: " + file.getName());
    }

    public static void recover(File game) throws IOException {
        File current = directory(game), previous = new File(game, "android/.zomdroid-mp-previous");
        checkDirectory(game, new File(game, "android"));
        checkDirectory(game, current);
        checkDirectory(game, previous);
        if (!current.exists() && previous.isDirectory()) Files.move(previous.toPath(), current.toPath());
    }

    public static void publish(File game, File stage) throws IOException {
        if (!stage.getCanonicalFile().getParentFile().equals(game.getCanonicalFile())
                || !stage.getName().startsWith(".mp-b41-download-") || Files.isSymbolicLink(stage.toPath()))
            throw new IOException("Invalid MP staging directory");
        if (!stage.isDirectory()) throw new IOException("Missing MP staging directory");
        for (String name : LibraryPack.B41_MULTIPLAYER.names) verifyArm64(new File(stage, name));
        recover(game);
        File current = directory(game), previous = new File(game, "android/.zomdroid-mp-previous");
        Files.createDirectories(current.getParentFile().toPath());
        // A failed earlier merge may have left copies of unrelated native files in this stage.
        // Rebuild those from the current directory, never resurrect a file the user removed.
        File[] staged = stage.listFiles();
        if (staged == null) throw new IOException("Cannot inspect MP staging directory");
        for (File file : staged) {
            if (!LibraryPack.B41_MULTIPLAYER.names.contains(file.getName())
                    && !file.getName().equals(LibraryPack.B41_MULTIPLAYER.metadataName)
                    && !file.getName().startsWith(".zomdroid_complete_")) deleteTree(file);
        }
        // Only the two requested libraries and our metadata are replaced. Other files survive.
        if (current.isDirectory()) {
            File[] files = current.listFiles();
            if (files == null) throw new IOException("Cannot inspect native directory");
            for (File file : files) {
                if (LibraryPack.B41_MULTIPLAYER.names.contains(file.getName())
                        || file.getName().equals(LibraryPack.B41_MULTIPLAYER.metadataName)) continue;
                copyTree(file, new File(stage, file.getName()));
            }
        }
        if (previous.exists()) deleteTree(previous);
        boolean hadOld = current.exists();
        if (hadOld) Files.move(current.toPath(), previous.toPath());
        try { Files.move(stage.toPath(), current.toPath()); }
        catch (IOException failure) {
            if (hadOld) Files.move(previous.toPath(), current.toPath());
            throw failure;
        }
    }

    private static void checkDirectory(File game, File directory) throws IOException {
        if (Files.isSymbolicLink(directory.toPath())
                || !directory.getCanonicalFile().toPath().startsWith(game.getCanonicalFile().toPath()))
            throw new IOException("Unsafe native directory: " + directory);
    }

    private static void copyTree(File source, File target) throws IOException {
        if (source.isDirectory() && !Files.isSymbolicLink(source.toPath())) {
            Files.createDirectories(target.toPath());
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot list " + source);
            for (File child : children) copyTree(child, new File(target, child.getName()));
        } else {
            Files.copy(source.toPath(), target.toPath(), LinkOption.NOFOLLOW_LINKS,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list " + file);
            for (File child : children) deleteTree(child);
        }
        Files.delete(file.toPath());
    }
}
