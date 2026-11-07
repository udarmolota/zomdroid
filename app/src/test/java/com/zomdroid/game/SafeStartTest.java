package com.zomdroid.game;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;

public class SafeStartTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void jar(File jar, String... pairs) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar))) {
            for (int i = 0; i < pairs.length; i += 2) {
                out.putNextEntry(new ZipEntry(pairs[i]));
                out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    @Test
    public void build42ComparesLooseClassesWithTheJar() throws IOException {
        File game = tmp.newFolder("game");
        jar(new File(game, "projectzomboid.jar"),
                "zombie/iso/IsoChunkMap.class", "original",
                "zombie/iso/IsoCell.class", "cell",
                "zombie/core/Core.class", "core");
        write(new File(game, "zombie/iso/IsoChunkMap.class"), "from a mod");   // replaced
        write(new File(game, "zombie/iso/IsoCell.class"), "cell");             // the game's own copy
        write(new File(game, "zombie/iso/Extra.class"), "added");              // not in the jar
        write(new File(game, "zombie/core/Core.class"), "patched by us");      // our patcher
        write(new File(game, "media/lua/Fake.class"), "never walked");         // media is skipped

        assertEquals(Arrays.asList("zombie/iso/Extra.class", "zombie/iso/IsoChunkMap.class"),
                SafeStart.findReplacedFiles(game));
    }

    @Test
    public void build41SeesOnlyBackupsMarkingAReplacement() throws IOException {
        File game = tmp.newFolder("game41");
        write(new File(game, "zombie/iso/IsoChunkMap.class"), "betterfps");
        write(new File(game, "zombie/iso/IsoChunkMap.class.original"), "vanilla");
        write(new File(game, "zombie/iso/IsoCell.class"), "vanilla");
        write(new File(game, "zombie/core/Core.class.original"), "ours");      // our class: ignored
        write(new File(game, "zombie/core/Core.class"), "ours");

        assertEquals(Collections.singletonList("zombie/iso/IsoChunkMap.class"),
                SafeStart.findReplacedFiles(game));
    }
}
