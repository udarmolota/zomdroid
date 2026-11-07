package com.zomdroid;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Tells the player why Host in the game does nothing while hosting is off for the instance. */
final class HostingOffNotice implements AutoCloseable {
    static final String MARKER = "host-pressed";
    private static final long POLL_MS = 1000;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private final File marker;
    private final Runnable poll = this::poll;
    private String agentPath;

    HostingOffNotice(Activity activity) {
        this.activity = activity;
        dir = new File(activity.getCacheDir(), "hosting-off");
        marker = new File(dir, MARKER);
        // The JVM refuses to start on a missing -javaagent, so no jar means no agent at all.
        File agent = new File(dir, "coop-agent.jar");
        try (InputStream input = activity.getAssets().open("coop-agent.jar")) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            Files.copy(input, agent.toPath(), StandardCopyOption.REPLACE_EXISTING);
            agentPath = agent.getAbsolutePath();
            marker.delete();
            main.postDelayed(poll, POLL_MS);
        } catch (IOException e) {
            Log.w("HostingOffNotice", "No Host notice this launch", e);
        }
    }

    /** The agent jar, or null if it could not be prepared. */
    String getAgentPath() { return agentPath; }

    private void poll() {
        if (marker.delete() && !activity.isFinishing()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.hosting_off_title)
                    .setMessage(activity.getString(R.string.hosting_off_message,
                            activity.getString(R.string.settings_coop_hosting)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        main.postDelayed(poll, POLL_MS);
    }

    @Override public void close() {
        main.removeCallbacks(poll);
    }
}
