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
    private static final String LAST_LOG_FILE_NAME = "lastlog.txt";
    private static final String LOG_FILE_NAME = "log.txt";

    public static void init() {
        File logFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LOG_FILE_NAME);
        File lastLogFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LAST_LOG_FILE_NAME);
        if (logFile.exists()) {
            if (lastLogFile.exists())
                lastLogFile.delete();
            if (!logFile.renameTo(lastLogFile))
                logFile.delete();
        }
        Thread readerThread = new Thread(() -> {

            Process logcatProcess;
            try {
                Runtime.getRuntime().exec("logcat -c"); // clear logcat buffer
                logcatProcess = Runtime.getRuntime().exec("logcat -v time *:I *:I");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ignore) {
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
