package com.zomdroid;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.*;
import android.os.*;
import android.util.Log;
import com.zomdroid.game.GameInstance;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Opt-in experimental adapter between a HotSpot Process and an Android bound service. */
public final class CoopHostBridge implements AutoCloseable {
    private final Activity activity;
    private final GameInstance instance;
    private final File root;
    private final CoopInternetView internetView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile Session active;

    public static CoopHostBridge start(Activity activity, GameInstance instance) throws IOException {
        return new CoopHostBridge(activity, instance);
    }
    private CoopHostBridge(Activity activity, GameInstance instance) throws IOException {
        this.activity = activity;
        this.instance = instance;
        internetView = new CoopInternetView(activity);
        root = new File(instance.getHomePath(), "coop-probe/bridge/" + UUID.randomUUID());
        if (!root.mkdirs()) throw new IOException("Cannot create COOP bridge directory");
        File agent = new File(root, "coop-agent.jar");
        try (InputStream input = activity.getAssets().open("coop-agent.jar")) {
            Files.copy(input, agent.toPath());
        }
        watcher.scheduleWithFixedDelay(this::poll, 0, 100, TimeUnit.MILLISECONDS);
    }
    public String getPath() { return root.getAbsolutePath(); }

    private void poll() {
        if (closed) return;
        try {
            Session running = active;
            if (running != null) running.pollInternet();
            File[] sessions = root.listFiles(File::isDirectory);
            if (sessions == null) return;
            for (File dir : sessions) {
                if (!new File(dir, "request").isFile() || new File(dir, "claimed").exists()) continue;
                if (!new File(dir, "claimed").createNewFile()) continue;
                if (active != null) {
                    write(dir, "error", "Another COOP server is still running or shutting down");
                    write(dir, "exit", "1");
                    continue;
                }
                Session session = new Session(dir);
                active = session;
                main.post(() -> session.bind());
            }
        } catch (Exception e) { Log.e("CoopHostBridge", "Request watcher failed", e); }
    }
    static void write(File dir, String name, String text) throws IOException {
        File temp = new File(dir, name + ".tmp");
        Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private final class Session implements ServiceConnection {
        final File dir;
        boolean bound;
        long nextInternetPoll;
        String lastInternetState = "";
        long protocolOffset;
        boolean serverStarted;
        Session(File dir) { this.dir = dir; }
        void pollInternet() {
            long now = System.currentTimeMillis();
            if (now < nextInternetPoll) return;
            nextInternetPoll = now + 1000;
            try {
                // Separate file descriptor: never consumes or changes the protocol stream.
                File protocol = new File(dir, "stdout");
                if (protocol.isFile() && !serverStarted) {
                    try (RandomAccessFile input = new RandomAccessFile(protocol, "r")) {
                        input.seek(Math.max(0, Math.min(protocolOffset, input.length()) - 128));
                        String line;
                        int count = 0;
                        while ((line = input.readLine()) != null && count++ < 200)
                            if (line.contains("UI_ServerStatus_Started")) serverStarted = true;
                        protocolOffset = input.getFilePointer();
                    }
                }
                java.util.Properties data = new java.util.Properties();
                File status = new File(dir, "internet.properties");
                if (status.isFile()) try (InputStream input = new FileInputStream(status)) { data.load(input); }
                if (!data.containsKey("state")) data.setProperty("state", serverStarted ? "unknown" : "pending");
                if (serverStarted && "discovered".equals(data.getProperty("state"))) data.setProperty("state", "unknown");
                String key = data.getProperty("state") + ":" + data.getProperty("port", "") + ":" + data.getProperty("externalAddress", "");
                if (key.equals(lastInternetState)) return;
                lastInternetState = key;
                main.post(() -> { if (!closed && active == this) internetView.update(data); });
            } catch (IOException e) { Log.w("CoopHostBridge", "Internet status unavailable", e); }
        }
        void bind() {
            if (closed) { finish(1, "Host closed before server launch"); return; }
            Intent intent = new Intent(activity, CoopServerService.class)
                    .putExtra("instance", instance.getName()).putExtra("session", dir.getAbsolutePath());
            try {
                bound = activity.bindService(intent, this, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                if (!bound) finish(1, "Android refused to bind COOP service");
            } catch (Exception e) { finish(1, e.toString()); }
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try { write(dir, "accepted", "1"); }
            catch (IOException e) { finish(1, e.toString()); }
            Log.i("CoopHostBridge", "Android COOP process bound");
        }
        @Override public void onNullBinding(ComponentName name) { finish(1, "Server process is busy"); }
        @Override public void onBindingDied(ComponentName name) { finish(1, "Server binding died"); }
        @Override public void onServiceDisconnected(ComponentName name) {
            // Unbind immediately so BIND_AUTO_CREATE cannot restart a failed server.
            if (bound) { activity.unbindService(this); bound = false; }
            if (closed) return;
            watcher.execute(() -> {
                int code = 1;
                try {
                    int pid = Integer.parseInt(new String(Files.readAllBytes(new File(dir, "pid").toPath()), StandardCharsets.UTF_8));
                    ActivityManager manager = activity.getSystemService(ActivityManager.class);
                    for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(activity.getPackageName(), pid, 1)) {
                        code = info.getReason() == ApplicationExitInfo.REASON_EXIT_SELF ? info.getStatus() : 1;
                    }
                } catch (Exception e) { Log.w("CoopHostBridge", "Exit status unavailable", e); }
                finish(code, null);
            });
        }
        void finish(int code, String error) {
            main.post(() -> {
                if (bound) { activity.unbindService(this); bound = false; }
                try {
                    if (error != null) write(dir, "error", error);
                    write(dir, "exit", Integer.toString(code));
                } catch (IOException e) { Log.e("CoopHostBridge", "Cannot report exit", e); }
                if (active == this) { active = null; internetView.close(); }
            });
        }
    }
    @Override public void close() {
        closed = true;
        watcher.shutdownNow();
        main.post(internetView::close);
        Session session = active;
        if (session != null && session.bound) {
            activity.unbindService(session);
            session.bound = false;
        }
    }
}
