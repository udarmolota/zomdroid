package com.zomdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Visible, debug-only probe. Keep foreground while testing; this is not a hosting service. */
public final class ServerProbeActivity extends Activity {
    private static boolean started;
    private static String activeInstance;
    private static volatile FileDescriptor commandPipe;
    private static volatile String status = "Ready to start (not running).";
    private static final java.util.concurrent.atomic.AtomicBoolean quitRequested =
            new java.util.concurrent.atomic.AtomicBoolean();

    static void requestSaveAndQuit() {
        if (commandPipe == null || !quitRequested.compareAndSet(false, true)) return;
        try {
            byte[] quit = "quit\n".getBytes(StandardCharsets.UTF_8);
            Os.write(commandPipe, quit, 0, quit.length);
            status = "quit sent. Waiting for server save and shutdown.";
            android.util.Log.i("ServerProbe", "Requested save and quit");
        } catch (Exception e) {
            quitRequested.set(false);
            status = "Could not send quit: " + e;
            android.util.Log.e("ServerProbe", status, e);
        }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView console;
    private Button join;
    private static boolean serverReady;
    private File log;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String tail = "";
            if (started && log != null && log.isFile()) {
                try (RandomAccessFile file = new RandomAccessFile(log, "r")) {
                    long length = file.length();
                    int count = (int) Math.min(16000, length);
                    file.seek(length - count);
                    byte[] bytes = new byte[count];
                    file.readFully(bytes);
                    tail = new String(bytes, StandardCharsets.UTF_8);
                } catch (Exception e) { tail = e.toString(); }
            }
            console.setText(status + "\n\n" + tail);
            if (tail.contains("*** SERVER STARTED ****")) serverReady = true;
            if (tail.contains("Shutdown handling started")) serverReady = false;
            if (join != null) join.setEnabled(serverReady);
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Providers run in the main process only; the server needs its own storage singleton.
        AppStorage.init(this);
        GameInstanceManager.init(this);
        LauncherPreferences.init(this);
        String name = getIntent().getStringExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME);
        if (started && !java.util.Objects.equals(activeInstance, name)) {
            new AlertDialog.Builder(this).setMessage("A probe for " + activeInstance
                    + " already owns this process. Stop it before testing another instance.")
                    .setPositiveButton("Close", (d, w) -> finish()).setCancelable(false).show();
            return;
        }
        GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(name);
        if (instance == null || !instance.isInstallationFinished()) { finish(); return; }
        log = new File(instance.getGamePath(), "server-native.log");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView description = new TextView(this);
        description.setText("Server startup test — " + name + "\n"
                + "Uses a separate server-probe folder and 1 GB heap. After startup, use Join "
                + "to test a client on this phone. Logs are included in the bug report.");
        layout.addView(description);
        Button start = new Button(this);
        start.setText("Start server test");
        start.setEnabled(!started);
        layout.addView(start);
        start.setOnClickListener(v -> {
            started = true;
            activeInstance = name;
            start.setEnabled(false);
            status = "Starting. Process alive does not mean server ready; check the log below.";
            new Thread(() -> {
                try {
                    // Clear this probe's previous output before initialization can fail.
                    try (RandomAccessFile previous = new RandomAccessFile(log, "rw")) {
                        previous.setLength(0);
                    }
                    FileDescriptor[] pipe = Os.pipe();
                    Os.dup2(pipe[0], 0);
                    Os.close(pipe[0]);
                    commandPipe = pipe[1];
                    ServerProbeLauncher.launch(instance, getApplicationContext());
                    status = "Launch call returned. Check the log; background server threads may remain.";
                } catch (Throwable e) {
                    android.util.Log.e("ServerProbe", "Startup failed", e);
                    status = "Startup failed: " + e;
                    try (java.io.PrintWriter out = new java.io.PrintWriter(log, "UTF-8")) {
                        e.printStackTrace(out);
                    } catch (Exception writeError) {
                        android.util.Log.e("ServerProbe", "Cannot persist startup error", writeError);
                    }
                }
            }, "server-probe").start();
        });
        join = new Button(this);
        join.setText("Join from this phone");
        join.setEnabled(serverReady);
        layout.addView(join);
        join.setOnClickListener(v -> {
            android.content.Intent client = new android.content.Intent(this, GameActivity.class);
            client.putExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME, name);
            client.putExtra(GameActivity.EXTRA_SERVER_PROBE_CLIENT, true);
            startActivity(client);
        });
        Button stop = new Button(this);
        stop.setText("Request save and quit");
        layout.addView(stop);
        stop.setOnClickListener(v -> {
            requestSaveAndQuit();
            stop.setEnabled(!quitRequested.get());
        });
        Button close = new Button(this);
        close.setText("End test process");
        layout.addView(close);
        close.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("This immediately ends the test process. If startup succeeded, send quit "
                        + "and wait for shutdown first. An active test world will not be saved by this button.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("End process", (d, w) -> {
                    finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                }).show());
        console = new TextView(this);
        console.setTextIsSelectable(true);
        console.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(console);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }

    @Override protected void onResume() {
        super.onResume();
        if (console != null) handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
