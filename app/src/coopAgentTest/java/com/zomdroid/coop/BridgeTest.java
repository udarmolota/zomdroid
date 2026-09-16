package com.zomdroid.coop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Tests transformation and Process semantics against a simulated Android service. */
public final class BridgeTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("zomdroid-coop-test").toFile();
        System.setProperty("zomdroid.coop.bridge", root.getAbsolutePath());
        CountDownLatch allowOutput = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread android = new Thread(() -> {
            try {
                File session = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (session == null) {
                    File[] dirs = root.listFiles(File::isDirectory);
                    if (dirs != null) for (File dir : dirs) if (new File(dir, "request").exists()) session = dir;
                    if (System.nanoTime() > deadline) throw new AssertionError("No transformed launch request");
                    Thread.sleep(10);
                }
                try (DataInputStream request = new DataInputStream(new FileInputStream(new File(session, "request")))) {
                    if (request.readInt() != 3 || !request.readUTF().equals("jre/bin/java")
                            || !request.readUTF().equals("zombie.network.GameServer")
                            || !request.readUTF().equals("-coop")) throw new AssertionError("Command changed");
                }
                Files.write(new File(session, "accepted").toPath(), new byte[]{49});
                if (!allowOutput.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test timeout");
                Files.write(new File(session, "stdout").toPath(), "status@ready\n".getBytes(StandardCharsets.UTF_8));
                if (!allowExit.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test timeout");
                String commands = new String(Files.readAllBytes(new File(session, "stdin").toPath()), StandardCharsets.UTF_8);
                if (!commands.equals("ping@42\n")) throw new AssertionError("stdin mismatch: " + commands);
                if (!new File(session, "stop").exists()) throw new AssertionError("destroy did not request stop");
                Files.write(new File(session, "exit").toPath(), new byte[]{55});
            } catch (Throwable e) { failure.set(e); }
        }, "fake-android");
        android.setDaemon(true);
        android.start();
        Process process = new zombie.network.CoopMaster().launchServer();
        if (!process.isAlive()) throw new AssertionError("Premature exit");
        try { process.exitValue(); throw new AssertionError("exitValue should throw while running"); }
        catch (IllegalThreadStateException expected) { }
        if (process.waitFor(50, TimeUnit.MILLISECONDS)) throw new AssertionError("Premature wait completion");
        ExecutorService readers = Executors.newSingleThreadExecutor();
        Future<String> line = readers.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine());
        try {
            try { line.get(100, TimeUnit.MILLISECONDS); throw new AssertionError("EOF before server output"); }
            catch (TimeoutException expected) { }
            allowOutput.countDown();
            if (!"status@ready".equals(line.get(3, TimeUnit.SECONDS))) throw new AssertionError("stdout mismatch");
            process.getOutputStream().write("ping@42\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            process.destroy();
            allowExit.countDown();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 7) throw new AssertionError("Exit status mismatch");
            if (process.getInputStream().read() != -1) throw new AssertionError("Missing EOF");
            android.join(1000);
            if (failure.get() != null) throw new AssertionError("Simulated service failed", failure.get());
            System.out.println("PASS: transform, command framing, blocking stdout, stdin, wait, destroy, exit and EOF");
        } finally { readers.shutdownNow(); }
    }
}
