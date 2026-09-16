package com.zomdroid.coop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Debug transport in the app-private directory, preserving the game's Process contract.
 * Regular files are used to avoid crossing ART/HotSpot JNI object ownership boundaries.
 * Readers block at EOF while the remote process is alive, just like process pipes.
 */
public final class FileProcess extends Process {
    private final File session;
    private final OutputStream commands;
    private final InputStream output;

    private FileProcess(File session) throws IOException {
        this.session = session;
        commands = new FileOutputStream(new File(session, "stdin"), true);
        output = new InputStream() {
            private RandomAccessFile reader;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                if (len == 0) return 0;
                for (;;) {
                    File file = new File(session, "stdout");
                    if (reader == null && file.exists()) reader = new RandomAccessFile(file, "r");
                    if (reader != null) {
                        int count = reader.read(bytes, off, len);
                        if (count >= 0) return count;
                    }
                    if (!isAlive()) return -1;
                    pause();
                }
            }
            @Override public void close() throws IOException { if (reader != null) reader.close(); }
        };
    }

    public static Process start(ProcessBuilder builder) throws IOException {
        String root = System.getProperty("zomdroid.coop.bridge");
        if (root == null) throw new IOException("Android COOP bridge is not configured");
        List<String> command = builder.command();
        if (!command.contains("zombie.network.GameServer") || !command.contains("-coop")) {
            throw new IOException("Only the PZ COOP server can use this bridge");
        }
        File session = new File(root, UUID.randomUUID().toString());
        if (!session.mkdirs()) throw new IOException("Cannot create COOP session " + session);
        FileProcess process = new FileProcess(session);
        File staged = new File(session, "request.tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(staged))) {
            out.writeInt(command.size());
            for (String arg : command) out.writeUTF(arg);
        }
        Files.move(staged.toPath(), new File(session, "request").toPath(), StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!new File(session, "accepted").exists()) {
            if (new File(session, "error").exists()) {
                throw new IOException(new String(Files.readAllBytes(new File(session, "error").toPath()), StandardCharsets.UTF_8));
            }
            if (System.nanoTime() > deadline) {
                process.destroy();
                throw new IOException("Android did not accept the COOP launch within 15 seconds");
            }
            pause();
        }
        return process;
    }

    private static void pause() throws IOException {
        try { Thread.sleep(50); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    @Override public OutputStream getOutputStream() { return commands; }
    @Override public InputStream getInputStream() { return output; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public int exitValue() {
        File exit = new File(session, "exit");
        if (!exit.exists()) throw new IllegalThreadStateException("COOP server is running");
        try { return Integer.parseInt(new String(Files.readAllBytes(exit.toPath()), StandardCharsets.UTF_8).trim()); }
        catch (IOException | NumberFormatException e) { return 1; }
    }
    @Override public boolean isAlive() { return !new File(session, "exit").exists(); }
    @Override public int waitFor() throws InterruptedException {
        while (isAlive()) Thread.sleep(50);
        return exitValue();
    }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (isAlive()) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(50);
        }
        return true;
    }
    @Override public void destroy() {
        try { new File(session, "stop").createNewFile(); }
        catch (IOException e) { throw new IllegalStateException("Cannot stop COOP server", e); }
    }
}
