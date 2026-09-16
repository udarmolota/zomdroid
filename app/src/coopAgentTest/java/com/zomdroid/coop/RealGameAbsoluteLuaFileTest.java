package com.zomdroid.coop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import zombie.core.IndieFileLoader;

/** Run with the real game jar before the fixture classes on the runtime classpath. */
public final class RealGameAbsoluteLuaFileTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("zomdroid-real-server-lua-");
        Path server = Files.createDirectories(home.resolve("Server"));
        Path file = server.resolve("real_spawnregions.lua");
        Files.write(file, "real-game-ok".getBytes(StandardCharsets.UTF_8));
        System.setProperty("user.home", home.toString());
        try (BufferedReader reader = new BufferedReader(IndieFileLoader.getStreamReader(file.toString(), true))) {
            if (!"real-game-ok".equals(reader.readLine())) throw new AssertionError("Unexpected contents");
        }
        System.out.println("PASS: transformed the real Build 42.20.4 IndieFileLoader bytecode");
    }
}
