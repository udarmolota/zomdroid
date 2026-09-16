package com.zomdroid;

import android.system.Os;
import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

/** Embedded server launcher shared by experimental COOP and the standalone debug probe. */
final class ServerProbeLauncher {
    private static boolean jvmClaimed;
    static synchronized boolean isClaimed() { return jvmClaimed; }
    static void launchDedicated(GameInstance instance, android.content.Context context,
            DedicatedServerProfile profile) throws Exception {
        launch(instance, context, null, null, profile);
    }
    static void launch(GameInstance instance, android.content.Context context) throws Exception {
        launch(instance, context, null, null);
    }
    static void launch(GameInstance instance, android.content.Context context,
            java.util.List<String> coopCommand, File session) throws Exception {
        launch(instance, context, coopCommand, session, null);
    }
    private static void launch(GameInstance instance, android.content.Context context,
            java.util.List<String> coopCommand, File session, DedicatedServerProfile dedicated) throws Exception {
        synchronized (ServerProbeLauncher.class) {
            if (jvmClaimed) throw new IllegalStateException("A server JVM already owns this process");
            jvmClaimed = true;
        }
        if (!android.app.Application.getProcessName().endsWith(":server")) {
            throw new IllegalStateException("Server JVM requires the dedicated :server process");
        }
        File root = dedicated != null ? dedicated.root
                : new File(instance.getHomePath(), coopCommand == null ? "server-probe" : "coop-probe");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create " + root);
        File temp = new File(root, "tmp");
        if (!temp.isDirectory() && !temp.mkdirs()) throw new IOException("Cannot create " + temp);
        File mods = new File(root, "mods");
        if (!mods.isDirectory() && !mods.mkdirs()) throw new IOException("Cannot create " + mods);

        String home = AppStorage.requireSingleton().getHomePath();
        String jre = home + "/" + C.deps.JRE_25;
        if (!new File(jre, "lib/server/libjvm.so").isFile()) jre = home + "/" + C.deps.JRE_ROOT;
        if (!new File(jre, "lib/server/libjvm.so").isFile()) {
            throw new IOException("Install the launcher runtime dependencies first");
        }

        // Same JNI bridge as the client, with the game's x86_64 libraries handled by box64.
        com.zomdroid.patch.ZNetStatisticsPatchApplier.applyIfNeeded(instance);
        com.zomdroid.patch.NativeLibraryWorkarounds.disableIncompleteNativeLibraries(instance);
        com.zomdroid.patch.PathfindingWorkaround.forceJavaPathfinderFor4212Plus(instance, root);
        NativeLibraryEnvironment.applyMacos(instance, root);
        Os.setenv("BOX64_LD_LIBRARY_PATH", instance.getLdLibraryPathForEmulation(), true);
        Os.setenv("BOX64_LOG", "1", true);
        Os.setenv("BOX64_SHOWBT", "1", true);
        Os.setenv("BOX64_DYNAREC_STRONGMEM", "3", true);
        Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", true);
        Os.setenv("ZOMDROID_SERVER_PROBE", "1", true);
        if (session != null) Os.setenv("ZOMDROID_COOP_OUTPUT", new File(session, "stdout").getAbsolutePath(), true);
        Os.setenv("ZOMDROID_CACHE_DIR", temp.getAbsolutePath(), true);
        // initZomdroidWindow only selects the enum; no surface or GL context is created.
        Os.setenv("ZOMDROID_RENDERER", "ZINK_ZFA", true);
        Os.unsetenv("ZOMDROID_VULKAN_DRIVER_NAME");
        System.loadLibrary("zomdroid");
        GameLauncher.initZomdroidWindow();

        ArrayList<String> jvm = instance.getJvmArgsAsList();
        File bootstrap = new File(root, "server-bootstrap.jar");
        try (java.io.InputStream input = context.getAssets().open("server-bootstrap.jar")) {
            java.nio.file.Files.copy(input, bootstrap.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        jvm.replaceAll(arg -> arg.startsWith("-Djava.class.path=")
                ? arg + ":" + bootstrap.getAbsolutePath() : arg);
        File failureFile = new File(root, "server-exception.txt");
        // One run per server process, so an old error cannot be mistaken for this run.
        try (java.io.FileOutputStream reset = new java.io.FileOutputStream(failureFile)) { }
        jvm.add("-Dzomdroid.server.failureFile=" + failureFile.getAbsolutePath());
        if (dedicated != null) {
            jvm.add("-Dzomdroid.server.telemetry=" + new File(root, "dedicated-telemetry.properties").getAbsolutePath());
            jvm.add("-Dzomdroid.server.lifecycle=" + new File(root, "dedicated-state").getAbsolutePath());
            jvm.add("-Dzomdroid.server.output=" + new File(instance.getGamePath(), "server-native.log").getAbsolutePath());
        }
        // Client agents and user renderer/JVM overrides are deliberately absent from this probe.
        jvm.removeIf(arg -> arg.startsWith("-javaagent:") || arg.startsWith("-Xmx")
                || arg.startsWith("-Xms") || arg.startsWith("-Duser.home=")
                || arg.startsWith("-Djava.io.tmpdir="));
        String build = instance.getBuildVersion();
        {
            File guard = new File(root, "coop-agent.jar");
            try (java.io.InputStream input = context.getAssets().open("coop-agent.jar")) {
                java.nio.file.Files.copy(input, guard.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // Only reuse the bundled agent's ASM classes; never run its client premain on the server.
            String asm = home + "/" + C.deps.JARS_ZOMDROID_AGENT;
            jvm.replaceAll(arg -> arg.startsWith("-Djava.class.path=") ? arg + ":" + asm : arg);
            jvm.add("-javaagent:" + guard.getAbsolutePath() + "=server-upnp");
            jvm.add("-Dzomdroid.server.upnpGuard=" + ("41".equals(build) || (build != null && build.startsWith("41."))));
            jvm.add("-Dzomdroid.server.absoluteLuaFiles="
                    + ("42".equals(build) || (build != null && build.startsWith("42."))));
            File internet = new File(root, "hosting-internet.properties");
            // Reset on each launch so a previous successful mapping is never shown as current.
            try (java.io.FileOutputStream reset = new java.io.FileOutputStream(internet)) { }
            jvm.add("-Dzomdroid.server.internetFile=" + internet.getAbsolutePath());
            if (session != null) jvm.add("-Dzomdroid.server.internetSession=" + new File(session, "internet.properties").getAbsolutePath());
            Log.i("ServerProbe", "Server UPnP observation enabled; build=" + build);
        }
        jvm.add("-Xms256m");
        jvm.add("-Xmx1024m");
        if (dedicated != null) {
            jvm.removeIf(arg -> arg.startsWith("-Xmx"));
            jvm.add("-Xmx" + dedicated.heapMb + "m");
        }
        if (coopCommand != null) {
            int mainIndex = coopCommand.indexOf("zombie.network.GameServer");
            jvm.removeIf(arg -> arg.startsWith("-Xmx") || arg.startsWith("-Xms"));
            for (int i = 1; i < mainIndex; i++) {
                String arg = coopCommand.get(i);
                if (arg.startsWith("-Xmx") || arg.startsWith("-Xms") || arg.equals("-Dsoftreset")) jvm.add(arg);
            }
        }
        jvm.add("-Duser.home=" + root.getAbsolutePath());
        jvm.add("-Djava.io.tmpdir=" + temp.getAbsolutePath());
        jvm.add("-Djava.awt.headless=true");
        jvm.add("-Dzomboid.steam=0");
        jvm.add("-XX:ErrorFile=" + root.getAbsolutePath() + "/hs_err_pid%p.log");
        String[] args = {"-cachedir=" + root.getAbsolutePath(), "-servername", "zomdroid-probe",
                "-nosteam", "-ip", "127.0.0.1", "-adminpassword", UUID.randomUUID().toString()};
        if (dedicated != null)
            args = new String[]{"-cachedir=" + root.getAbsolutePath(), "-servername", dedicated.name,
                    "-nosteam", "-ip", "0.0.0.0", "-adminpassword", dedicated.adminPassword};
        if (coopCommand != null) {
            args = coopCommand.subList(coopCommand.indexOf("zombie.network.GameServer") + 1,
                    coopCommand.size()).toArray(new String[0]);
        }
        String libraries = AppStorage.requireSingleton().getLibraryPath() + ":/system/lib64:"
                + jre + "/lib:" + jre + "/lib/server:" + instance.getJavaLibraryPath();
        Log.i("ServerProbe", "Starting zombie/network/GameServer; instance=" + instance.getName()
                + "; runtime=" + jre + "; cache=" + root + "; mode=" + (dedicated != null ? "dedicated" : coopCommand == null ? "loopback probe" : "COOP")
                + "; macOS=" + Os.getenv("ZOMDROID_MACHO_LIBS") + "; skip=" + Os.getenv("ZOMDROID_MACHO_SKIP"));
        GameLauncher.startGame(instance.getGamePath(), libraries, jvm.toArray(new String[0]),
                "com/zomdroid/server/ServerBootstrap", args);
        Log.w("ServerProbe", "Native launch call returned; inspect server-native.log for the outcome");
    }
}
