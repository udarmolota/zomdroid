package com.zomdroid.steam;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MacosLibrariesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void manifestNamesAreExactAndTraversalIsRejected() {
        assertEquals("libLighting.dylib", MacosLibraries.selectedName(
                "Project Zomboid.app\\Contents\\Java\\libLighting.dylib"));
        assertNull(MacosLibraries.selectedName("Contents/Java/libfmod.dylib"));
        assertNull(MacosLibraries.selectedName("Contents/Java/liblighting.dylib"));
        assertNull(MacosLibraries.selectedName("../libLighting.dylib"));
        assertNull(MacosLibraries.selectedName("/libLighting.dylib"));
        assertNull(MacosLibraries.selectedName("C:/libLighting.dylib"));
    }

    @Test public void failedPublishRestoresWorkingDirectory() throws Exception {
        File game = temp.newFolder("game");
        File current = new File(game, "macos");
        assertTrue(current.mkdir());
        Files.write(new File(current, "old").toPath(), new byte[]{1});
        try {
            MacosLibraries.publish(game, new File(game, ".macos-download-missing"));
            fail("Missing staging directory must fail");
        } catch (java.io.IOException expected) { }
        assertTrue(new File(game, "macos/old").isFile());
    }

    @Test public void publishKeepsPreviousSetAndRecoveryHandlesInterruptedRename() throws Exception {
        File game = temp.newFolder("game");
        File current = new File(game, "macos");
        assertTrue(current.mkdir());
        Files.write(new File(current, "old").toPath(), new byte[]{1});
        File stage = new File(game, ".macos-download-108602-123");
        assertTrue(stage.mkdir());
        Files.write(new File(stage, "new").toPath(), new byte[]{2});
        MacosLibraries.publish(game, stage);
        assertTrue(new File(game, "macos/new").isFile());
        assertTrue(new File(game, ".macos-previous/old").isFile());
        Files.move(new File(game, "macos").toPath(), new File(game, "saved-new").toPath());
        MacosLibraries.recover(game);
        assertTrue(new File(game, "macos/old").isFile());
    }

    @Test public void cannotPublishOutsideInstance() throws Exception {
        File game = temp.newFolder("game");
        File outside = temp.newFolder(".macos-download-outside");
        try { MacosLibraries.publish(game, outside); fail("Must refuse foreign stage"); }
        catch (java.io.IOException expected) { }
        assertTrue(outside.isDirectory());
    }
}
