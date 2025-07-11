package com.zomdroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.zomdroid.game.GameInstanceManager;

public class ZomdroidApplication extends Application {
    private static final String LOG_TAG = "ZomdroidApplication";
    @SuppressLint("StaticFieldLeak") // we have activity lifecycle tracking to avoid leaks
    private static volatile Activity currentActivity;
    private static boolean inited = false;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                currentActivity = activity;
                if (!inited) init();
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                if (activity == currentActivity) {
                    currentActivity = null;
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }
        });
    }

    private void updateLauncherVersion() {
        SharedPreferences prefs = getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        long savedVersion = prefs.getLong(C.shprefs.keys.LAUNCHER_VERSION, 0);
        long currentVersion = 0;
        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, e.toString());
        }
        if (savedVersion == 0 || currentVersion != savedVersion) {
            prefs.edit().putLong(C.shprefs.keys.LAUNCHER_VERSION, currentVersion)
                    .putBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, false)
                    .apply();
        }
    }

    private void init() {
        inited = true;
        GameInstanceManager.init(this);
        LauncherPreferences.init(this);
        CrashHandler.init();
        updateLauncherVersion();
    }

    public static Activity getCurrentActivity() {
        return currentActivity;
    }
}
