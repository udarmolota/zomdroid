package com.zomdroid.steam;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MultiplayerLibrariesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private byte[] arm64() {
        byte[] bytes = new byte[64];
        bytes[0] = 0x7f; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F';
        bytes[4] = 2; bytes[5] = 1; bytes[16] = 3; bytes[18] = (byte)183;
        return bytes;
    }

    private File stage(File game) throws Exception {
        File stage = new File(game, ".mp-b41-download-test");
        assertTrue(stage.mkdir());
        for (String name : LibraryPack.B41_MULTIPLAYER.names)
            Files.write(new File(stage, name).toPath(), arm64());
        return stage;
    }

    @Test public void sourceIsPinnedAndDesktopTwinsAreExcluded() {
        LibraryPack pack = LibraryPack.B41_MULTIPLAYER;
        assertEquals(108603, pack.depot);
        assertEquals(249541819024555413L, pack.manifest);
        assertEquals("libRakNet64.so", pack.selectedName("android/arm64-v8a/libRakNet64.so"));
        assertEquals("libZNetNoSteam64.so", pack.selectedName("projectzomboid\\android\\arm64-v8a\\libZNetNoSteam64.so"));
        assertNull(pack.selectedName("libRakNet64.so"));
        assertNull(pack.selectedName("projectzomboid/libRakNet64.so"));
        assertNull(pack.selectedName("android/arm64-v8a/../libRakNet64.so"));
        assertNull(pack.selectedName("/android/arm64-v8a/libRakNet64.so"));
        assertNull(pack.selectedName("android/arm64-v8a/libLighting64.so"));
    }

    @Test public void publishPreservesOtherLibrariesAndBacksUpReplacedFiles() throws Exception {
        File game = temp.newFolder("game");
        File current = MultiplayerLibraries.directory(game);
        assertTrue(current.mkdirs());
        Files.write(new File(current, "libOther.so").toPath(), new byte[]{7});
        Files.write(new File(current, "libRakNet64.so").toPath(), new byte[]{8});
        File stage = stage(game);
        Files.write(new File(stage, "stale.so").toPath(), new byte[]{9});
        MultiplayerLibraries.publish(game, stage);
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(new File(current, "libOther.so").toPath()));
        assertArrayEquals(arm64(), Files.readAllBytes(new File(current, "libRakNet64.so").toPath()));
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(new File(game, "android/.zomdroid-mp-previous/libRakNet64.so").toPath()));
        assertFalse(new File(current, "stale.so").exists());
    }

    @Test public void invalidArchitectureCannotReplaceWorkingSet() throws Exception {
        File game = temp.newFolder("game");
        File current = MultiplayerLibraries.directory(game);
        assertTrue(current.mkdirs());
        Files.write(new File(current, "libRakNet64.so").toPath(), new byte[]{1});
        File stage = stage(game);
        byte[] x86 = arm64(); x86[18] = 62;
        Files.write(new File(stage, "libRakNet64.so").toPath(), x86);
        try { MultiplayerLibraries.publish(game, stage); fail("Must reject x86_64"); }
        catch (java.io.IOException expected) { }
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(current, "libRakNet64.so").toPath()));
    }

    @Test public void interruptedRenameRecoversPreviousDirectory() throws Exception {
        File game = temp.newFolder("game");
        File previous = new File(game, "android/.zomdroid-mp-previous");
        assertTrue(previous.mkdirs());
        Files.write(new File(previous, "old").toPath(), new byte[]{1});
        MultiplayerLibraries.recover(game);
        assertTrue(new File(MultiplayerLibraries.directory(game), "old").isFile());
    }

    @Test public void outsideStageIsRejected() throws Exception {
        File game = temp.newFolder("game"), outside = temp.newFolder(".mp-b41-download-outside");
        try { MultiplayerLibraries.publish(game, outside); fail("Must reject foreign stage"); }
        catch (java.io.IOException expected) { }
        assertTrue(outside.isDirectory());
    }
}
