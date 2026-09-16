package com.zomdroid.coop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import zombie.core.IndieFileLoader;

public final class AbsoluteLuaFileTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("zomdroid-server-lua-");
        Path server = Files.createDirectories(home.resolve("Server"));
        Path allowed = server.resolve("test_spawnregions.lua");
        Files.write(allowed, "return-ok".getBytes(StandardCharsets.UTF_8));
        Path outside = Files.write(home.resolve("outside.lua"), "must-not-open".getBytes(StandardCharsets.UTF_8));
        System.setProperty("user.home", home.toString());

        try (BufferedReader reader = new BufferedReader(IndieFileLoader.getStreamReader(allowed.toString(), true))) {
            if (!"return-ok".equals(reader.readLine())) throw new AssertionError("Allowed file was not read");
        }
        expectOriginal("relative.lua");
        expectOriginal(outside.toString());
        System.out.println("PASS: B42 absolute server Lua file only; relative/mod/outside paths remain untouched");
    }

    private static void expectOriginal(String path) throws Exception {
        try {
            IndieFileLoader.getStreamReader(path, true);
            throw new AssertionError("Unexpectedly intercepted " + path);
        } catch (FileNotFoundException expected) {
            if (!expected.getMessage().startsWith("fixture-original:")) throw expected;
        }
    }
}
