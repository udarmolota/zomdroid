package com.zomdroid.server;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;

/** Keep the exception before the native JNI caller or process termination can lose it. */
public final class ServerBootstrap {
    public static void main(String[] args) {
        startTelemetry();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record(thread.getName(), error);
            error.printStackTrace();
        });
        try {
            Class.forName("zombie.network.GameServer").getMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getTargetException() : error;
            record("GameServer.main", cause);
            cause.printStackTrace();
            System.exit(1);
        }
    }

    private static void startTelemetry() {
        String path = System.getProperty("zomdroid.server.telemetry");
        if (path == null) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                String output = System.getProperty("zomdroid.server.output");
                String result = "unconfirmed";
                // PZ writes the completion marker immediately before System.exit(). Its logger's
                // shutdown hook may flush in parallel with ours, so a single read can race it and
                // leave a successfully saved server marked as unconfirmed.
                for (int attempt = 0; attempt < 40; attempt++) {
                    try (java.io.RandomAccessFile log = new java.io.RandomAccessFile(output, "r")) {
                        int count = (int)Math.min(log.length(), 65536);
                        log.seek(log.length() - count); byte[] bytes = new byte[count]; log.readFully(bytes);
                        if (new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                                .contains("Shutdown handling finished")) {
                            result = "clean";
                            break;
                        }
                    }
                    if (attempt < 39) Thread.sleep(50);
                }
                java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("zomdroid.server.lifecycle")),
                        result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable ignored) { }
        }, "server-exit-record"));
        Thread sampler = new Thread(() -> {
            for (;;) {
                try {
                    Thread.sleep(5000);
                    java.util.Properties p = new java.util.Properties();
                    Runtime runtime = Runtime.getRuntime();
                    p.setProperty("heapUsedMb", Long.toString((runtime.totalMemory() - runtime.freeMemory()) >> 20));
                    p.setProperty("heapMaxMb", Long.toString(runtime.maxMemory() >> 20));
                    p.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
                    p.setProperty("players", String.valueOf(Class.forName("zombie.network.GameServer")
                            .getMethod("getPlayerCount").invoke(null)));
                    java.nio.file.Path destination = java.nio.file.Paths.get(path);
                    java.nio.file.Path temp = java.nio.file.Paths.get(path + ".tmp");
                    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(temp)) { p.store(out, null); }
                    java.nio.file.Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (InterruptedException e) { return; }
                catch (Throwable ignored) { /* Status collection must never stop a game server. */ }
            }
        }, "server-telemetry");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static synchronized void record(String thread, Throwable error) {
        String path = System.getProperty("zomdroid.server.failureFile");
        if (path == null) return;
        try (FileOutputStream stream = new FileOutputStream(path, true);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(stream, "UTF-8"))) {
            writer.println("=== " + new java.util.Date() + " thread=" + thread + " ===");
            Runtime runtime = Runtime.getRuntime();
            writer.println("heap: used=" + (runtime.totalMemory() - runtime.freeMemory())
                    + " committed=" + runtime.totalMemory() + " max=" + runtime.maxMemory());
            error.printStackTrace(writer);
            writer.flush();
            stream.getFD().sync();
        } catch (Throwable failure) {
            System.err.println("[server-bootstrap] Cannot persist exception: " + failure);
        }
    }
}
