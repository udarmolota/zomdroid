package com.zomdroid.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BootstrapTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zomdroid-server-bootstrap-");
        for (String mode : Arrays.asList("clean", "unconfirmed", "failure")) {
            Path dir = Files.createDirectory(root.resolve(mode));
            Files.write(dir.resolve("output"), new byte[0]);
            List<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp"); command.add(System.getProperty("java.class.path"));
            command.add("-Dzomdroid.server.telemetry=" + dir.resolve("telemetry"));
            command.add("-Dzomdroid.server.lifecycle=" + dir.resolve("lifecycle"));
            command.add("-Dzomdroid.server.output=" + dir.resolve("output"));
            command.add("-Dzomdroid.server.failureFile=" + dir.resolve("error"));
            command.add(ServerBootstrap.class.getName()); command.add(mode);
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("process.log").toFile()).start();
            if (!child.waitFor(20, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("Hung " + mode); }
            String lifecycle = new String(Files.readAllBytes(dir.resolve("lifecycle")), "UTF-8");
            if (!lifecycle.equals(mode.equals("clean") ? "clean" : "unconfirmed")) throw new AssertionError(mode + ": " + lifecycle);
            if (mode.equals("clean")) {
                Properties p = new Properties();
                try (java.io.InputStream in = Files.newInputStream(dir.resolve("telemetry"))) { p.load(in); }
                if (!p.getProperty("players").equals("2")) throw new AssertionError("Missing telemetry");
            }
            if (mode.equals("failure") && (child.exitValue() == 0 || !Files.exists(dir.resolve("error"))))
                throw new AssertionError("Exception was not preserved");
        }
        System.out.println("PASS: clean shutdown, unconfirmed exit, startup exception and server telemetry");
    }
}
