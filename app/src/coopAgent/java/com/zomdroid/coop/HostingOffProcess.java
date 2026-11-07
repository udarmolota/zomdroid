package com.zomdroid.coop;

import java.io.File;
import java.io.IOException;

/** Replaces the server launch while hosting is off: Android refuses the exec it would try anyway. */
public final class HostingOffProcess {
    private HostingOffProcess() { }

    public static Process start(ProcessBuilder builder) throws IOException {
        new File(System.getProperty("zomdroid.coop.hostingOffMarker")).createNewFile();
        throw new IOException("Server hosting is off in the instance settings");
    }
}
