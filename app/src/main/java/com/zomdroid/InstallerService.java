package com.zomdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InstallerService extends Service implements TaskProgressListener {
    private static final String LOG_TAG = InstallerService.class.getName();
    private static final String CHANNEL_ID = "com.zomdroid.InstallerService.NOTIFICATION_CHANNEL";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STARTED = "com.zomdroid.InstallerService.ACTION_STARTED";
    public static final String EXTRA_COMMAND = "com.zomdroid.InstallerService.EXTRA_COMMAND";
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.InstallerService.EXTRA_GAME_INSTANCE_NAME";
    public static final String EXTRA_ARCHIVE_URI = "com.zomdroid.InstallerService.EXTRA_ARCHIVE_URI";
    public static final String EXTRA_NATIVE_LIBS_URI = "com.zomdroid.InstallerService.EXTRA_NATIVE_LIBS_URI";
    public static final String EXTRA_SAVES_URI = "com.zomdroid.InstallerService.EXTRA_SAVES_URI";
    public static final String EXTRA_MODS_URI = "com.zomdroid.InstallerService.EXTRA_MODS_URI";
    public static final String EXTRA_CONTROLS_URI = "com.zomdroid.InstallerService.EXTRA_CONTROLS_URI";

    private final IBinder binder = new LocalBinder();
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private NotificationManagerCompat notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastProgressUpdateMs;
    private final MutableLiveData<TaskState> taskState = new MutableLiveData<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "GameInstallerServiceChannel", NotificationManager.IMPORTANCE_LOW);

        notificationManager = NotificationManagerCompat.from(this);
        notificationManager.createNotificationChannel(channel);

        Intent serviceStartedBroadcast = new Intent(ACTION_STARTED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(serviceStartedBroadcast);

        Task task = Task.values()[intent.getIntExtra(EXTRA_COMMAND, 0)];
        switch (task) {
            case CREATE_GAME_INSTANCE: {
                doCreateGameInstance(intent);
                break;
            }
            case DELETE_GAME_INSTANCE: {
                doDeleteGameInstance(intent);
                break;
            }
            case INSTALL_DEPENDENCIES: {
                doInstallDependencies(intent);
                break;
            }
            case INSTALL_MOD_TO_INSTANCE: {
                doInstallModToInstance(intent);
                break;
            }
            case INSTALL_CONTROLS_TO_INSTANCE: {
                doInstallControlsToInstance(intent);
                break;
            }

        }

        return START_NOT_STICKY;
    }

    private void doCreateGameInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_creating_instance);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_create_instance),
                    "Game instance name intent extra is missing");
            return;
        }
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_create_instance),
                    "Game instance with name " + gameInstanceName + " not found");
            return;
        }

        Uri gameFilesArchiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
        if (gameFilesArchiveUri == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_create_instance),
                    "Game files archive URI intent extra is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                installGameFromZip(gameInstance, gameFilesArchiveUri);
                // 42.13 extra jar unpack
                extractProjectZomboidJarSimple(gameInstance);

                // Added in 1.3.2 for native game libs
                File androidDirFromGame = new File(gameInstance.getGamePath() + "/android");
                boolean gameHasAndroid = androidDirFromGame.exists();

                String nativeLibsPath = gameInstance.getGamePath() + "/android/arm64-v8a";
                File nativeLibsDir = new File(nativeLibsPath);

                if (!gameHasAndroid) {
                    if (nativeLibsDir.exists()) FileUtils.deleteDirectory(nativeLibsDir);
                    nativeLibsDir.mkdirs();
                } else {
                    if (!nativeLibsDir.exists()) nativeLibsDir.mkdirs();
                }

                Uri nativeLibsArchiveUri = intent.getParcelableExtra(EXTRA_NATIVE_LIBS_URI);
                if (nativeLibsArchiveUri != null) {
                    try (InputStream nativeLibsStream = getContentResolver().openInputStream(nativeLibsArchiveUri)) {
                        FileUtils.extractZipToDisk(nativeLibsStream, nativeLibsPath, this,
                                FileUtils.queryFileSize(getContentResolver(), nativeLibsArchiveUri));
                    } catch (IOException e) {
                        System.out.println("Native libraries not installed: " + e.getMessage());
                        // Still can work without MP
                    }
                } else {
                    System.out.println("No native libraries provided — skipping multiplayer setup");
                }

                // 42.13 problematic lib renaming
                maybeDisableLightingLibFor4213(gameInstance);

                // Extracting Saves
                Uri savesArchiveUri = intent.getParcelableExtra(EXTRA_SAVES_URI);
                if (savesArchiveUri != null) {
                    try {
                        String savesRootPath = gameInstance.getHomePath() + "/Zomboid/Saves";
                        File savesRootDir = new File(savesRootPath);
                        if (!savesRootDir.exists()) savesRootDir.mkdirs();

                        try (InputStream savesStream = getContentResolver().openInputStream(savesArchiveUri)) {
                            FileUtils.extractZipToDisk(
                                    savesStream,
                                    savesRootPath,
                                    this,
                                    FileUtils.queryFileSize(getContentResolver(), savesArchiveUri)
                            );
                        }
                    } catch (IOException e) {
                        System.out.println("Saves not installed: " + e.getMessage());
                    }
                }

                // Extracting Mods (при создании инстанса)
                Uri modsArchiveUri = intent.getParcelableExtra(EXTRA_MODS_URI);
                if (modsArchiveUri != null) {
                    try (InputStream modsStream = getContentResolver().openInputStream(modsArchiveUri)) {
                        String modsRootPath = gameInstance.getHomePath() + "/Zomboid/mods";
                        File modsRootDir = new File(modsRootPath);
                        if (!modsRootDir.exists()) modsRootDir.mkdirs();

                        FileUtils.extractZipToDisk(
                                modsStream,
                                modsRootPath,
                                this,
                                FileUtils.queryFileSize(getContentResolver(), modsArchiveUri)
                        );
                    } catch (IOException e) {
                        System.out.println("Mods not installed: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_create_instance), e.toString());
                return;
            }

            GameInstanceManager.requireSingleton().markInstallationFinished(gameInstance);
            finish(getString(R.string.dialog_title_instance_created), null);
        });
    }

    private void doDeleteGameInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_deleting_game_instance);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_delete_instance),
                    "Game instance name intent extra is missing");
            return;
        }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_delete_instance),
                    "Game instance with name " + gameInstanceName + " not found");
            return;
        }

        executorService.submit(() -> {
            try {
                FileUtils.deleteDirectory(new File(gameInstance.getHomePath()));
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_delete_instance), e.toString());
                return;
            }

            GameInstanceManager.requireSingleton().unregisterInstance(gameInstance);
            finish(getString(R.string.dialog_title_instance_deleted), null);
        });
    }

    private void doInstallDependencies(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_dependencies);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        executorService.submit(() -> {
            SharedPreferences prefs = getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
            Gson gson = new Gson();

            String bundlesJson = prefs.getString(C.shprefs.keys.INSTALLED_BUNDLES, "[]");

            Type mapType = new TypeToken<HashMap<String, Long>>() {}.getType();
            HashMap<String, Long> oldBundlesHashesMap = gson.fromJson(bundlesJson, mapType);

            HashMap<String, Long> newBundlesHashesMap = new HashMap<>();

            // --- JRE 21 ---
            Long jre21HashOld = oldBundlesHashesMap.get(C.assets.BUNDLES_JRE21);
            try {
                Long jre21HashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JRE21);
                newBundlesHashesMap.put(C.assets.BUNDLES_JRE21, jre21HashNew);

                // Reinstall only if the bundle changed (CRC mismatch) or not installed yet.
                if (jre21HashOld == null || !jre21HashOld.equals(jre21HashNew)) {
                    String jre21Path = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE_21;
                    File jre21Dir = new File(jre21Path);
                    if (jre21Dir.exists()) FileUtils.deleteDirectory(jre21Dir);

                    InputStream jreBundleInStream = getAssets().open(C.assets.BUNDLES_JRE21);
                    FileUtils.extractTarXzToDisk(jreBundleInStream, jre21Path, this, 0);
                    jreBundleInStream.close();
                }
            } catch (IOException e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                return;
            }

            // --- JRE 25 ---
            Long jre25HashOld = oldBundlesHashesMap.get(C.assets.BUNDLES_JRE25);
            try {
                Long jre25HashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JRE25);
                newBundlesHashesMap.put(C.assets.BUNDLES_JRE25, jre25HashNew);

                // Reinstall only if the bundle changed (CRC mismatch) or not installed yet.
                if (jre25HashOld == null || !jre25HashOld.equals(jre25HashNew)) {
                    String jre25Path = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE_25;
                    File jre25Dir = new File(jre25Path);
                    if (jre25Dir.exists()) FileUtils.deleteDirectory(jre25Dir);

                    InputStream jreBundleInStream = getAssets().open(C.assets.BUNDLES_JRE25);
                    FileUtils.extractTarXzToDisk(jreBundleInStream, jre25Path, this, 0);
                    jreBundleInStream.close();
                }
            } catch (IOException e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                return;
            }

            // --- LIBS ---
            Long libsHashOld = oldBundlesHashesMap.get(C.assets.BUNDLES_LIBS);
            try {
                Long libsHashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_LIBS);
                newBundlesHashesMap.put(C.assets.BUNDLES_LIBS, libsHashNew);

                if (libsHashOld == null || !libsHashOld.equals(libsHashNew)) {
                    String libsPath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.LIBS;
                    File libsDir = new File(libsPath);
                    if (libsDir.exists()) FileUtils.deleteDirectory(libsDir);

                    InputStream libsBundleInStream = getAssets().open(C.assets.BUNDLES_LIBS);
                    FileUtils.extractTarXzToDisk(libsBundleInStream, libsPath, this, 0);
                }
            } catch (IOException e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                return;
            }

            // --- JARS ---
            Long jarsHashOld = oldBundlesHashesMap.get(C.assets.BUNDLES_JARS);
            try {
                Long jarsHashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JARS);
                newBundlesHashesMap.put(C.assets.BUNDLES_JARS, jarsHashNew);

                if (jarsHashOld == null || !jarsHashOld.equals(jarsHashNew)) {
                    String jarsPath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JARS;
                    File jarsDir = new File(jarsPath);
                    if (jarsDir.exists()) FileUtils.deleteDirectory(jarsDir);

                    InputStream jarsBundleInStream = getAssets().open(C.assets.BUNDLES_JARS);
                    FileUtils.extractTarToDisk(jarsBundleInStream, jarsPath, this, 0);
                }
            } catch (IOException e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                return;
            }

            bundlesJson = gson.toJson(newBundlesHashesMap);
            prefs.edit()
                    .putString(C.shprefs.keys.INSTALLED_BUNDLES, bundlesJson)
                    .putBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, true)
                    .apply();

            finish(getString(R.string.dialog_title_dependencies_installed), null);
        });
    }

    // -------------------- INSTALL MOD TO INSTANCE --------------------

    private void doInstallModToInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_mods);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (instanceName == null) {
            finishWithError(taskTitle, "Game instance name is missing");
            return;
        }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gameInstance == null) {
            finishWithError(taskTitle, "Game instance not found: " + instanceName);
            return;
        }

        Uri modsArchiveUri = intent.getParcelableExtra(EXTRA_MODS_URI);
        if (modsArchiveUri == null) {
            finishWithError(taskTitle, "Mods archive URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                String modsRootPath = gameInstance.getHomePath() + "/Zomboid/mods";
                File modsRootDir = new File(modsRootPath);
                if (!modsRootDir.exists()) modsRootDir.mkdirs();

                try (InputStream modsStream = getContentResolver().openInputStream(modsArchiveUri)) {
                    FileUtils.extractZipToDisk(
                            modsStream,
                            modsRootPath,
                            this,
                            FileUtils.queryFileSize(getContentResolver(), modsArchiveUri)
                    );
                }

                finish(getString(R.string.dialog_title_mods_installed), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_mods), e.toString());
            }
        });
    }

    private void doInstallControlsToInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_controls);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (instanceName == null) {
            finishWithError(taskTitle, "Game instance name is missing");
            return;
        }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gameInstance == null) {
            finishWithError(taskTitle, "Game instance not found: " + instanceName);
            return;
        }

        Uri controlsArchiveUri = intent.getParcelableExtra(EXTRA_CONTROLS_URI);
        if (controlsArchiveUri == null) {
            finishWithError(taskTitle, "Controls archive URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                // "home -> game -> controls"
                // Предположим, что "game" = gameInstance.getGamePath()
                String controlsDirPath = gameInstance.getGamePath() + "/controls";
                File controlsDir = new File(controlsDirPath);
                if (!controlsDir.exists()) controlsDir.mkdirs();

                File outFile = new File(controlsDir, "controls.json");

                boolean found = false;

                try (InputStream is = getContentResolver().openInputStream(controlsArchiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {

                    ZipEntry e;
                    byte[] buf = new byte[64 * 1024];

                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) continue;

                        String name = e.getName();
                        // ловим и "controls.json", и "something/controls.json"
                        if (name != null && name.toLowerCase().endsWith("controls.json")) {
                            //byte[] buf = new byte[64 * 1024];
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

                            int r;
                            while ((r = zis.read(buf)) != -1) {
                                baos.write(buf, 0, r);
                            }

                            byte[] jsonBytes = baos.toByteArray();

                            // 1) пишем файл
                            try (OutputStream os = new FileOutputStream(outFile, false)) {
                                os.write(jsonBytes);
                                os.flush();
                            }

                            // 2) пишем SharedPreferences (перезапишет текущий layout в памяти)
                            String json = new String(jsonBytes, java.nio.charset.Charset.defaultCharset());
                            getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                                    .edit()
                                    .putString(C.shprefs.keys.INPUT_CONTROLS, json)
                                    .apply();

                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    finishWithError(getString(R.string.dialog_title_failed_to_install_controls),
                            "controls.json not found in the ZIP");
                    return;
                }

                finish(getString(R.string.dialog_title_controls_installed), null);

            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_controls), e.toString());
            }
        });
    }

    private void finish(String title, String message) {
        this.taskState.postValue(new TaskState(title, message, -1, 0, true, false));
    }

    private void finishWithError(String title, String error) {
        Log.e(LOG_TAG, error);
        this.taskState.postValue(new TaskState(title, error, -1, 0, false, true));
    }

    private void installGameFromZip(GameInstance gameInstance, Uri zipUri) throws IOException {
        ContentResolver contentResolver = getApplicationContext().getContentResolver();
        try (InputStream inputStream = contentResolver.openInputStream(zipUri)) {
            long fileSize = FileUtils.queryFileSize(contentResolver, zipUri);
            FileUtils.extractZipToDisk(inputStream, gameInstance.getGamePath(), this, fileSize);
        }
    }

    @Override
    public void onTimeout(int startId) {
        super.onTimeout(startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private Notification buildNotification(String title) {
        Intent notificationIntent = new Intent(this, LauncherActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setAutoCancel(false);
        return notificationBuilder.build();
    }

    @Override
    public void onProgressUpdate(String message, int progress, int progressMax) {
        if (System.currentTimeMillis() - lastProgressUpdateMs < 500) return;
        lastProgressUpdateMs = System.currentTimeMillis();

        TaskState currentState = this.taskState.getValue();
        this.taskState.postValue(new TaskState(currentState == null ? null : currentState.title,
                message, progress, progressMax, false, false));

        handler.post(() -> {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (message != null) {
                notificationBuilder.setContentText(message);
            }
            if (progress < 0)
                notificationBuilder.setProgress(0, 0, true);
            else
                notificationBuilder.setProgress(progressMax, progress, false);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        });
    }

    private void maybeDisableLightingLibFor4213(GameInstance gameInstance) {
        File gameDir = new File(gameInstance.getGamePath());
        File pzJar = new File(gameDir, "projectzomboid.jar");
        if (!pzJar.exists()) return; // не 42.13-структура

        File soDir = new File(gameDir, "android/arm64-v8a");
        File so = new File(soDir, "libLighting64.so");
        if (!so.exists()) return;

        File disabled = new File(soDir, "libLighting64.so.disabled");
        if (disabled.exists()) {
            //noinspection ResultOfMethodCallIgnored
            so.delete();
            return;
        }

        if (!so.renameTo(disabled)) {
            throw new RuntimeException("Failed to rename libLighting64.so for 42.13: " + so.getAbsolutePath());
        }

        Log.i(LOG_TAG, "42.13 patch: disabled libLighting64.so -> " + disabled.getName());
    }

    private void extractProjectZomboidJarSimple(GameInstance gameInstance) throws IOException {
        File gameDir = new File(gameInstance.getGamePath());
        File jar = new File(gameDir, "projectzomboid.jar");
        if (!jar.exists()) return;

        Log.i(LOG_TAG, "42.13 test: extracting projectzomboid.jar using FileUtils");
        try (InputStream is = new FileInputStream(jar)) {
            FileUtils.extractZipToDisk(is, gameDir.getAbsolutePath(), this, jar.length());
        }
    }

    public LiveData<TaskState> getTaskState() {
        return taskState;
    }

    public class LocalBinder extends Binder {
        public InstallerService getService() {
            return InstallerService.this;
        }
    }

    public enum Task {
        CREATE_GAME_INSTANCE,
        DELETE_GAME_INSTANCE,
        INSTALL_DEPENDENCIES,
        INSTALL_MOD_TO_INSTANCE,
        INSTALL_CONTROLS_TO_INSTANCE
    }

    public static class TaskState {
        public final String title;
        public final String message;
        public final int progress;
        public final int progressMax;
        public final boolean isFinished;
        public final boolean isFinishedWithError;

        public TaskState(String title, String message, int progress, int progressMax,
                         boolean isFinished, boolean isFinishedWithError) {
            this.title = title;
            this.message = message;
            this.progress = progress;
            this.progressMax = progressMax;
            this.isFinished = isFinished;
            this.isFinishedWithError = isFinishedWithError;
        }
    }
}
