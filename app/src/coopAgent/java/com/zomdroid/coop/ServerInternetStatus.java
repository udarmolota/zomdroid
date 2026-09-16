package com.zomdroid.coop;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Atomic side channel: no diagnostic messages on CoopSlave's stdout protocol. */
public final class ServerInternetStatus {
    private ServerInternetStatus() {}
    private static volatile boolean jniUnavailable;

    public static void unavailable() { jniUnavailable = true; }

    public static void discovered(boolean found, Class<?> mapper) {
        publish(jniUnavailable ? "unavailable" : found ? "discovered" : "no_gateway", defaultPort(mapper), "", "");
    }

    public static void mapping(boolean mapped, int port, String protocol, Class<?> mapper) {
        if (!"UDP".equalsIgnoreCase(protocol)) return;
        int serverPort = defaultPort(mapper);
        if (serverPort > 0 && port != serverPort) return;
        String address = "", error = "";
        if (mapped) {
            try {
                Object value = mapper.getMethod("getExternalAddress").invoke(null);
                if (value != null) address = value.toString();
            } catch (Throwable failure) { error = "External address unavailable: " + failure.getClass().getSimpleName(); }
        }
        publish(jniUnavailable ? "unavailable" : mapped ? "mapped" : "mapping_failed", port, address, error);
    }

    private static int defaultPort(Class<?> mapper) {
        try {
            Class<?> optionsClass = Class.forName("zombie.network.ServerOptions", false, mapper.getClassLoader());
            Object options = optionsClass.getField("instance").get(null);
            return ((Number)optionsClass.getMethod("getInteger", String.class).invoke(options, "DefaultPort")).intValue();
        } catch (Throwable ignored) { return 0; }
    }

    private static synchronized void publish(String state, int port, String address, String error) {
        try {
            Properties data = new Properties();
            data.setProperty("state", state);
            data.setProperty("port", Integer.toString(port));
            data.setProperty("externalAddress", address);
            data.setProperty("detail", error);
            data.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
            for (String key : new String[]{"zomdroid.server.internetFile", "zomdroid.server.internetSession"}) {
                String path = System.getProperty(key);
                if (path == null) continue;
                Path target = Paths.get(path), temp = Paths.get(path + ".tmp");
                try (OutputStream out = Files.newOutputStream(temp)) { data.store(out, "Game UPnP result; external reachability not tested"); }
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        } catch (Throwable ignored) { /* Observation must never abort server startup. */ }
    }
}
