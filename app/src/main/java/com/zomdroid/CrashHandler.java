package com.zomdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Button;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

public class CrashHandler {
    public static final String LAST_LOG_FILE_NAME = "lastlog.txt";
    public static final String LOG_FILE_NAME = "log.txt";

    public static void init() {
        File logFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LOG_FILE_NAME);
        File lastLogFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LAST_LOG_FILE_NAME);
        if (logFile.exists()) {
            if (lastLogFile.exists())
                lastLogFile.delete();
            if (!logFile.renameTo(lastLogFile))
                logFile.delete();
        }

        try (BufferedWriter header = new BufferedWriter(new FileWriter(logFile, false))) {
            header.write("Device  : " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            header.newLine();
            header.write("Android : " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            header.newLine();
            header.write("---");
            header.newLine();
        } catch (IOException ignore) {}

        Thread readerThread = new Thread(() -> {
            try {
                Runtime.getRuntime().exec("logcat -c"); // clear logcat buffer
            } catch (IOException ignore) {}

            // Do NOT name buffers explicitly. "-b main,crash,system" asks for crash/system, which
            // hold other apps' records and need READ_LOGS: permissive ROMs tolerate it, strict ones
            // (seen on vivo/Funtouch) dump whatever main had and then EXIT — the reader hits
            // end-of-stream a second after start and the app logs nothing for the rest of the
            // session, which is exactly how a crash report arrives with no evidence in it.
            // Plain logcat uses the default buffer set (main/system/crash) and silently skips the
            // ones it may not read. The fallback covers any other reason the process dies early.
            String[] commands = { "logcat -v time *:I", "logcat -b main -v time *:I" };

            for (int attempt = 0; attempt < commands.length; attempt++) {
                Process logcatProcess;
                try {
                    logcatProcess = Runtime.getRuntime().exec(commands[attempt]);
                } catch (IOException e) {
                    continue; // try the next form
                }
                long linesRead = 0;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()));
                     BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        linesRead++;
                    }
                } catch (IOException ignore) {
                }
                // Reaching here means logcat ended. It is meant to stream until the process dies,
                // so an early exit means this form was rejected — retry with the narrower one.
                // If it streamed a lot before ending, the ROM killed it; retrying gains nothing.
                if (linesRead > 200) break;
            }
        });
        readerThread.start();
    }

    // called from native code
    public static void handleAbort() throws IOException {
        Activity activity = ZomdroidApplication.getCurrentActivity();
        if (activity == null) System.exit(1);

        activity.runOnUiThread(() -> {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.dialog_title_fatal_error)
                    .setMessage(R.string.app_aborted)
                    .setCancelable(false)
                    .setPositiveButton(R.string.share_logs, null)
                    .setNegativeButton(R.string.dialog_button_quit, (d, which) -> {
                        System.exit(1);
                    })
                    .create();

            dialog.setOnShowListener(d -> {
                Button shareButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                shareButton.setOnClickListener(v -> {
                    File logFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LOG_FILE_NAME);
                    Uri uri = DocumentsContract.buildDocumentUri(
                            C.STORAGE_PROVIDER_AUTHORITY,
                            logFile.getAbsolutePath()
                    );
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(intent, null));
                });
            });

            dialog.show();
        });
    }
}
