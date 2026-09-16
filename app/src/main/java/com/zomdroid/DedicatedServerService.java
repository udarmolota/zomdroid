package com.zomdroid;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.system.Os;
import androidx.core.app.NotificationCompat;
import com.zomdroid.game.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Started (not bound-lifetime) service. Closing the dashboard never sends quit. */
public final class DedicatedServerService extends Service {
    static final int QUERY = 1, SAVE = 2, STOP = 3, SNAPSHOT = 4, FORCE = 5;
    private static final int NOTIFICATION = 4320;
    private static final String CHANNEL = "dedicated_server";
    private static volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService commands = Executors.newSingleThreadExecutor();
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == FORCE && canForceStop()) android.os.Process.killProcess(android.os.Process.myPid());
        if (msg.what == SAVE || msg.what == STOP) command(msg.what == STOP);
        if (msg.replyTo != null) try {
            Message reply = Message.obtain(null, SNAPSHOT); reply.setData(snapshot()); msg.replyTo.send(reply);
        } catch (RemoteException ignored) { }
        return true;
    }));
    private String instanceName, profileName, state = "starting", error = "";
    private File log, root;
    private RandomAccessFile leaseFile;
    private FileLock lease;
    private FileDescriptor input;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private boolean stopping, savePending;
    private long logOffset, saveAt;
    private long startedAt, stoppingAt;
    private ServerLogMonitor monitor = new ServerLogMonitor();
    private String lastNotification = "";

    public static boolean active(Context context) {
        File file = new File(context.getFilesDir(), "dedicated-server.lock");
        if (!file.isFile()) return false;
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            try (FileLock l = f.getChannel().tryLock()) { return l == null; }
        } catch (java.nio.channels.OverlappingFileLockException e) { return true; }
        catch (IOException e) { return true; } // Fail closed when a running server cannot be ruled out.
    }
    static boolean isRunningInProcess() { return running; }

    @Override public void onCreate() {
        super.onCreate(); AppStorage.init(this); LauncherPreferences.init(this); GameInstanceManager.init(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.ds_title), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (running) { notifyState(); return START_NOT_STICKY; }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        instanceName = intent.getStringExtra("instance"); profileName = intent.getStringExtra("profile");
        notifyState(); // Meet the foreground-service deadline before filesystem/JVM work.
        try {
            if (ServerProbeLauncher.isClaimed() || CoopServerService.isLaunched()) throw new IOException(getString(R.string.ds_busy));
            GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
            if (instance == null || !instance.isInstallationFinished()) throw new IOException("Instance unavailable");
            leaseFile = new RandomAccessFile(new File(getFilesDir(), "dedicated-server.lock"), "rw");
            // A dashboard status query may own the lock for a few microseconds.
            for (int attempt = 0; attempt < 5 && lease == null; attempt++) {
                lease = leaseFile.getChannel().tryLock();
                if (lease == null) android.os.SystemClock.sleep(10);
            }
            if (lease == null) throw new IOException(getString(R.string.ds_busy));
            DedicatedServerProfile profile = new DedicatedServerProfile(instance, profileName);
            profile.load();
            if (profile.adminPassword.isEmpty()) throw new IOException("Administrator password missing");
            root = profile.root; log = new File(instance.getGamePath(), "server-native.log");
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create hosting directory");
            try (RandomAccessFile old = new RandomAccessFile(log, "rw")) { old.setLength(0); }
            DedicatedServerProfile.write(new File(root, "dedicated-state"), "starting".getBytes(StandardCharsets.UTF_8));
            DedicatedServerProfile.write(new File(root, "dedicated-error.txt"), new byte[0]);
            DedicatedServerProfile.write(new File(root, "dedicated-telemetry.properties"), new byte[0]);
            cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zomdroid:server");
            cpu.acquire();
            WifiManager manager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) { wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Zomdroid:server"); wifi.acquire(); }
            running = true;
            startedAt = SystemClock.elapsedRealtime();
            handler.post(poll);
            new Thread(() -> {
                try {
                    profile.prepareMods(instance);
                    FileDescriptor[] pipe = Os.pipe(); Os.dup2(pipe[0], 0); Os.close(pipe[0]);
                    synchronized (this) { input = pipe[1]; }
                    ServerProbeLauncher.launchDedicated(instance, this, profile);
                    throw new IOException("GameServer.main returned without process shutdown");
                } catch (Throwable e) {
                    android.util.Log.e("DedicatedServer", "Server failed", e);
                    try { DedicatedServerProfile.write(new File(root, "dedicated-error.txt"), e.toString().getBytes(StandardCharsets.UTF_8)); }
                    catch (Exception ignored) { }
                    handler.post(() -> { state = "failed"; error = e.toString(); notifyState(); });
                    // A JVM cannot be restarted safely in this process. Leave the failure on disk.
                    handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 1500);
                }
            }, "dedicated-server").start();
        } catch (Exception e) {
            error = e.toString(); state = "failed";
            android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); release();
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private synchronized void command(boolean quit) {
        if (!running || stopping || input == null || (!quit && (!state.equals("running") || savePending))) return;
        consumeLog(); // Do not mistake an old save marker for this request's completion.
        if (log != null) logOffset = log.length();
        monitor = new ServerLogMonitor();
        monitor.ready = state.equals("running");
        if (quit) { stopping = true; state = "stopping"; stoppingAt = SystemClock.elapsedRealtime(); }
        else { savePending = true; monitor.saving = true; saveAt = System.currentTimeMillis(); }
        notifyState();
        commands.execute(() -> {
            try {
                byte[] bytes = (quit ? "quit\n" : "save\n").getBytes(StandardCharsets.UTF_8);
                int offset = 0; while (offset < bytes.length) offset += Os.write(input, bytes, offset, bytes.length - offset);
            } catch (Exception e) { handler.post(() -> { error = e.toString(); notifyState(); }); }
        });
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() { consumeLog(); notifyState(); handler.postDelayed(this, 2000); }
    };
    private void consumeLog() {
        if (log == null || !log.isFile()) return;
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            if (f.length() < logOffset) { logOffset = 0; monitor = new ServerLogMonitor(); }
            f.seek(logOffset);
            byte[] bytes = new byte[(int)Math.min(1024 * 1024, f.length() - logOffset)];
            f.readFully(bytes); logOffset = f.getFilePointer();
            monitor.accept(new String(bytes, StandardCharsets.UTF_8));
            if (monitor.ready && !stopping) state = "running";
            savePending = monitor.saving;
        } catch (IOException e) { error = e.toString(); }
    }
    private Bundle snapshot() {
        Bundle b = new Bundle(); b.putString("instance", instanceName); b.putString("profile", profileName);
        b.putString("state", state); b.putString("error", error); b.putBoolean("saving", savePending);
        b.putLong("saveAt", saveAt); b.putInt("pid", android.os.Process.myPid());
        b.putBoolean("canForce", canForceStop());
        b.putLong("uptime", running ? (SystemClock.elapsedRealtime() - startedAt) / 1000 : 0);
        return b;
    }
    private boolean canForceStop() {
        return running && ((stopping && SystemClock.elapsedRealtime() - stoppingAt > 30000)
                || (state.equals("starting") && SystemClock.elapsedRealtime() - startedAt > 60000));
    }
    private void notifyState() {
        String signature = state + ":" + stopping + ":" + profileName;
        if (signature.equals(lastNotification)) return;
        lastNotification = signature;
        Intent open = new Intent(this, DedicatedServerActivity.class).putExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME, instanceName);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int label = state.equals("failed") ? R.string.ds_failed : stopping ? R.string.ds_stopping : state.equals("running") ? R.string.ds_running : R.string.ds_starting;
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.ds_title) + " · " + (profileName == null ? "" : profileName))
                .setContentText(getString(label)).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
    }
    private void release() {
        handler.removeCallbacksAndMessages(null); commands.shutdown();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (cpu != null && cpu.isHeld()) cpu.release();
        try { if (lease != null) lease.release(); if (leaseFile != null) leaseFile.close(); } catch (IOException ignored) { }
    }
    @Override public void onDestroy() { release(); super.onDestroy(); }
}
