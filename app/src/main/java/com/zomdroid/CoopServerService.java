package com.zomdroid;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.system.Os;
import android.util.Log;
import com.zomdroid.game.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Experimental hosting: one JVM per process, without sticky restart after failure. */
public final class CoopServerService extends Service {
    private static boolean launched;
    static boolean isLaunched() { return launched; }
    private volatile FileDescriptor input;
    private volatile boolean quit;
    private volatile boolean quitSent;
    private File session;
    private final Binder binder = new Binder();

    @Override public void onCreate() {
        super.onCreate();
        AppStorage.init(this);
        LauncherPreferences.init(this);
        GameInstanceManager.init(this);
    }
    @Override public IBinder onBind(Intent intent) {
        if (DedicatedServerService.isRunningInProcess()) return null;
        if (launched) return null;
        launched = true;
        session = new File(intent.getStringExtra("session"));
        String name = intent.getStringExtra("instance");
        new Thread(() -> run(name), "coop-server").start();
        return binder;
    }
    private void run(String name) {
        try {
            GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(name);
            if (instance == null) throw new IOException("COOP instance no longer exists");
            String allowed = new File(instance.getHomePath(), "coop-probe/bridge").getCanonicalPath() + File.separator;
            if (!session.getCanonicalPath().startsWith(allowed)) throw new IOException("Invalid COOP session directory");
            ArrayList<String> command = new ArrayList<>();
            try (DataInputStream in = new DataInputStream(new FileInputStream(new File(session, "request")))) {
                int count = in.readInt();
                if (count < 2 || count > 256) throw new IOException("Invalid COOP command length");
                for (int i = 0; i < count; i++) command.add(in.readUTF());
            }
            if (!command.contains("zombie.network.GameServer") || !command.contains("-coop")) {
                throw new IOException("Expected a GameServer COOP command");
            }
            CoopHostBridge.write(session, "pid", Integer.toString(android.os.Process.myPid()));
            FileDescriptor[] pipe = Os.pipe();
            Os.dup2(pipe[0], 0);
            Os.close(pipe[0]);
            input = pipe[1];
            if (quit) requestQuit();
            new Thread(this::feedCommands, "coop-stdin").start();
            ServerProbeLauncher.launch(instance, this, command, session);
            // GameServer's main normally calls System.exit. Returning is a failed/finished launch;
            // do not strand an Android service while CoopMaster waits for Process.exitValue().
            System.exit(1);
        } catch (Throwable e) {
            Log.e("CoopServer", "Launch failed", e);
            try { CoopHostBridge.write(session, "error", e.toString()); }
            catch (Exception ignored) { }
            System.exit(1);
        }
    }
    private void feedCommands() {
        try (RandomAccessFile commands = new RandomAccessFile(new File(session, "stdin"), "r")) {
            byte[] bytes = new byte[4096];
            for (;;) {
                int count = commands.read(bytes);
                if (count > 0) {
                    int offset = 0;
                    while (offset < count) offset += Os.write(input, bytes, offset, count - offset);
                }
                if (new File(session, "stop").exists()) requestQuit();
                Thread.sleep(50);
            }
        } catch (Exception e) { Log.e("CoopServer", "Command channel failed", e); requestQuit(); }
    }
    private synchronized void requestQuit() {
        quit = true;
        if (quitSent || input == null) return;
        quitSent = true;
        try {
            byte[] bytes = "quit\n".getBytes(StandardCharsets.UTF_8);
            Os.write(input, bytes, 0, bytes.length);
        } catch (Exception e) { Log.e("CoopServer", "Cannot request shutdown", e); }
    }
    @Override public boolean onUnbind(Intent intent) { requestQuit(); return false; }
}
