package com.zomdroid;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import com.zomdroid.game.GameInstancesManager;

import java.io.File;
import java.io.IOException;

public class ZomdroidApplication extends Application {
    private static final String LOG_TAG = ZomdroidApplication.class.getName();
    private static final String LAST_LOG_FILE_NAME = "lastlog.txt";
    private static final String LOG_FILE_NAME = "log.txt";
    @Override
    public void onCreate() {
        super.onCreate();
        GameInstancesManager.init(this);
        LauncherPreferences.init(this);
        captureLogcatToFile();
        updateLauncherVersion();
    }

    private void updateLauncherVersion() {
        SharedPreferences prefs = getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        long savedVersion = prefs
                .getLong(C.shprefs.keys.LAUNCHER_VERSION, 0);
        long currentVersion = 0;
        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, e.toString());
        }
        if (savedVersion == 0 || currentVersion != savedVersion) {
            prefs.edit()
                    .putLong(C.shprefs.keys.LAUNCHER_VERSION, currentVersion)
                    .putBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, false)
                    .apply();
        }
    }

    public void captureLogcatToFile() {
        File logFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LOG_FILE_NAME);
//        File lastLogFile = new File(AppStorage.requireSingleton().getHomePath() + "/" + LAST_LOG_FILE_NAME);
//        if (logFile.exists()) {
//            if (lastLogFile.exists())
//                lastLogFile.delete();
//            if (!logFile.renameTo(lastLogFile))
//                logFile.delete();
//        }
        if (logFile.exists())
            logFile.delete();
        try {
            //Runtime.getRuntime().exec("logcat -c");
            Runtime.getRuntime().exec("logcat -r 32768 -n 1 *:I -f " + logFile.getAbsolutePath());
        } catch (IOException ignored) {}
    }
}
