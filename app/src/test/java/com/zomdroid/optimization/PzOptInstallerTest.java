package com.zomdroid.optimization;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PzOptInstallerTest {
    private Path root;
    private Path game;
    private Path state;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("pzopt-installer-test");
        game = Files.createDirectories(root.resolve("game"));
        state = root.resolve("launcher-backups/pzopt");
        write(game.resolve("projectzomboid.jar"), "project-jar");
    }

    @After
    public void tearDown() throws IOException {
        deleteTree(root);
    }

    @Test
    public void installUpdateAndRestorePreserveTheOriginalGame() throws Exception {
        write(game.resolve("zombie/GameWindow.class"), "vanilla");

        Path first = releaseZip(Map.of(
                "zombie/GameWindow.class", bytes("patched-v1"),
                "pzopt/BuildInfo.class", bytes("build-v1")
        ));
        PzOptInstaller.install(first, game, state);

        assertArrayEquals(bytes("patched-v1"), Files.readAllBytes(game.resolve("zombie/GameWindow.class")));
        assertArrayEquals(bytes("build-v1"), Files.readAllBytes(game.resolve("pzopt/BuildInfo.class")));

        Path second = releaseZip(Map.of(
                "zombie/GameWindow.class", bytes("patched-v2"),
                "pzopt/BuildInfo.class", bytes("build-v2"),
                "pzopt/NewClass.class", bytes("new-v2")
        ));
        PzOptInstaller.install(second, game, state);
        PzOptInstaller.restore(game, state);

        assertArrayEquals(bytes("vanilla"), Files.readAllBytes(game.resolve("zombie/GameWindow.class")));
        assertFalse(Files.exists(game.resolve("pzopt/BuildInfo.class")));
        assertFalse(Files.exists(game.resolve("pzopt/NewClass.class")));
        assertFalse(Files.exists(state));
    }

    @Test
    public void restoreAlsoRemovesTheModsOwnClassCache() throws Exception {
        Path zip = releaseZip(Map.of("pzopt/BuildInfo.class", bytes("build")));
        PzOptInstaller.install(zip, game, state);
        write(game.resolve("pzopt/aot/zombie/GameWindow.class"), "cached");

        PzOptInstaller.restore(game, state);

        assertFalse(Files.exists(game.resolve("pzopt")));
        assertTrue(Files.exists(game.resolve("projectzomboid.jar")));
    }

    @Test
    public void updateRemovesFilesDroppedByTheNewRelease() throws Exception {
        Path first = releaseZip(Map.of(
                "pzopt/BuildInfo.class", bytes("build-v1"),
                "pzopt/OldClass.class", bytes("old")
        ));
        PzOptInstaller.install(first, game, state);

        Path second = releaseZip(Map.of(
                "pzopt/BuildInfo.class", bytes("build-v2")
        ));
        PzOptInstaller.install(second, game, state);

        assertFalse(Files.exists(game.resolve("pzopt/OldClass.class")));
        assertArrayEquals(bytes("build-v2"), Files.readAllBytes(game.resolve("pzopt/BuildInfo.class")));
    }

    @Test
    public void rejectsZipTraversalBeforeChangingTheGame() throws Exception {
        write(game.resolve("zombie/GameWindow.class"), "vanilla");
        Path zip = root.resolve("traversal.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "pzopt-files.txt", "pzopt/BuildInfo.class\n../outside.class\n");
            add(out, "pzopt/BuildInfo.class", "build");
            add(out, "../outside.class", "bad");
        }

        assertThrows(IOException.class, () -> PzOptInstaller.install(zip, game, state));
        assertArrayEquals(bytes("vanilla"), Files.readAllBytes(game.resolve("zombie/GameWindow.class")));
        assertFalse(Files.exists(root.resolve("outside.class")));
        assertFalse(Files.exists(state));
    }

    @Test
    public void rejectsArchiveWhoseManifestDoesNotMatchItsFiles() throws Exception {
        Path zip = root.resolve("mismatch.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "pzopt-files.txt", "pzopt/BuildInfo.class\n");
            add(out, "pzopt/BuildInfo.class", "build");
            add(out, "unexpected.class", "unexpected");
        }

        assertThrows(IOException.class, () -> PzOptInstaller.install(zip, game, state));
        assertFalse(Files.exists(game.resolve("pzopt/BuildInfo.class")));
    }

    @Test
    public void refusesToBackupAnUnmanagedExistingPzOptInstallAsVanilla() throws Exception {
        write(game.resolve("pzopt/BuildInfo.class"), "manual-install");
        Path zip = releaseZip(Map.of(
                "pzopt/BuildInfo.class", bytes("new-build")
        ));

        IOException error = assertThrows(IOException.class,
                () -> PzOptInstaller.install(zip, game, state));

        assertTrue(error.getMessage().contains("already installed"));
        assertArrayEquals(bytes("manual-install"), Files.readAllBytes(game.resolve("pzopt/BuildInfo.class")));
        assertFalse(Files.exists(state));
    }

    @Test
    public void rejectsReleaseBuiltForAnotherGameRevision() throws Exception {
        Path zip = releaseZip(Map.of(
                "pzopt/BuildInfo.class", bytes("new-build")
        ));
        write(game.resolve("projectzomboid.jar"), "different-game-revision");

        IOException error = assertThrows(IOException.class,
                () -> PzOptInstaller.install(zip, game, state));

        assertTrue(error.getMessage().contains("different Project Zomboid revision"));
        assertFalse(Files.exists(game.resolve("pzopt/BuildInfo.class")));
        assertFalse(Files.exists(state));
    }

    @Test
    public void installsTheReleaseFromASteamWorkshopItem() throws Exception {
        Path flat = releaseZip(Map.of("pzopt/BuildInfo.class", bytes("build")));
        Path workshop = root.resolve("workshop.zip");
        String prefix = "mods/PZ_Optimization/42/pzopt-classes/";
        try (java.util.zip.ZipFile in = new java.util.zip.ZipFile(flat.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(workshop))) {
            add(out, "mods/PZ_Optimization/42/install.ps1", "pc installer");
            add(out, "mods/PZ_Optimization/42/mod.info", "id=PZ_Optimization");
            for (java.util.Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                add(out, prefix + entry.getName(), in.getInputStream(entry).readAllBytes());
            }
        }

        PzOptInstaller.install(workshop, game, state);

        assertArrayEquals(bytes("build"), Files.readAllBytes(game.resolve("pzopt/BuildInfo.class")));
        assertFalse(Files.exists(game.resolve("install.ps1")));
        assertFalse(Files.exists(game.resolve("mods")));
    }

    private Path releaseZip(Map<String, byte[]> payload) throws IOException {
        Path zip = Files.createTempFile(root, "release", ".zip");
        Map<String, byte[]> entries = new LinkedHashMap<>(payload);
        entries.put("pzopt/build-info.properties", bytes(
                "revision=test-revision\njar.sha256="
                        + sha256(Files.readAllBytes(game.resolve("projectzomboid.jar"))) + "\n"));
        StringBuilder manifest = new StringBuilder();
        for (String name : entries.keySet()) manifest.append(name).append('\n');
        manifest.append("pzopt-files.txt\n");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                add(out, entry.getKey(), entry.getValue());
            }
            add(out, "pzopt-files.txt", manifest.toString());
        }
        return zip;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void add(ZipOutputStream out, String name, String value) throws IOException {
        add(out, name, bytes(value));
    }

    private static void add(ZipOutputStream out, String name, byte[] value) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(value);
        out.closeEntry();
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes(value));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
