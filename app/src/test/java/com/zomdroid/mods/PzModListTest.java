package com.zomdroid.mods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PzModListTest {
    private Path zomboid;

    @Before
    public void setUp() throws IOException {
        zomboid = Files.createTempDirectory("pz-mods-test");
    }

    @After
    public void tearDown() throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(zomboid)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // A world's mods.txt as Build 42.21 wrote it on a real phone.
    private static final String SAVE_MODS =
            "VERSION = 1,\n\nmods\n{\n    mod = ZombieBuddy,\n    mod = \\ZombieBuddy_B42.21_TemporaryFix,\n    mod = Viewpoint,\n}\n\nmaps\n{\n    map = Muldraugh, KY,\n}\n";

    @Test
    public void readsAndRewritesOnlyTheModsBlock() throws Exception {
        Path txt = write("Saves/Sandbox/World/mods.txt", SAVE_MODS);
        List<String> order = PzModList.readOrder(txt.toFile());
        assertEquals(Arrays.asList("ZombieBuddy", "ZombieBuddy_B42.21_TemporaryFix", "Viewpoint"), order);

        PzModList.writeOrder(txt.toFile(), Arrays.asList("Viewpoint", "ZombieBuddy_B42.21_TemporaryFix", "NewMod"));
        String text = read(txt);
        assertEquals("VERSION = 1,\n\nmods\n{\n    mod = Viewpoint,\n    mod = \\ZombieBuddy_B42.21_TemporaryFix,\n    mod = NewMod,\n}\n\nmaps\n{\n    map = Muldraugh, KY,\n}\n", text);
        assertEquals(SAVE_MODS, read(zomboid.resolve("Saves/Sandbox/World/mods.txt.bak")));
    }

    @Test
    public void keepsWindowsLineEndings() throws Exception {
        Path txt = write("mods/default.txt", SAVE_MODS.replace("\n", "\r\n"));
        PzModList.writeOrder(txt.toFile(), Arrays.asList("ZombieBuddy"));
        String text = read(txt);
        assertTrue(text.contains("{\r\n    mod = ZombieBuddy,\r\n}\r\n"));
        assertFalse(text.replace("\r\n", "").contains("\n"));
    }

    @Test
    public void createsAMissingDefaultList() throws Exception {
        Path txt = zomboid.resolve("mods/default.txt");
        assertEquals(0, PzModList.readOrder(txt.toFile()).size());
        PzModList.writeOrder(txt.toFile(), Arrays.asList("A"));
        assertEquals(Arrays.asList("A"), PzModList.readOrder(txt.toFile()));
        assertTrue(read(txt).contains("maps\n{\n}"));
    }

    @Test
    public void listsDefaultThenSinglePlayerWorlds() throws Exception {
        write("Saves/Sandbox/Old/mods.txt", SAVE_MODS);
        write("Saves/Multiplayer/Server/mods.txt", SAVE_MODS);
        write("Saves/Survival/NoList/map_t.bin", "x");
        List<PzModList.Target> targets = PzModList.targets(zomboid.toFile());
        assertEquals(2, targets.size());
        assertTrue(targets.get(0).newWorlds);
        assertEquals("Old", targets.get(1).world);
        assertEquals("Sandbox", targets.get(1).mode);
    }

    @Test
    public void picksTheVersionFolderTheGameWouldRead() throws Exception {
        write("mods/Fix/42/mod.info", "id=Fix\nname=Old\n");
        write("mods/Fix/42.21/mod.info", "id=Fix\nname=For 42.21\nrequire=\\ZombieBuddy\nincompatible=Other\n");
        write("mods/Fix/42.30/mod.info", "id=Fix\nname=Future\n");
        write("mods/Legacy/mod.info", "id=Legacy\nname=Build 41 only\n");
        write("mods/Cats/42/mod.info", "id=CompanionCat\nname=CD: Cats\nrequire=CompanionDogs\nversionMin=42.30.0\n");
        write("Workshop/ModTemplate/Contents/mods/ModTemplate/mod.info", "id=ModTemplate\n");
        write("Workshop/123/Contents/mods/Buddy/42/mod.info", "id=ZombieBuddy\nname=ZombieBuddy\nauthor=Zed\n");

        Map<String, PzModList.Mod> mods = PzModList.byId(PzModList.installed(zomboid.toFile(), "42.21.0", true));
        assertEquals(4, mods.size());
        PzModList.Mod fix = mods.get("Fix");
        assertEquals("For 42.21", fix.name);
        assertEquals(Arrays.asList("ZombieBuddy"), fix.require);
        assertEquals(Arrays.asList("Other"), fix.incompatible);
        assertTrue(fix.fitsBuild);
        assertTrue(fix.deletable);
        assertFalse(mods.get("Legacy").fitsBuild);
        assertFalse(mods.get("CompanionCat").fitsBuild);   // versionMin above the game
        assertFalse(mods.get("ZombieBuddy").deletable);    // Workshop
        assertNotNull(mods.get("ZombieBuddy"));

        Map<String, PzModList.Mod> b41 = PzModList.byId(PzModList.installed(zomboid.toFile(), "41.78.16", false));
        assertTrue(b41.get("Legacy").fitsBuild);
        assertFalse(b41.get("Fix").fitsBuild);
    }

    @Test
    public void sortPutsRequirementsFirstAndKeepsTheRest() throws Exception {
        write("mods/A/42/mod.info", "id=A\nrequire=\\C\n");
        write("mods/B/42/mod.info", "id=B\n");
        write("mods/C/42/mod.info", "id=C\nloadModAfter=B\n");
        write("mods/D/42/mod.info", "id=D\nrequire=Missing,E\n");
        write("mods/E/42/mod.info", "id=E\n");
        Map<String, PzModList.Mod> mods = PzModList.byId(PzModList.installed(zomboid.toFile(), "42.21.0", true));

        PzModSorter.Result r = PzModSorter.sort(Arrays.asList("A", "B", "C", "D"), mods);
        assertEquals(Arrays.asList("B", "C", "A", "D"), r.order);
        assertEquals(Arrays.asList("Missing"), r.missing);
        assertEquals("E", r.inactive.get(0).id);

        PzModSorter.Result again = PzModSorter.sort(r.order, mods);
        assertEquals(0, again.moved);
    }

    private Path write(String rel, String text) throws IOException {
        Path p = zomboid.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
