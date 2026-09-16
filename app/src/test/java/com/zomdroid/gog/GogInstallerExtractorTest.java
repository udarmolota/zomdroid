package com.zomdroid.gog;

import com.zomdroid.game.InstallationPreset;
import com.zomdroid.game.PresetManager;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A GOG Linux installer is a shell script with the game's ZIP appended. Project Zomboid's is over
 * 4 GB, hence ZIP64, and Commons Compress reads a ZIP64 archive behind such a preamble only through
 * {@link PrefixedZip}. These tests build the same shape at toy size, plain and ZIP64.
 */
public class GogInstallerExtractorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final String JSON = "{\"mainClass\":\"zombie.gameStates.MainScreenState\"}";
    private static final String HEAD;

    static {
        StringBuilder head = new StringBuilder("#!/bin/sh\n# This script was generated using Makeself 2.2.0\n"
                + "# The license covering MojoSetup is the zlib license\n");
        while (head.length() < 6000) head.append("# filler line ").append(head.length()).append('\n');
        HEAD = head.append("exit 0\n").toString();
    }

    /** GOG's layout: support files beside the game, GOG bookkeeping inside it, the game in its own folder. */
    private File installer(String name, Zip64Mode mode) throws IOException {
        File zip = temp.newFile(name + ".zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            out.setUseZip64(mode);
            put(out, "data/noarch/support/gog_com.shield", "x");
            put(out, "data/noarch/game/goggame-1207659176.info", "{}");
            put(out, "data/noarch/game/projectzomboid/ProjectZomboid64.json", JSON);
            put(out, "data/noarch/game/projectzomboid/natives/libPZBullet64.so", "ELF-not-really");
            put(out, "data/noarch/game/projectzomboid/media/lua/shared/game/x.lua", "-- lua");
        }
        File sh = temp.newFile(name + ".sh");
        try (OutputStream out = new FileOutputStream(sh)) {
            out.write(HEAD.getBytes(StandardCharsets.ISO_8859_1));
            Files.copy(zip.toPath(), out);
        }
        return sh;
    }

    private static void put(ZipArchiveOutputStream out, String name, String body) throws IOException {
        out.putArchiveEntry(new ZipArchiveEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeArchiveEntry();
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void payloadOffsetIsTheScriptLength_plainAndZip64() throws Exception {
        for (Zip64Mode mode : new Zip64Mode[]{Zip64Mode.Never, Zip64Mode.Always}) {
            File sh = installer("offset-" + mode, mode);
            try (FileChannel ch = FileChannel.open(sh.toPath(), StandardOpenOption.READ)) {
                assertEquals(mode.toString(), HEAD.length(), PrefixedZip.payloadOffset(ch));
            }
            File zip = new File(sh.getParentFile(), "offset-" + mode + ".zip");
            try (FileChannel ch = FileChannel.open(zip.toPath(), StandardOpenOption.READ)) {
                assertEquals(mode.toString(), 0, PrefixedZip.payloadOffset(ch));
            }
        }
    }

    @Test public void sniffsInstallersAndBundles() throws Exception {
        File sh = installer("sniff", Zip64Mode.Never);
        assertTrue(GogInstallerExtractor.isMakeselfInstaller(sh));
        assertFalse(GogInstallerExtractor.isMakeselfInstaller(new File(sh.getParentFile(), "sniff.zip")));
        assertTrue(GogInstallerExtractor.isInstallerBundle(Arrays.asList("projectzomboid_linux.sh")));
        assertTrue(GogInstallerExtractor.isInstallerBundle(Arrays.asList("gog/", "gog/game.SH")));
        assertFalse(GogInstallerExtractor.isInstallerBundle(Arrays.asList("game.sh", "ProjectZomboid64.json")));
        assertFalse(GogInstallerExtractor.isInstallerBundle(Arrays.asList("ProjectZomboid64.json")));
        assertFalse(GogInstallerExtractor.isInstallerBundle(Arrays.<String>asList()));
    }

    @Test public void extractsOnlyTheGamePayload_plainAndZip64() throws Exception {
        for (Zip64Mode mode : new Zip64Mode[]{Zip64Mode.Never, Zip64Mode.Always}) {
            File sh = installer("extract-" + mode, mode);
            File game = temp.newFolder("game-" + mode);
            final int[] reports = {0};
            GogInstallerExtractor.extractInstaller(sh, game, (m, p, max) -> reports[0]++);
            assertEquals(JSON, read(new File(game, "projectzomboid/ProjectZomboid64.json")));
            assertTrue(new File(game, "projectzomboid/natives/libPZBullet64.so").isFile());
            assertTrue(new File(game, "projectzomboid/media/lua/shared/game/x.lua").isFile());
            assertFalse("GOG bookkeeping is skipped", new File(game, "goggame-1207659176.info").exists());
            assertFalse("support/ is outside the game", new File(game, "support").exists());
            assertFalse(new File(game, "data").exists());
            assertEquals("one report per entry", 3, reports[0]);
        }
    }

    @Test public void extractsInstallersOutOfAZip() throws Exception {
        File sh = installer("bundled", Zip64Mode.Always);
        File bundle = temp.newFile("bundle.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bundle)) {
            out.putArchiveEntry(new ZipArchiveEntry("readme.txt/"));   // a folder, ignored
            out.closeArchiveEntry();
            ZipArchiveEntry e = new ZipArchiveEntry("downloads/" + sh.getName());
            out.putArchiveEntry(e);
            Files.copy(sh.toPath(), out);
            out.closeArchiveEntry();
        }
        File game = temp.newFolder("game-bundle");
        File cache = temp.newFolder("cache");
        try (FileInputStream in = new FileInputStream(bundle)) {
            GogInstallerExtractor.extractBundle(in, bundle.length(), game, cache, null);
        }
        assertEquals(JSON, read(new File(game, "projectzomboid/ProjectZomboid64.json")));
        assertEquals("spooled installers are removed", 0, cache.list().length);
    }

    @Test public void aZipWithoutInstallersIsRefused() throws Exception {
        File bundle = temp.newFile("plain.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bundle)) {
            put(out, "ProjectZomboid64.json", JSON);
        }
        try (FileInputStream in = new FileInputStream(bundle)) {
            GogInstallerExtractor.extractBundle(in, bundle.length(), temp.newFolder("g"), temp.newFolder("c"), null);
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("No .sh installer"));
        }
    }

    @Test public void presetRulesMatchTheArchiveIndexAndTheDisk() throws Exception {
        List<String> gog41 = Arrays.asList("data/noarch/game/projectzomboid/ProjectZomboid64.json",
                "data/noarch/game/projectzomboid/media/x.lua");
        assertEquals(PresetManager.PRESET_BUILD_41, PresetManager.detectPresetIndex(gog41));
        assertEquals(PresetManager.PRESET_BUILD_42, PresetManager.detectPresetIndex(
                Arrays.asList("ProjectZomboid64.json", "imgui-java-binding.jar")));
        assertEquals(PresetManager.PRESET_BUILD_42_12, PresetManager.detectPresetIndex(
                Arrays.asList("PZ/android/arm64-v8a/libPZBullet64.so", "PZ/ProjectZomboid64.json")));
        assertEquals(PresetManager.PRESET_BUILD_42_12, PresetManager.detectPresetIndex(
                Arrays.asList("data/noarch/game/projectzomboid/natives/libPZBullet64.so")));

        File sh = installer("preset", Zip64Mode.Never);
        File game = temp.newFolder("game-preset");
        GogInstallerExtractor.extractInstaller(sh, game, null);
        // The installer service drills into projectzomboid/ before asking; ask the drilled folder.
        InstallationPreset p = PresetManager.detectFromGameDir(new File(game, "projectzomboid"));
        assertNotNull(p);
        assertEquals("Build 42.12+", p.name);
        assertEquals("42", p.buildVersion);
    }
}
