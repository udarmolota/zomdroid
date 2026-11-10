package com.zomdroid.mods;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModCopierTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void copiesModFoldersOnlyAndKeepsExistingOnes() throws IOException {
        File from = tmp.newFolder("from");
        File to = tmp.newFolder("to");
        write(new File(from, "B41Mod/mod.info"), "id=B41Mod");
        write(new File(from, "B41Mod/media/lua/a.lua"), "x");
        write(new File(from, "B42Mod/common/mod.info"), "id=B42Mod");
        write(new File(from, "B42Mod/42/media/scripts/s.txt"), "y");
        write(new File(from, "Kept/mod.info"), "new");
        write(new File(from, "data/data/com.zomdroid/stuff.txt"), "scaffolding");   // not a mod
        write(new File(from, "loose.txt"), "not a folder");
        write(new File(to, "Kept/mod.info"), "old");

        ModCopier.Result r = ModCopier.copyAll(from, to, null);

        assertEquals(Arrays.asList("B41Mod", "B42Mod"), r.copied);
        assertEquals(Collections.singletonList("Kept"), r.alreadyThere);
        assertTrue(new File(to, "B42Mod/42/media/scripts/s.txt").isFile());
        assertEquals("old", new String(Files.readAllBytes(new File(to, "Kept/mod.info").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(to, "data").exists());
        assertFalse(new File(to, "loose.txt").exists());
        assertFalse(r.zombieBuddySkipped);
    }

    @Test
    public void leavesZombieBuddyOutAndNamesTheModsThatNeedIt() throws IOException {
        File from = tmp.newFolder("from");
        File to = tmp.newFolder("to");
        write(new File(from, "ZombieBuddy/42/mod.info"), "id=ZombieBuddy");
        write(new File(from, "ZBBetterFPS/42.13/mod.info"), "id=ZBBetterFPS\nrequire=\\ZombieBuddy");
        write(new File(from, "Plain/mod.info"), "id=Plain");

        ModCopier.Result r = ModCopier.copyAll(from, to, null);

        assertTrue(r.zombieBuddySkipped);
        assertFalse(new File(to, "ZombieBuddy").exists());
        assertEquals(Arrays.asList("Plain", "ZBBetterFPS"), r.copied);
        assertEquals(Collections.singletonList("ZBBetterFPS"), r.needZombieBuddy);
    }
}
