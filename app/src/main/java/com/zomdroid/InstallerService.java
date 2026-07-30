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
import android.os.Build;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class InstallerService extends Service implements TaskProgressListener {
    private static final String LOG_TAG = InstallerService.class.getName();
    private static final String CHANNEL_ID = "com.zomdroid.InstallerService.NOTIFICATION_CHANNEL";
    private static final int NOTIFICATION_ID = 1;

    // Intent action broadcast when service starts
    public static final String ACTION_STARTED = "com.zomdroid.InstallerService.ACTION_STARTED";

    // Intent extras
    public static final String EXTRA_COMMAND = "com.zomdroid.InstallerService.EXTRA_COMMAND";
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.InstallerService.EXTRA_GAME_INSTANCE_NAME";
    public static final String EXTRA_ARCHIVE_URI = "com.zomdroid.InstallerService.EXTRA_ARCHIVE_URI";
    public static final String EXTRA_NATIVE_LIBS_URI = "com.zomdroid.InstallerService.EXTRA_NATIVE_LIBS_URI";
    public static final String EXTRA_SAVES_URI = "com.zomdroid.InstallerService.EXTRA_SAVES_URI";
    public static final String EXTRA_MODS_URI = "com.zomdroid.InstallerService.EXTRA_MODS_URI";
    public static final String EXTRA_CONTROLS_URI = "com.zomdroid.InstallerService.EXTRA_CONTROLS_URI";
    public static final String EXTRA_OUTPUT_URI = "com.zomdroid.InstallerService.EXTRA_OUTPUT_URI";
    public static final String EXTRA_DRIVER_URI = "com.zomdroid.InstallerService.EXTRA_DRIVER_URI";
    // Build version of the target instance ("41" or "42"), used by mod fix to choose install strategy
    public static final String EXTRA_BUILD_VERSION = "com.zomdroid.InstallerService.EXTRA_BUILD_VERSION";
    public static final String EXTRA_BETTERFPS_MODE = "com.zomdroid.InstallerService.EXTRA_BETTERFPS_MODE";
    public static final String EXTRA_RLZ_LEVEL = "com.zomdroid.InstallerService.EXTRA_RLZ_LEVEL";
    public static final String EXTRA_INSTALL_PRESET_NAME = "com.zomdroid.InstallerService.EXTRA_INSTALL_PRESET_NAME";
    public static final String EXTRA_GPU_VENDOR = "com.zomdroid.InstallerService.EXTRA_GPU_VENDOR";

    private final IBinder binder = new LocalBinder();
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private NotificationManagerCompat notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastProgressUpdateMs;
    private final MutableLiveData<TaskState> taskState = new MutableLiveData<>();
    private Task currentTask;
    private String currentInstallPresetName;
    private String currentGpuVendor;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GameInstallerServiceChannel", NotificationManager.IMPORTANCE_LOW);

        notificationManager = NotificationManagerCompat.from(this);
        notificationManager.createNotificationChannel(channel);

        Task task = Task.values()[intent.getIntExtra(EXTRA_COMMAND, 0)];
        currentTask = task;
        currentInstallPresetName = intent.getStringExtra(EXTRA_INSTALL_PRESET_NAME);
        currentGpuVendor = intent.getStringExtra(EXTRA_GPU_VENDOR);

        Intent serviceStartedBroadcast = new Intent(ACTION_STARTED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(serviceStartedBroadcast);

        switch (task) {
            case CREATE_GAME_INSTANCE:
                doCreateGameInstance(intent);
                break;
            case DELETE_GAME_INSTANCE:
                doDeleteGameInstance(intent);
                break;
            case INSTALL_DEPENDENCIES:
                doInstallDependencies(intent);
                break;
            case INSTALL_MOD_TO_INSTANCE:
                doInstallModToInstance(intent);
                break;
            case INSTALL_CONTROLS_TO_INSTANCE:
                doInstallControlsToInstance(intent);
                break;
            case INSTALL_SAVES_TO_INSTANCE:
                doInstallSavesToInstance(intent);
                break;
            case EXPORT_SAVES_FROM_INSTANCE:
                doExportSavesFromInstance(intent);
                break;
            case EXPORT_CONTROLS_FROM_INSTANCE:
                doExportControlsFromInstance(intent);
                break;
            case IMPORT_CUSTOM_DRIVER:
                doImportCustomDriver(intent);
                break;
            case EXPORT_CUSTOM_DRIVER:
                doExportCustomDriver(intent);
                break;
            case EXPORT_LOG:
                doExportLog(intent);
                break;
            case INSTALL_BETTERFPS:
                doInstallBetterFps(intent);
                break;
            case INSTALL_RENDER_LESS_ZOMBIE:
                doInstallRenderLessZombie(intent);
                break;
            case INSTALL_MOD_WITH_FIX:
                doInstallModWithFix(intent);
                break;
            case INSTALL_MOD_SMART:
                doInstallModSmart(intent);
                break;
            case INSTALL_ETO:
                doInstallEto(intent);
                break;
            case INSTALL_ZOMBIEBUDDY:
                doInstallZombieBuddy(intent);
                break;
            case INSTALL_ZBBETTERFPS:
                doInstallZbBetterFps(intent);
                break;
            case IMPORT_GAME_SETTINGS:
                doImportGameSettings(intent);
                break;
            case EXPORT_GAME_SETTINGS:
                doExportGameSettings(intent);
                break;
            case INSTALL_NATIVE_LIBS:
                doInstallNativeLibs(intent);
                break;
        }

        return START_NOT_STICKY;
    }

    // -------------------- CREATE GAME INSTANCE --------------------

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
                // Users often zip the game inside one or more wrapper folders. Drill down to the
                // real game root (folder holding ProjectZomboid64/projectzomboid.jar/zombie) and
                // lift it up to gamePath, so nothing downstream cares about the extra nesting.
                flattenGameRootIfWrapped(new File(gameInstance.getGamePath()));
                // Build 42.20+ moved all Linux libraries under natives/ and the bundled Android
                // libraries under natives/android/arm64-v8a/. Normalize that layout back to the
                // structure Zomdroid uses so the existing Java, box64 and linker paths stay valid.
                normalizeNativeLayoutFor4220(gameInstance);
                // 42.13+: extract projectzomboid.jar if present
                extractProjectZomboidJarSimple(gameInstance);
                // 42.20+ redundantly loads Android FMOD from the HotSpot VM. Zomdroid has
                // already loaded and initialized it on ART; keep only fmodintegration64 here.
                com.zomdroid.patch.FmodLoadPatchApplier.applyIfNeeded(gameInstance);
                // Bink has no ARM64 library. Make getVideo() return the already-supported
                // "unavailable" result instead of throwing and logging every UI frame.
                com.zomdroid.patch.BinkVideoPatchApplier.applyIfNeeded(gameInstance);
                // Keep the ARM64 Lighting implementation and replace only the one void JNI method
                // it omits in 42.20. This runs after projectzomboid.jar has been unpacked.
                com.zomdroid.patch.LightingTransmissionPatchApplier.applyIfNeeded(gameInstance);

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

                // 42.13: rename problematic native libs
                maybeDisableLibFor42(gameInstance);
                // 42.15/42.17: patch printSpecs() crash
                maybePatchPrintSpecsFor4215(gameInstance);
                // B42: patch ShaderUnit to enable combineShaderSources (required for NG_GL4ES)
                maybePatchShaderUnitCombine(gameInstance);

            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_create_instance), e.toString());
                return;
            }

            GameInstanceManager.requireSingleton().markInstallationFinished(gameInstance);
            finish(getString(R.string.dialog_title_instance_created), null);
        });
    }

    // -------------------- DELETE GAME INSTANCE --------------------

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

    // -------------------- INSTALL DEPENDENCIES --------------------

    // Called on every app launch (see LauncherFragment#updateDependencies), not just the first
    // one, so an APK update whose bundled libs.tar.xz/jre/jars changed can pick that up without
    // a full app reinstall. To keep that cheap and silent on the common case (nothing changed),
    // we CRC-check all bundles up front and only show the install notification/dialog and touch
    // disk for the ones that actually differ.
    private void doInstallDependencies(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_dependencies);

        // startForeground() must be called promptly regardless of whether anything turns out to
        // have changed — Android requires it soon after startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));

        executorService.submit(() -> {
            SharedPreferences prefs = getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
            Gson gson = new Gson();

            String bundlesJson = prefs.getString(C.shprefs.keys.INSTALLED_BUNDLES, "[]");

            Type mapType = new TypeToken<HashMap<String, Long>>() {}.getType();
            HashMap<String, Long> oldBundlesHashesMap = gson.fromJson(bundlesJson, mapType);
            HashMap<String, Long> newBundlesHashesMap = new HashMap<>();

            // --- Pass 1: CRC32 every bundle against its last-installed hash. Quiet and cheap —
            // reads the (already-compressed) asset bytes straight out of the APK, no extraction.
            boolean jre21Changed, jre25Changed, libsChanged, jarsChanged;
            try {
                Long jre21HashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JRE21);
                newBundlesHashesMap.put(C.assets.BUNDLES_JRE21, jre21HashNew);
                jre21Changed = !jre21HashNew.equals(oldBundlesHashesMap.get(C.assets.BUNDLES_JRE21));

                Long jre25HashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JRE25);
                newBundlesHashesMap.put(C.assets.BUNDLES_JRE25, jre25HashNew);
                jre25Changed = !jre25HashNew.equals(oldBundlesHashesMap.get(C.assets.BUNDLES_JRE25));

                Long libsHashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_LIBS);
                newBundlesHashesMap.put(C.assets.BUNDLES_LIBS, libsHashNew);
                libsChanged = !libsHashNew.equals(oldBundlesHashesMap.get(C.assets.BUNDLES_LIBS));

                Long jarsHashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JARS);
                newBundlesHashesMap.put(C.assets.BUNDLES_JARS, jarsHashNew);
                jarsChanged = !jarsHashNew.equals(oldBundlesHashesMap.get(C.assets.BUNDLES_JARS));
            } catch (IOException e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                return;
            }

            boolean anyChanged = jre21Changed || jre25Changed || libsChanged || jarsChanged;
            if (!anyChanged) {
                // Nothing to do — never posted an "in progress" state, so no dialog ever showed.
                finish(null, null);
                return;
            }

            // --- Pass 2: only now announce the install, and only extract what changed ---
            this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

            if (jre21Changed) {
                try {
                    String jre21Path = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE_21;
                    File jre21Dir = new File(jre21Path);
                    if (jre21Dir.exists()) FileUtils.deleteDirectory(jre21Dir);

                    InputStream jreBundleInStream = getAssets().open(C.assets.BUNDLES_JRE21);
                    FileUtils.extractTarXzToDisk(jreBundleInStream, jre21Path, this, 0);
                    jreBundleInStream.close();
                } catch (IOException e) {
                    finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                    return;
                }
            }

            if (jre25Changed) {
                try {
                    String jre25Path = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE_25;
                    File jre25Dir = new File(jre25Path);
                    if (jre25Dir.exists()) FileUtils.deleteDirectory(jre25Dir);

                    InputStream jreBundleInStream = getAssets().open(C.assets.BUNDLES_JRE25);
                    FileUtils.extractTarXzToDisk(jreBundleInStream, jre25Path, this, 0);
                    jreBundleInStream.close();
                } catch (IOException e) {
                    finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                    return;
                }
            }

            if (libsChanged) {
                try {
                    String libsPath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.LIBS;
                    File libsDir = new File(libsPath);
                    if (libsDir.exists()) FileUtils.deleteDirectory(libsDir);

                    InputStream libsBundleInStream = getAssets().open(C.assets.BUNDLES_LIBS);
                    FileUtils.extractTarXzToDisk(libsBundleInStream, libsPath, this, 0);
                } catch (IOException e) {
                    finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                    return;
                }
            }

            if (jarsChanged) {
                try {
                    String jarsPath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JARS;
                    File jarsDir = new File(jarsPath);
                    if (jarsDir.exists()) FileUtils.deleteDirectory(jarsDir);

                    InputStream jarsBundleInStream = getAssets().open(C.assets.BUNDLES_JARS);
                    FileUtils.extractTarToDisk(jarsBundleInStream, jarsPath, this, 0);
                } catch (IOException e) {
                    finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                    return;
                }
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

                // Temp dir next to mods folder for faster atomic move
                File tempDir = new File(gameInstance.getHomePath(), "tmp_mods_import_" + System.currentTimeMillis());
                if (!tempDir.mkdirs()) {
                    throw new RuntimeException("Failed to create temp dir: " + tempDir.getAbsolutePath());
                }

                try {
                    // 1) Extract ZIP to temp dir
                    try (InputStream modsStream = getContentResolver().openInputStream(modsArchiveUri)) {
                        FileUtils.extractZipToDisk(
                                modsStream,
                                tempDir.getAbsolutePath(),
                                this,
                                FileUtils.queryFileSize(getContentResolver(), modsArchiveUri)
                        );
                    }

                    // 2) Find all mod roots using smart recursive detection (handles any wrapper depth)
                    java.util.List<File> mods = new java.util.ArrayList<>();
                    collectModRoots(tempDir, mods);

                    if (mods.isEmpty()) {
                        throw new IllegalArgumentException("No valid mods found in ZIP (mod.info missing).");
                    }

                    // 3) Install each mod folder
                    for (File modDir : mods) {
                        File target = new File(modsRootDir, modDir.getName());
                        moveOrReplace(modDir, target);
                    }

                } finally {
                    // Cleanup temp dir
                    FileUtils.deleteDirectory(tempDir);
                }

                finish(getString(R.string.dialog_title_mods_installed), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_mods), e.toString());
            }
        });
    }

    // -------------------- INSTALL SAVES TO INSTANCE --------------------

    private void doInstallSavesToInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_saves);

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

        Uri savesArchiveUri = intent.getParcelableExtra(EXTRA_SAVES_URI);
        if (savesArchiveUri == null) {
            finishWithError(taskTitle, "Saves archive URI is missing");
            return;
        }

        executorService.submit(() -> {
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

                finish(getString(R.string.dialog_title_saves_installed), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_saves), e.toString());
            }
        });
    }

    // -------------------- INSTALL CONTROLS TO INSTANCE --------------------

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
                String controlsDirPath = gameInstance.getGamePath() + "/controls";
                File controlsDir = new File(controlsDirPath);
                if (!controlsDir.exists()) controlsDir.mkdirs();

                File outFile = new File(controlsDir, "controls.json");
                File iconsDir = new File(controlsDir, "icons");
                boolean found = false;

                try (InputStream is = getContentResolver().openInputStream(controlsArchiveUri)) {
                    if (is == null) throw new IllegalStateException("openInputStream returned null");
                    try (ZipInputStream zis = new ZipInputStream(is)) {
                        ZipEntry e;
                        byte[] buf = new byte[64 * 1024];

                        // Process every entry (do NOT stop at controls.json): we also extract the
                        // icons/ folder so user-supplied button/radial images travel with the layout.
                        while ((e = zis.getNextEntry()) != null) {
                            if (e.isDirectory()) continue;

                            String name = e.getName();
                            if (name == null) continue;
                            String lower = name.toLowerCase();

                            // Accept both "controls.json" and "something/controls.json"
                            if (lower.endsWith("controls.json")) {
                                try (OutputStream os = new FileOutputStream(outFile, false)) {
                                    int r;
                                    while ((r = zis.read(buf)) != -1) {
                                        os.write(buf, 0, r);
                                    }
                                    os.flush();
                                }
                                found = true;
                            } else if (lower.contains("icons/")) {
                                // Custom button/radial image. Use only the file name (no directory
                                // components) to guard against zip-slip path traversal.
                                String norm = name.replace('\\', '/');
                                String base = norm.substring(norm.lastIndexOf('/') + 1);
                                if (!base.isEmpty() && !base.contains("..")) {
                                    if (!iconsDir.exists()) iconsDir.mkdirs();
                                    File iconOut = new File(iconsDir, base);
                                    try (OutputStream os = new FileOutputStream(iconOut, false)) {
                                        int r;
                                        while ((r = zis.read(buf)) != -1) {
                                            os.write(buf, 0, r);
                                        }
                                        os.flush();
                                    }
                                }
                            }
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

    // -------------------- EXPORT CONTROLS FROM INSTANCE --------------------

    private void doExportControlsFromInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_controls);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);

        if (instanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        if (outUri == null) { finishWithError(taskTitle, "Output URI is missing"); return; }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + instanceName); return; }

        executorService.submit(() -> {
            try {
                File controlsDir = new File(gameInstance.getGamePath(), "controls");

                if (!controlsDir.exists()) {
                    // Nothing to export — default layout, controls/ was never created
                    finish(getString(R.string.dialog_title_controls_export_skipped_default), null);
                    return;
                }

                try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    ZipUtils.zipDirectoryToStream(controlsDir, os);
                }

                finish(getString(R.string.dialog_title_controls_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_controls), e.toString());
            }
        });
    }

    // -------------------- EXPORT SAVES FROM INSTANCE --------------------

    private void doExportSavesFromInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_saves);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);

        if (instanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        if (outUri == null) { finishWithError(taskTitle, "Output URI is missing"); return; }

        GameInstance gi = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gi == null) { finishWithError(taskTitle, "Game instance not found: " + instanceName); return; }

        executorService.submit(() -> {
            try {
                File savesDir = new File(gi.getHomePath() + "/Zomboid/Saves");
                if (!savesDir.exists() || !savesDir.isDirectory()) {
                    throw new IllegalArgumentException("Saves folder not found: " + savesDir);
                }

                try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    ZipUtils.zipDirectoryToStream(savesDir, os);
                }

                finish(getString(R.string.dialog_title_saves_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_saves), e.toString());
            }
        });
    }

    // -------------------- IMPORT CUSTOM DRIVER --------------------

    private void doImportCustomDriver(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_importing_driver);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        Uri driverUri = intent.getParcelableExtra(EXTRA_DRIVER_URI);
        if (driverUri == null) {
            finishWithError(taskTitle, "Driver URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                String destPath = AppStorage.requireSingleton().getHomePath()
                        + "/" + C.deps.CUSTOM_DRIVER;
                File destFile = new File(destPath);

                File parent = destFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                try (InputStream is = getContentResolver().openInputStream(driverUri);
                     OutputStream os = new java.io.FileOutputStream(destFile, false)) {
                    if (is == null) throw new IllegalStateException("openInputStream returned null");
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                }

                finish(getString(R.string.dialog_title_driver_imported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_import_driver), e.toString());
            }
        });
    }

    // -------------------- EXPORT CUSTOM DRIVER --------------------

    private void doExportCustomDriver(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_driver);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);
        if (outUri == null) {
            finishWithError(taskTitle, "Output URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                String srcPath = AppStorage.requireSingleton().getHomePath()
                        + "/" + C.deps.CUSTOM_DRIVER;
                File srcFile = new File(srcPath);

                if (!srcFile.exists()) {
                    finishWithError(taskTitle, "Custom driver file not found: " + srcPath);
                    return;
                }

                try (InputStream is = new java.io.FileInputStream(srcFile);
                     OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                }

                finish(getString(R.string.dialog_title_driver_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_driver), e.toString());
            }
        });
    }

    // -------------------- EXPORT LOG --------------------

    private void doExportLog(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_log);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);

        if (instanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        if (outUri == null) { finishWithError(taskTitle, "Output URI is missing"); return; }

        GameInstance gi = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gi == null) { finishWithError(taskTitle, "Game instance not found: " + instanceName); return; }

        executorService.submit(() -> {
            try {
                if (!hasAnyLogFiles(gi)) {
                    finishWithError(taskTitle, "No log files found");
                    return;
                }

                try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    writeLogReportZip(gi, os);
                }

                finish(getString(R.string.dialog_title_log_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_log), e.toString());
            }
        });
    }

    private static boolean hasAnyLogFiles(GameInstance gi) {
        File consoleFile = new File(gi.getHomePath() + "/Zomboid/console.txt");
        File launcherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LOG_FILE_NAME);
        File lastLauncherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LAST_LOG_FILE_NAME);
        return consoleFile.exists() || launcherLog.exists() || lastLauncherLog.exists();
    }

    // Builds the same diagnostic zip (report.txt + crash/native/shader/console/launcher logs) used
    // by both "Export logs" (SAF-picked destination) and the "Report a bug" email attachment, so
    // there is one definition of what a Zomdroid bug report contains. Caller owns/closes `os`.
    public static void writeLogReportZip(GameInstance gi, OutputStream os) throws IOException {
        File consoleFile = new File(gi.getHomePath() + "/Zomboid/console.txt");
        File launcherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LOG_FILE_NAME);
        // Crash session's logcat lives in lastlog.txt: after a native game crash the process
        // dies and the app restarts, which rotates log.txt -> lastlog.txt.
        File lastLauncherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LAST_LOG_FILE_NAME);
        // Native crash handler dump (SIGSEGV/SIGBUS/SIGILL/SIGFPE) written into the game dir.
        File crashFile = new File(gi.getGamePath() + "/crash.txt");
        // Persistent mirror of native stdout/stderr (box64 SEGV/BT reports, NG probes) —
        // survives crashes/restarts, unlike the rotating logcat.
        File nativeLog = new File(gi.getGamePath() + "/native.log");
        // NG_GL4ES shader diagnostics: full source + driver log of shaders that failed to
        // compile/link (up to 10 programs) + GL trace. Written by libng_gl4es into files/.
        File failedShaders = new File(AppStorage.requireSingleton().getHomePath() + "/failed_shaders.txt");
        File glTrace = new File(AppStorage.requireSingleton().getHomePath() + "/gl_trace.txt");
        // PZ's own per-session debug log. It is the ONLY place Java-side link/load failures are
        // recorded — an UnsatisfiedLinkError for a native game method never reaches console.txt,
        // which is why a broken game lib could go unnoticed across every report we ever received.
        // The live session writes straight into Zomboid/Logs/; on the next launch PZ rotates that
        // file into Logs/logs_<date>/, so after a crash-and-relaunch the interesting one is the
        // newest file in the newest archive folder. Collect both.
        File pzLogsDir = new File(gi.getHomePath() + "/Zomboid/Logs");
        File debugLog = newestDebugLog(pzLogsDir);
        File prevDebugLog = newestDebugLog(newestLogArchiveDir(pzLogsDir));

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os, 256 * 1024))) {
            // report.txt — device / build metadata
            LauncherPreferences prefs = LauncherPreferences.requireSingleton();
            LauncherPreferences.VulkanDriver driver = prefs.getVulkanDriver();
            String driverStr = driver.libName != null
                    ? driver.name() + " (" + driver.libName + ")"
                    : "system default";

            zos.putNextEntry(new ZipEntry("report.txt"));
            writeLogUtf8(zos, "=== Zomdroid Bug Report ===\n");
            writeLogUtf8(zos, "Device   : " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            writeLogUtf8(zos, "Android  : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            writeLogUtf8(zos, "Zomdroid : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
            writeLogUtf8(zos, "Renderer : " + prefs.getRenderer().name() + "\n");
            writeLogUtf8(zos, "Driver   : " + driverStr + "\n");
            writeLogUtf8(zos, "===========================\n");
            zos.closeEntry();

            // Original log files, verbatim — each kept whole in its own entry
            addFileToZip(zos, crashFile, "crash.txt");
            addFileToZip(zos, nativeLog, "native.log");
            addFileToZip(zos, failedShaders, "failed_shaders.txt");
            addFileToZip(zos, glTrace, "gl_trace.txt");
            addFileToZip(zos, consoleFile, "console.txt");
            addFileToZip(zos, launcherLog, "log.txt");
            addFileToZip(zos, lastLauncherLog, "lastlog.txt");
            addFileToZip(zos, debugLog, "debuglog.txt");
            addFileToZip(zos, prevDebugLog, "debuglog_prev.txt");
        }
    }

    // Newest "<date>_<time>_DebugLog.txt" directly inside `dir`, or null if there is none.
    private static File newestDebugLog(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        return newestOf(dir.listFiles((d, name) -> name.endsWith("_DebugLog.txt")));
    }

    // Newest "logs_<date>" folder that PZ rotates finished sessions into, or null.
    private static File newestLogArchiveDir(File logsDir) {
        if (logsDir == null || !logsDir.isDirectory()) return null;
        return newestOf(logsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("logs_")));
    }

    private static File newestOf(File[] candidates) {
        if (candidates == null) return null;
        File newest = null;
        for (File f : candidates) {
            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    // Adds a file to the zip under entryName. No-op if the file is missing.
    private static void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        if (file == null || !file.exists()) return;
        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) zos.write(buf, 0, r);
        }
        zos.closeEntry();
    }

    private static void writeLogUtf8(OutputStream os, String s) throws IOException {
        os.write(s.getBytes("UTF-8"));
    }

    private static void appendFileToStream(OutputStream os, File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) {
                os.write(buf, 0, r);
            }
        }
    }

    // -------------------- INSTALL BETTERFPS --------------------

    private void doInstallBetterFps(Intent intent) {
        String taskTitle = getString(R.string.optimization_betterfps_installing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        Uri archiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
        if (archiveUri == null) { finishWithError(taskTitle, "Archive URI is missing"); return; }

        // Mode is one of: "PotatoePC", "1080p", "4k" — maps to subfolder inside media/
        String mode = intent.getStringExtra(EXTRA_BETTERFPS_MODE);
        if (mode == null) mode = "PotatoePC";
        final String selectedMode = mode;

        executorService.submit(() -> {
            File tmpDir = new File(getCacheDir(), "betterfps_tmp_" + System.currentTimeMillis());
            try {
                tmpDir.mkdirs();

                // Step 1: Extract ZIP to temp (smart — handles double-wrapped archives)
                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                // Step 2: Find IsoChunkMap.class for the selected mode.
                // Expected path inside mod: media/<mode>/zombie/iso/IsoChunkMap.class
                // We search recursively so double-wrapped ZIPs are handled automatically.
                File classFile = findBetterFpsClass(tmpDir, selectedMode);
                if (classFile == null) {
                    finishWithError(taskTitle,
                            getString(R.string.optimization_betterfps_error_not_found, selectedMode));
                    return;
                }
                Log.d("BetterFPS", "Found class for mode=" + selectedMode + ": " + classFile.getAbsolutePath());

                // Step 3: Backup original IsoChunkMap.class if not already backed up
                String targetDir = gameInstance.getGamePath() + "/zombie/iso";
                File targetFile = new File(targetDir, "IsoChunkMap.class");
                File backupFile = new File(targetDir, "IsoChunkMap.class.original");
                new File(targetDir).mkdirs();

                if (targetFile.exists() && !backupFile.exists()) {
                    copyFile(targetFile, backupFile);
                    Log.d("BetterFPS", "Backup created: " + backupFile.getAbsolutePath());
                }

                // Step 4: Copy selected IsoChunkMap.class into game
                copyFile(classFile, targetFile);
                Log.d("BetterFPS", "Installed: " + targetFile.getAbsolutePath());

                finish(getString(R.string.optimization_betterfps_installed), null);

            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // Find IsoChunkMap.class for the given mode inside the extracted BetterFPS mod.
    // Looks for a path ending with: media/<mode>/zombie/iso/IsoChunkMap.class
    // Case-insensitive mode matching to handle any capitalisation differences.
    private File findBetterFpsClass(File dir, String mode) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findBetterFpsClass(f, mode);
                if (found != null) return found;
            } else if (f.getName().equals("IsoChunkMap.class")) {
                if (f.getAbsolutePath().toLowerCase().contains(mode.toLowerCase())) {
                    return f;
                }
            }
        }
        return null;
    }

    // -------------------- INSTALL RENDER LESS ZOMBIE (B41) --------------------
    //
    // "(Reduce Lag) RenderLessZombie" (Workshop 2970823607) ships patched zombie/iso classes in
    // media/<level>/zombie/iso/, one folder per zombie-count level (1/25/50/75/100/150). Unlike
    // BetterFPS this is a whole SET of files (IsoWorld.class + its inner classes), so every file
    // of the chosen level is copied and every original it replaces is kept as <name>.bak.

    private void doInstallRenderLessZombie(Intent intent) {
        String taskTitle = getString(R.string.optimization_rlz_installing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        Uri archiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
        if (archiveUri == null) { finishWithError(taskTitle, "Archive URI is missing"); return; }

        String level = intent.getStringExtra(EXTRA_RLZ_LEVEL);
        if (level == null || level.isEmpty()) level = "50";
        final String selectedLevel = level;

        executorService.submit(() -> {
            File tmpDir = new File(getCacheDir(), "rlz_tmp_" + System.currentTimeMillis());
            try {
                tmpDir.mkdirs();

                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                File srcDir = findRenderLessZombieLevelDir(tmpDir, selectedLevel);
                if (srcDir == null) {
                    finishWithError(taskTitle,
                            getString(R.string.optimization_rlz_error_not_found, selectedLevel));
                    return;
                }
                File[] srcFiles = srcDir.listFiles(File::isFile);
                if (srcFiles == null || srcFiles.length == 0) {
                    finishWithError(taskTitle,
                            getString(R.string.optimization_rlz_error_not_found, selectedLevel));
                    return;
                }
                Log.d("RenderLessZombie", "Level " + selectedLevel + " dir: " + srcDir.getAbsolutePath()
                        + " (" + srcFiles.length + " files)");

                File targetDir = new File(gameInstance.getGamePath(), "zombie/iso");
                targetDir.mkdirs();

                int copied = 0;
                for (File src : srcFiles) {
                    File target = new File(targetDir, src.getName());
                    File backup = new File(targetDir, src.getName() + ".bak");
                    // Back up the pristine original only once: re-installing another level must not
                    // overwrite the backup with already-patched classes.
                    if (target.exists() && !backup.exists()) {
                        copyFile(target, backup);
                        Log.d("RenderLessZombie", "Backup: " + backup.getName());
                    }
                    copyFile(src, target);
                    copied++;
                }
                Log.d("RenderLessZombie", "Installed " + copied + " file(s) into " + targetDir);

                finish(getString(R.string.optimization_rlz_installed, selectedLevel), null);

            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // Locate <any>/media/<level>/zombie/iso inside the extracted mod. The level is matched as a
    // whole path SEGMENT, never as a substring — "50" must not match the "150" folder.
    private File findRenderLessZombieLevelDir(File dir, String level) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            if (f.getName().equals(level)) {
                File iso = new File(f, "zombie/iso");
                if (iso.isDirectory()) return iso;
            }
            File found = findRenderLessZombieLevelDir(f, level);
            if (found != null) return found;
        }
        return null;
    }

    // ================================================
    // INSTALL_MOD_WITH_FIX
    //
    // Smart mod root detection (same as INSTALL_MOD_SMART) + forced inception copy for scripts/.
    // For Build 42: also merges 42.x version folders.
    // For Build 41: no merging, root files preserved.
    // ================================================

    private void doInstallModWithFix(Intent intent) {
        String taskTitle = getString(R.string.mod_fix_installing);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) {
            finishWithError(taskTitle, "Game instance name is missing");
            return;
        }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) {
            finishWithError(taskTitle, "Game instance not found: " + gameInstanceName);
            return;
        }

        Uri archiveUri = intent.getParcelableExtra(EXTRA_MODS_URI);
        if (archiveUri == null) {
            finishWithError(taskTitle, "Archive URI is missing");
            return;
        }

        // Determine install strategy based on instance build version
        String buildVersion = intent.getStringExtra(EXTRA_BUILD_VERSION);
        boolean isBuild42 = "42".equals(buildVersion);
        Log.d("ModFix", "Install strategy: build=" + buildVersion + ", isBuild42=" + isBuild42);
        Log.d("ModFix", "=== doInstallModWithFix START ===");
        Log.d("ModFix", "buildVersion=" + buildVersion + ", isBuild42=" + isBuild42);
        Log.d("ModFix", "archiveUri=" + archiveUri);

        executorService.submit(() -> {
            File tmpDir = new File(getCacheDir(), "mod_fix_tmp_" + System.currentTimeMillis());
            try {
                // Step 1: Extract ZIP to temp dir
                tmpDir.mkdirs();
                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[64 * 1024];
                                int r;
                                while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                            }
                        }
                        zis.closeEntry();
                    }
                }
                Log.d("ModFix", "Step 1 done. tmpDir contents:");
                File[] tmpContents = tmpDir.listFiles();
                if (tmpContents != null) {
                    for (File f : tmpContents) {
                        Log.d("ModFix", "  " + f.getName() + (f.isDirectory() ? "/" : " [file]"));
                    }
                } else {
                    Log.d("ModFix", "  tmpDir is empty or null!");
                }

                // Step 2: Find all mod roots using smart detection
                List<File> modRoots = new ArrayList<>();
                collectModRoots(tmpDir, modRoots);
                if (modRoots.isEmpty()) {
                    finishWithError(taskTitle, getString(R.string.install_mod_smart_no_root));
                    return;
                }
                Log.d("ModFix", "Found " + modRoots.size() + " mod root(s)");

                String modsPath = gameInstance.getHomePath() + "/Zomboid/mods";
                new File(modsPath).mkdirs();
                String instanceNameLower = gameInstance.getName().toLowerCase();
                String inceptionRelPath = "data/user/0/com.zomdroid/files/instances/"
                        + instanceNameLower + "/zomboid/mods";
                File inceptionDir = new File(modsPath, inceptionRelPath);

                for (File modRoot : modRoots) {
                    // Step 3: Determine mod name
                    String modName = modRoot.getName();
                    if (modName.equals(tmpDir.getName())) {
                        modName = extractZipName(archiveUri);
                        if (modName != null && modName.endsWith(".zip"))
                            modName = modName.substring(0, modName.length() - 4);
                    }
                    Log.d("ModFix", "Processing mod: " + modName + " (isBuild42=" + isBuild42 + ")");

                    // Step 4: Check if inception copy needed (scripts/ folder)
                    boolean needsInception = needsLowercaseFix(modRoot);
                    Log.d("ModFix", "  needsInception: " + needsInception);

                    // Step 5: Merge 42.x version folders if B42
                    //if (isBuild42) {
                    //    mergeVersionsForB42(modRoot);
                    //}

                    // Step 6: Install normal-case copy
                    File normalDest = new File(modsPath, modName);
                    if (normalDest.exists()) FileUtils.deleteDirectory(normalDest);
                    copyDirectory(modRoot, normalDest);
                    Log.d("ModFix", "  Installed normal: " + normalDest.getAbsolutePath());

                    // Step 7: If scripts/ present, also install lowercase inception copy
                    if (needsInception) {
                        inceptionDir.mkdirs();
                        String lowerName = modName.toLowerCase();
                        File lowerDest = new File(inceptionDir, lowerName);
                        if (lowerDest.exists()) FileUtils.deleteDirectory(lowerDest);
                        copyDirectoryLowercase(modRoot, lowerDest);
                        Log.d("ModFix", "  Installed lowercase: " + lowerDest.getAbsolutePath());
                    }
                }

                finish(getString(R.string.mod_fix_installed), null);
            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // -------------------- MOD FIX HELPERS --------------------

    // Merge all 42.x version folders into the latest, inject root media/ and common/,
    // then clean up root files (logo.png, poster.png, mod.info).
    // Used only for Build 42 installs.
    private void mergeVersionsForB42(File modDir) throws IOException {
        File[] entries = modDir.listFiles(File::isDirectory);
        if (entries == null) return;

        // Scan for version folders matching 42 or 42.x
        List<String> versions = new ArrayList<>();
        for (File f : entries) {
            if (f.getName().equals("42") || (f.getName().startsWith("42.") && f.getName().length() > 3)) {
                versions.add(f.getName());
            }
        }
        if (versions.isEmpty()) return;

        // Sort version-aware oldest → newest: 42 < 42.1 < 42.9 < 42.10 < 42.13
        versions.sort((a, b) -> {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int maxLen = Math.max(pa.length, pb.length);
            for (int i = 0; i < maxLen; i++) {
                int na = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int nb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (na != nb) return Integer.compare(na, nb);
            }
            return 0;
        });

        String latest = versions.get(versions.size() - 1);
        File target = new File(modDir, latest);
        target.mkdirs();

        // Merge older versions into latest, oldest first (no overwrite — newest wins)
        for (int i = 0; i < versions.size() - 1; i++) {
            File older = new File(modDir, versions.get(i));
            copyDirectoryNoOverwrite(older, target);
        }

        // Inject root media/ → latest/media/ (no overwrite)
        File rootMedia = new File(modDir, "media");
        if (rootMedia.exists() && rootMedia.isDirectory()) {
            copyDirectoryNoOverwrite(rootMedia, new File(target, "media"));
            FileUtils.deleteDirectory(rootMedia);
        }

        // Inject common/ → latest/ (no overwrite), then empty common/ but keep folder
        File rootCommon = new File(modDir, "common");
        if (rootCommon.exists() && rootCommon.isDirectory()) {
            copyDirectoryNoOverwrite(rootCommon, target);
            File[] commonContents = rootCommon.listFiles();
            if (commonContents != null) {
                for (File f : commonContents) FileUtils.deleteDirectory(f);
            }
            // Keep empty common/ folder — same behaviour as bash script
        }

        // Delete old version folders
        for (int i = 0; i < versions.size() - 1; i++) {
            FileUtils.deleteDirectory(new File(modDir, versions.get(i)));
        }

        // Clean up root files — 42/mod.info is now authoritative
        for (String name : new String[]{"logo.png", "poster.png", "mod.info"}) {
            File f = new File(modDir, name);
            if (f.exists()) f.delete();
        }
    }


    // Returns true if mod needs a lowercase inception copy:
    // - has a scripts/ folder (causes double-path issues on Android)
    private boolean needsLowercaseFix(File dir) {
        String name = dir.getName();
        if (name.equals("scripts") && dir.isDirectory()) return true;
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File f : children) {
            if (f.isDirectory() && needsLowercaseFix(f)) return true;
        }
        return false;
    }

    // Extract mod name from ZIP filename via ContentResolver
    private String extractZipName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                if (idx != -1) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}

        if (name != null && name.toLowerCase().endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name == null || name.isEmpty()) {
            name = "mod_" + System.currentTimeMillis();
        }
        return name;
    }

    // -------------------- GENERIC FILE HELPERS --------------------

    private static boolean isModFolder(File dir) {
        return dir != null && dir.isDirectory();
    }

    private static File[] listDirs(File root) {
        File[] dirs = root.listFiles(File::isDirectory);
        return (dirs == null) ? new File[0] : dirs;
    }

    private static void moveOrReplace(File srcDir, File dstDir) throws Exception {
        if (dstDir.exists()) {
            FileUtils.deleteDirectory(dstDir);
        }
        // Fast atomic move if on same storage partition
        java.nio.file.Files.move(
                srcDir.toPath(),
                dstDir.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
    }

    // Copy directory recursively
    private void copyDirectory(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) {
                copyDirectory(f, target);
            } else {
                copyFile(f, target);
            }
        }
    }

    // Copy directory recursively, all names lowercased
    private void copyDirectoryLowercase(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName().toLowerCase());
            if (f.isDirectory()) {
                copyDirectoryLowercase(f, target);
            } else {
                copyFile(f, target);
            }
        }
    }

    // Copy directory recursively, skip if destination file already exists
    private void copyDirectoryNoOverwrite(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) {
                copyDirectoryNoOverwrite(f, target);
            } else if (!target.exists()) {
                copyFile(f, target);
            }
        }
    }

    // Copy single file
    private void copyFile(File src, File dst) throws IOException {
        try (InputStream is = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
        }
    }

    // -------------------- BUILD-SPECIFIC PATCHES --------------------

    // Build 42.20+ layout:
    //   game/natives/*.so                       (Linux x86_64)
    //   game/natives/android/arm64-v8a/*.so    (Android arm64)
    //
    // Zomdroid's established layout is:
    //   game/*.so
    //   game/android/arm64-v8a/*.so
    //
    // Detect by structure rather than a textual version so later 42.x builds using the same
    // packaging are handled automatically. This must run before maybeDisableLibFor42(), whose
    // native-vs-Linux comparison deliberately expects the established layout.
    private void normalizeNativeLayoutFor4220(GameInstance gameInstance) throws IOException {
        File gameDir = new File(gameInstance.getGamePath());
        File newLinuxDir = new File(gameDir, "natives");
        File layoutMarker = new File(newLinuxDir, "libPZBullet64.so");
        if (!layoutMarker.isFile()) return;

        gameInstance.markBuild4220Plus();
        Log.i(LOG_TAG, "Detected Build 42.20+ native layout; normalizing for Zomdroid");

        File newAndroidDir = new File(newLinuxDir, "android/arm64-v8a");
        File oldAndroidDir = new File(gameDir, "android/arm64-v8a");

        // Move ARM64 first. moveSharedLibraries() only considers direct children, so the nested
        // Android files can never be mistaken for Linux x86_64 files.
        moveSharedLibraries(newAndroidDir, oldAndroidDir);
        moveSharedLibraries(newLinuxDir, gameDir);

        deleteDirectoryIfEmpty(newAndroidDir);
        deleteDirectoryIfEmpty(new File(newLinuxDir, "android"));
        deleteDirectoryIfEmpty(newLinuxDir);
    }

    private void moveSharedLibraries(File sourceDir, File targetDir) throws IOException {
        File[] libraries = sourceDir.listFiles(
                file -> file.isFile() && file.getName().endsWith(".so"));
        if (libraries == null || libraries.length == 0) return;
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create native library directory "
                    + targetDir.getAbsolutePath());
        }

        for (File source : libraries) {
            File target = new File(targetDir, source.getName());
            try {
                java.nio.file.Files.move(
                        source.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                // Same-filesystem moves are expected for an extracted instance, but retain a
                // copy/delete fallback for unusual Android storage implementations.
                copyFile(source, target);
                if (!source.delete()) {
                    throw new IOException("Copied but failed to remove "
                            + source.getAbsolutePath(), moveError);
                }
            }
            Log.i(LOG_TAG, "Normalized native library: " + source.getAbsolutePath()
                    + " -> " + target.getAbsolutePath());
        }
    }

    private void deleteDirectoryIfEmpty(File dir) {
        File[] children = dir.listFiles();
        if (children != null && children.length == 0 && !dir.delete()) {
            Log.w(LOG_TAG, "Failed to remove empty directory " + dir.getAbsolutePath());
        }
    }

    // 42.13: rename problematic native libs that crash on Android
    private void maybeDisableLibFor42(GameInstance gameInstance) {
        File gameDir = new File(gameInstance.getGamePath());
        File pzJar = new File(gameDir, "projectzomboid.jar");
        if (!pzJar.exists()) return; // Not a 42.13+ structure

        File soDir = new File(gameDir, "android/arm64-v8a");
        // Apply hard runtime findings (including Bullet's complete-looking but broken export).
        com.zomdroid.patch.NativeLibraryWorkarounds.disableIncompleteNativeLibraries(gameInstance);
        // The rest are judged on content instead, see below.
        disableIncompleteNativeLibs(gameInstance, gameDir, soDir);
    }

    // The Android builds TIS ships in android/arm64-v8a/ occasionally omit a JNI method their own
    // Linux build exports. 42.19's libPZPopMan64.so has no n_saveCell, so
    // ZombiePopulationManager.writeCellSnapshot died with UnsatisfiedLinkError, worlds were saved
    // incomplete, and every later load crashed — with nothing in console.txt to show for it.
    // dlopen() prefers the native lib, so an incomplete one is strictly worse than none: drop it
    // and let the x86_64 twin run under box64, which has the method.
    //
    // Content-based on purpose: no version numbers and no library names, so a build where TIS ships
    // a complete library keeps its native speed, and the next library with the same defect is
    // caught without a code change. Runs once per instance, here at install time — the game launch
    // path is untouched. Only reached for 42.12+ layouts (the projectzomboid.jar gate above), which
    // keeps it away from the multiplayer libs added by hand to 41.78 instances.
    private void disableIncompleteNativeLibs(GameInstance gameInstance, File gameDir, File soDir) {
        File[] nativeLibs = soDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (nativeLibs == null) return;

        for (File nativeLib : nativeLibs) {
            File linuxTwin = new File(gameDir, nativeLib.getName());
            if (!linuxTwin.exists()) continue; // nothing to compare against

            Set<String> expected = ElfSymbols.readExportedJniSymbols(linuxTwin);
            Set<String> present = ElfSymbols.readExportedJniSymbols(nativeLib);
            // null means "could not read" — never disable on a parse failure. An empty expected set
            // means the library registers its natives some other way and cannot be judged this way.
            if (expected == null || present == null || expected.isEmpty()) continue;

            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(present);
            if (missing.isEmpty()) continue;

            // Build 42.20's ARM64 Lighting library is safe after its sole missing void method is
            // replaced in LightingJNI.class. Its x86_64 twin crashes in getVisibleRooms() through
            // the box64 JNI bridge, so do not select that fallback.
            if ("libLighting64.so".equals(nativeLib.getName())
                    && missing.size() == 1
                    && missing.contains(
                    com.zomdroid.patch.LightingTransmissionPatcher.MISSING_JNI_SYMBOL)
                    && com.zomdroid.patch.LightingTransmissionPatchApplier.isApplied(
                    gameInstance)) {
                Log.i(LOG_TAG, "Keeping patched ARM64 libLighting64.so");
                continue;
            }

            Log.w(LOG_TAG, "Disabling " + nativeLib.getName() + ": the Android build is missing "
                    + missing.size() + " JNI method(s) exported by the Linux build, first: "
                    + missing.iterator().next());
            maybeDisableLib(soDir, nativeLib.getName());
        }
    }

    private void maybeDisableLib(File soDir, String libName) {
        File so = new File(soDir, libName);
        if (!so.exists()) return;

        File disabled = new File(soDir, libName + ".disabled");
        if (disabled.exists()) {
            //noinspection ResultOfMethodCallIgnored
            so.delete();
            return;
        }

        if (!so.renameTo(disabled)) {
            throw new RuntimeException("Failed to rename " + libName + " for 42.13: " + so.getAbsolutePath());
        }

        Log.i(LOG_TAG, "42.13 patch: disabled " + libName + " -> " + disabled.getName());
    }


    // B42: patch ShaderUnit to enable combineShaderSources (required for NG_GL4ES;
    // GLES forbids multi-unit linking, patched class uses PZ's own combineShaderSources mode).
    // Safe on ZINK — combineShaderSources is valid for any GL.
    //
    // The universal ShaderUnitPatcher finds the flag field BY NAME in the constant pool, so it
    // works on every game version — no md5 table, no pre-made replacement files. Also invoked
    // at game launch (GameLauncher) to cover instances created by older launcher versions.
    private void maybePatchShaderUnitCombine(GameInstance gameInstance) {
        com.zomdroid.patch.ShaderUnitPatchApplier.applyIfNeeded(gameInstance);
    }

    private static String md5Hex(File file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) md.update(buf, 0, r);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        }
    }

    // 42.15/42.17: patch printSpecs() crash on Android
    private void maybePatchPrintSpecsFor4215(GameInstance gameInstance) {
        File oshiDir = new File(gameInstance.getGamePath(), "oshi");
        if (!oshiDir.exists() || !oshiDir.isDirectory()) return;

        File target = new File(gameInstance.getGamePath(),
                "zombie/gameStates/MainScreenState.class");
        if (!target.exists()) return;

        File disabled = new File(gameInstance.getGamePath(),
                "zombie/gameStates/MainScreenState.class.disabled");

        // Already patched
        if (disabled.exists()) return;

        long classSize = target.length();
        String patchAsset;
        if (classSize >= 33100 && classSize <= 33500) {
            patchAsset = "patches/MainScreenState_42_17.class";
        } else if (classSize >= 32700 && classSize <= 33100) {
            patchAsset = "patches/MainScreenState_42_15.class";
        } else {
            Log.w(LOG_TAG, "Unknown MainScreenState version, size=" + classSize + ", skipping patch");
            return;
        }

        if (!target.renameTo(disabled)) {
            Log.e(LOG_TAG, "printSpecs patch: failed to rename original MainScreenState.class");
            return;
        }

        try (InputStream src = getAssets().open(patchAsset);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = src.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to apply printSpecs patch, asset=" + patchAsset, e);
            disabled.renameTo(target);
            return;
        }

        Log.i(LOG_TAG, "printSpecs patch applied: " + patchAsset + " (size=" + classSize + ")");
    }

    // 42.13+: extract projectzomboid.jar if present (new fat-jar structure)
    private void extractProjectZomboidJarSimple(GameInstance gameInstance) throws IOException {
        File gameDir = new File(gameInstance.getGamePath());
        File jar = new File(gameDir, "projectzomboid.jar");
        if (!jar.exists()) return;

        Log.i(LOG_TAG, "42.13+: extracting projectzomboid.jar");
        try (InputStream is = new FileInputStream(jar)) {
            FileUtils.extractZipToDisk(is, gameDir.getAbsolutePath(), this, jar.length());
        }
    }

    // -------------------- TASK STATE / NOTIFICATION --------------------

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

    // -------------------- GAME ROOT DRILL / UNWRAP --------------------
    // Files/dirs that mark the real Project Zomboid install root, across all builds:
    // desktop launcher script + its manifest, the 42.12+ fat jar, and the classes dir.
    private static final String[] GAME_ROOT_MARKERS = {
            "ProjectZomboid64.json", "ProjectZomboid64", "projectzomboid.jar", "zombie"
    };

    // True if dir DIRECTLY contains any PZ root marker.
    private boolean isGameRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            for (String marker : GAME_ROOT_MARKERS) {
                if (f.getName().equalsIgnoreCase(marker)) return true;
            }
        }
        return false;
    }

    // Recursively locate the game root (this dir or a descendant). null if none found.
    private File findGameRoot(File dir) {
        if (isGameRoot(dir)) return dir;
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) return null;
        for (File child : children) {
            File found = findGameRoot(child);
            if (found != null) return found;
        }
        return null;
    }

    // If the extracted game sits inside one or more wrapper folders, lift the real root up so
    // its contents live directly under gameDir. No-op if already flat or no PZ root is found.
    private void flattenGameRootIfWrapped(File gameDir) throws IOException {
        if (isGameRoot(gameDir)) return; // already flat
        File root = findGameRoot(gameDir);
        if (root == null || root.equals(gameDir)) return; // nothing PZ-like nested; launch check will report

        Log.i(LOG_TAG, "Game root nested at " + root + " — flattening into " + gameDir);

        File tmp = new File(gameDir.getParentFile(), gameDir.getName() + "__pzroot_tmp");
        if (tmp.exists()) FileUtils.deleteDirectory(tmp);

        // Move the nested root out of the wrapper tree (rename on same fs, copy as fallback)...
        if (!root.renameTo(tmp)) {
            copyDirectory(root, tmp);
        }
        // ...drop the leftover wrapper folders, then reinstate the root as gameDir.
        FileUtils.deleteDirectory(gameDir);
        if (!tmp.renameTo(gameDir)) {
            copyDirectory(tmp, gameDir);
            FileUtils.deleteDirectory(tmp);
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

    // INSTALL_MOD_SMART
    // Intelligently extracts a mod from ZIP regardless of wrapper folders.
    // Finds the mod root by looking for: mod.info file, media/ folder, or common/ folder.
    // Then applies needsLowercaseFix check and installs accordingly.
    private void doInstallModSmart(Intent intent) {
        String taskTitle = getString(R.string.install_mod_smart_title);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        executorService.execute(() -> {
            Uri archiveUri = intent.getParcelableExtra(EXTRA_MODS_URI);
            String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
            String buildVersion = intent.getStringExtra(EXTRA_BUILD_VERSION);
            boolean isBuild42 = "42".equals(buildVersion);

            File tmpDir = new File(getCacheDir(), "smart_mod_tmp_" + System.currentTimeMillis());
            try {
                tmpDir.mkdirs();

                // Step 1: Extract ZIP to temp
                onProgressUpdate(getString(R.string.extracting), -1, 0);
                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                // Step 2: Find mod root — folder containing mod.info, media/, or common/
                File modRoot = findModRoot(tmpDir);
                if (modRoot == null) {
                    finishWithError(taskTitle, getString(R.string.install_mod_smart_no_root));
                    return;
                }
                Log.d("SmartMod", "Found mod root: " + modRoot.getAbsolutePath());

                // Step 3: Determine mod name
                String modName = modRoot.getName();
                if (modName.equals(tmpDir.getName())) {
                    modName = extractZipName(archiveUri);
                    if (modName != null && modName.endsWith(".zip"))
                        modName = modName.substring(0, modName.length() - 4);
                }

                // Step 4: Check if inception copy needed
                boolean needsInception = needsLowercaseFix(modRoot);
                Log.d("SmartMod", "needsInception=" + needsInception + ", isBuild42=" + isBuild42);

                // Step 5: Merge 42.x version folders if B42
                //if (isBuild42) {
                //    mergeVersionsForB42(modRoot);
                //}

                // Step 6: Install normal-case copy
                GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
                if (gameInstance == null) {
                    finishWithError(taskTitle, "Game instance not found: " + instanceName);
                    return;
                }
                String modsPath = gameInstance.getHomePath() + "/Zomboid/mods";
                File modsDir = new File(modsPath);
                modsDir.mkdirs();

                File normalDest = new File(modsDir, modName);
                if (normalDest.exists()) FileUtils.deleteDirectory(normalDest);
                copyDirectory(modRoot, normalDest);
                Log.d("SmartMod", "Installed normal: " + normalDest.getAbsolutePath());

                // Expand common/ into each version folder so assets are accessible
                // (B42 looks in 42.x/ not in root, so common/ must be merged into each version folder)
                File commonDir = new File(normalDest, "common");
                if (commonDir.exists() && commonDir.isDirectory()) {
                    File[] versionDirs = normalDest.listFiles(File::isDirectory);
                    if (versionDirs != null) {
                        for (File vd : versionDirs) {
                            String name = vd.getName();
                            if (name.equals("42") || name.startsWith("42.") ||
                                name.equals("41") || name.startsWith("41.")) {
                                copyDirectoryNoOverwrite(commonDir, vd);
                                Log.d("SmartMod", "Expanded common/ into " + vd.getName());
                            }
                        }
                    }
                }

                // Step 7: If needed, install lowercase inception copy
                if (needsInception) {
                    String inceptionRelPath = "data/user/0/com.zomdroid/files/instances/"
                            + instanceName.toLowerCase() + "/zomboid/mods";
                    File inceptionDir = new File(modsDir, inceptionRelPath);
                    inceptionDir.mkdirs();
                    String lowerName = modName.toLowerCase();
                    File lowerDest = new File(inceptionDir, lowerName);
                    if (lowerDest.exists()) FileUtils.deleteDirectory(lowerDest);
                    copyDirectoryLowercase(modRoot, lowerDest);
                    Log.d("SmartMod", "Installed lowercase: " + lowerDest.getAbsolutePath());

                    // Expand common/ into version folders for inception copy too
                    File commonDirLower = new File(lowerDest, "common");
                    if (commonDirLower.exists() && commonDirLower.isDirectory()) {
                        File[] versionDirs = lowerDest.listFiles(File::isDirectory);
                        if (versionDirs != null) {
                            for (File vd : versionDirs) {
                                String name = vd.getName();
                                if (name.equals("42") || name.startsWith("42.") ||
                                    name.equals("41") || name.startsWith("41.")) {
                                    copyDirectoryNoOverwrite(commonDirLower, vd);
                                }
                            }
                        }
                    }
                }

                finish(getString(R.string.install_mod_smart_done), null);

            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // Find the mod root inside an extracted ZIP tree.
    // A valid mod root contains: mod.info, OR a media/ subfolder, OR a common/ subfolder.
    private File findModRoot(File dir) {
        if (isModRoot(dir)) return dir;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findModRoot(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isModRoot(File dir) {
        if (!dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isDirectory() && (name.equals("media") || name.equals("common"))) return true;
            if (f.isDirectory() && (name.equals("41") || name.equals("42") ||
                    name.startsWith("42.") || name.startsWith("41."))) return true;
            if (!f.isDirectory() && name.equals("mod.info")) return true;
        }
        return false;
    }

    // INSTALL_ETO
    // Installs Every Texture Optimized mod.
    // Finds media/textures inside the ZIP using smart detection + build version:
    //   B42: looks inside the latest 42.x subfolder → media/textures
    //   B41: looks at root → media/textures
    // Copies (overwrites) textures into the game's media/textures folder.
    private void doInstallEto(Intent intent) {
        String taskTitle = getString(R.string.optimization_eto_installing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        executorService.submit(() -> {
            Uri archiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
            String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
            String buildVersion = intent.getStringExtra(EXTRA_BUILD_VERSION);
            boolean isBuild42 = "42".equals(buildVersion);

            GameInstance gameInstance = GameInstanceManager.requireSingleton()
                    .getInstanceByName(gameInstanceName);
            if (gameInstance == null) {
                finishWithError(taskTitle, "Game instance not found: " + gameInstanceName);
                return;
            }

            File tmpDir = new File(getCacheDir(), "eto_tmp_" + System.currentTimeMillis());
            try {
                tmpDir.mkdirs();

                // Step 1: Extract ZIP to temp
                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                // Step 2: Find the right mod root.
                // If ZIP contains multiple mods, prefer the one with "performance" in the name.
                // Otherwise take the single mod found.
                File modRoot = findEtoModRoot(tmpDir);
                if (modRoot == null) {
                    finishWithError(taskTitle, getString(R.string.install_mod_smart_no_root));
                    return;
                }
                Log.d("ETO", "Using mod root: " + modRoot.getAbsolutePath());

                // Step 3: Find media/textures inside the chosen mod root.
                // For B42: look inside the latest 42.x subfolder first.
                // For B41: look directly at mod root.
                File texturesSource = findEtoTexturesFolder(modRoot, isBuild42);

                // Step 4: Validate BEFORE touching game files — fail fast if wrong mod/build.
                if (texturesSource == null || !texturesSource.isDirectory()) {
                    finishWithError(taskTitle, getString(R.string.optimization_eto_error_no_textures));
                    return;
                }
                Log.d("ETO", "Textures source: " + texturesSource.getAbsolutePath());

                // Step 5: Backup original textures folder if not already backed up.
                // We copy (not rename) so the original textures remain intact.
                // Backup happens only AFTER we confirmed textures source is valid.
                File gameTextures = new File(gameInstance.getGamePath(), "media/textures");
                File gameTexturesBak = new File(gameInstance.getGamePath(), "media/textures.bak");
                if (gameTextures.isDirectory() && !gameTexturesBak.exists()) {
                    Log.d("ETO", "Backing up original textures...");
                    copyDirectory(gameTextures, gameTexturesBak);
                    Log.d("ETO", "Backup done: " + gameTexturesBak.getAbsolutePath());
                }

                // Step 6: Copy ETO textures on top of existing textures folder.
                // Original files not present in ETO remain untouched.
                gameTextures.mkdirs();
                copyDirectory(texturesSource, gameTextures);
                Log.d("ETO", "Installed to: " + gameTextures.getAbsolutePath());

                finish(getString(R.string.optimization_eto_installed), null);

            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // Find the best ETO mod root inside the extracted ZIP.
    // Reads mod.info to get the mod id and selects by priority:
    //
    // B42 priority: Performance > Optimal > anything else (skip Hotfix)
    // B41 priority: ETO_Performance_mode > ETO_Balanced_mode > ETO_Quality_mode
    //               > ETO_FPS > anything else (skip ETO_Hotfix)
    //
    // If only one non-hotfix mod found — use it regardless of id.
    private File findEtoModRoot(File tmpDir) {
        List<File> roots = new ArrayList<>();
        collectModRoots(tmpDir, roots);

        if (roots.isEmpty()) return null;

        // Filter out hotfix mods
        List<File> candidates = new ArrayList<>();
        for (File root : roots) {
            String id = readModId(root);
            if (id != null && id.toLowerCase().contains("hotfix")) {
                Log.d("ETO", "Skipping hotfix mod: " + id);
                continue;
            }
            candidates.add(root);
        }

        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        // Multiple candidates — select by priority
        String[] priority = {
                "ETO_Performance_mode", "Performance",
                "ETO_Balanced_mode",
                "ETO_Quality_mode",
                "Optimal",
                "ETO_FPS"
        };

        for (String preferred : priority) {
            for (File root : candidates) {
                String id = readModId(root);
                if (preferred.equalsIgnoreCase(id)) {
                    Log.d("ETO", "Selected by priority id=" + id + ": " + root.getName());
                    return root;
                }
            }
        }

        // No priority match — return first candidate
        Log.d("ETO", "No priority match, using first: " + candidates.get(0).getName());
        return candidates.get(0);
    }

    // Read the "id" field from mod.info inside a mod root folder.
    // Returns null if mod.info not found or id field missing.
    private String readModId(File modRoot) {
        File modInfo = new File(modRoot, "mod.info");
        if (!modInfo.isFile()) return null;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(modInfo))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("id=")) {
                    return line.substring(3).trim();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    // Collect all mod roots (containing mod.info, media/ or common/) into the list.
    private void collectModRoots(File dir, List<File> result) {
        if (isModRoot(dir)) {
            result.add(dir);
            return; // don't recurse into a mod root
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectModRoots(child, result);
        }
    }

    // Find the media/textures folder inside the chosen ETO mod root.
    // For B42: navigate into the latest 42.x subfolder first.
    // For B41: look directly at mod root level.
    private File findEtoTexturesFolder(File modRoot, boolean isBuild42) {
        if (isBuild42) {
            File latestVersionFolder = findLatestB42FolderIn(modRoot);
            if (latestVersionFolder != null) {
                File textures = new File(latestVersionFolder, "media/textures");
                if (textures.isDirectory()) return textures;
            }
        }
        // B41 or fallback: media/textures directly at mod root
        File textures = new File(modRoot, "media/textures");
        if (textures.isDirectory()) return textures;
        // Last resort: search anywhere under mod root
        return findTexturesFolderRecursive(modRoot);
    }

    // Find the subfolder with the highest 42.x version number directly under dir.
    private File findLatestB42FolderIn(File dir) {
        File best = null;
        double bestVersion = -1;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            String name = f.getName();
            if (name.equals("42") || (name.startsWith("42.") && name.length() > 3)) {
                try {
                    double v = Double.parseDouble(name);
                    if (v > bestVersion) {
                        bestVersion = v;
                        best = f;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    private File findTexturesFolderRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equals("textures")) return f;
                File found = findTexturesFolderRecursive(f);
                if (found != null) return found;
            }
        }
        return null;
    }

    // -------------------- INSTALL ZOMBIEBUDDY --------------------
    // Extracts ZombieBuddy.jar from ZIP and copies it to the JARS dependencies folder.
    // Enables the zombiebuddy_enabled flag in SharedPreferences automatically.
    private void doInstallZombieBuddy(Intent intent) {
        String taskTitle = getString(R.string.optimization_zombiebuddy_installing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        executorService.submit(() -> {
            Uri archiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
            if (archiveUri == null) { finishWithError(taskTitle, "Archive URI is missing"); return; }

            File tmpDir = new File(getCacheDir(), "zb_tmp_" + System.currentTimeMillis());
            try {
                tmpDir.mkdirs();

                // Extract ZIP
                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) { outFile.mkdirs(); }
                        else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192]; int len;
                                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                // Find ZombieBuddy.jar recursively
                File jarFile = findFileRecursive(tmpDir, "ZombieBuddy.jar");
                if (jarFile == null) {
                    finishWithError(taskTitle, "ZombieBuddy.jar not found in archive");
                    return;
                }

                // Copy jar to game/ folder of the instance
                File destFile = new File(gameInstance.getGamePath(), C.deps.ZOMBIE_BUDDY_JAR);
                copyFile(jarFile, destFile);
                Log.d("ZombieBuddy", "Jar installed to: " + destFile.getAbsolutePath());

                // Install mod folder to instance mods folder
                File modRoot = findModRoot(tmpDir);
                if (modRoot != null) {
                    String modName = modRoot.getName();
                    if (modName.equals(tmpDir.getName())) modName = "ZombieBuddy";
                    String modsPath = gameInstance.getHomePath() + "/Zomboid/mods";
                    new File(modsPath).mkdirs();
                    File modDest = new File(modsPath, modName);
                    if (modDest.exists()) FileUtils.deleteDirectory(modDest);
                    copyDirectory(modRoot, modDest);
                    Log.d("ZombieBuddy", "Mod installed to: " + modDest.getAbsolutePath());
                }

                // Enable flag for this instance
                getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                        .edit().putBoolean("zombiebuddy_enabled_" + gameInstanceName, true).apply();

                finish(getString(R.string.optimization_zombiebuddy_installed), null);

            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // -------------------- INSTALL ZBBETTERFPS --------------------
    // Extracts ZBBetterFPS.jar, copies to JARS folder, comments out javaJarFile in mod.info,
    // and installs the mod to the game instance mods folder.
    // Enables the zbbetterfps_enabled flag in SharedPreferences automatically.
    private void doInstallZbBetterFps(Intent intent) {
        String taskTitle = getString(R.string.optimization_zbbetterfps_installing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        executorService.submit(() -> {
            Uri archiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
            if (archiveUri == null) { finishWithError(taskTitle, "Archive URI is missing"); return; }

            File tmpDir = new File(getCacheDir(), "zbbfps_tmp_" + System.currentTimeMillis());
            try {
                tmpDir.mkdirs();

                // Extract ZIP
                try (InputStream is = getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(tmpDir, entry.getName());
                        if (entry.isDirectory()) { outFile.mkdirs(); }
                        else {
                            outFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192]; int len;
                                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                // Find mod root
                File modRoot = findModRoot(tmpDir);
                if (modRoot == null) {
                    finishWithError(taskTitle, getString(R.string.install_mod_smart_no_root));
                    return;
                }

                // Install mod to instance mods folder as-is
                String modName = modRoot.getName();
                if (modName.equals(tmpDir.getName())) {
                    modName = extractZipName(archiveUri);
                    if (modName != null && modName.endsWith(".zip"))
                        modName = modName.substring(0, modName.length() - 4);
                }
                String modsPath = gameInstance.getHomePath() + "/Zomboid/mods";
                new File(modsPath).mkdirs();
                File modDest = new File(modsPath, modName);
                if (modDest.exists()) FileUtils.deleteDirectory(modDest);
                copyDirectory(modRoot, modDest);
                Log.d("ZBBetterFPS", "Mod installed to: " + modDest.getAbsolutePath());

                // Replace ZBBetterFPS.jar with our Java 21 compatible version.
                // For B41: replace only in 41/ subfolder.
                // For B42: replace in all 42.x/ subfolders (ZombieBuddy picks the right one).
                boolean isBuild42 = "42".equals(intent.getStringExtra(EXTRA_BUILD_VERSION));
                replaceZbBetterFpsJars(modDest, isBuild42);

                // Enable flag for this instance
                getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                        .edit().putBoolean("zbbetterfps_enabled_" + gameInstanceName, true).apply();

                finish(getString(R.string.optimization_zbbetterfps_installed), null);

            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            } finally {
                try { FileUtils.deleteDirectory(tmpDir); } catch (Exception ignored) {}
            }
        });
    }

    // Find a file by name recursively inside a directory
    private File findFileRecursive(File dir, String fileName) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(fileName)) return f;
            if (f.isDirectory()) {
                File found = findFileRecursive(f, fileName);
                if (found != null) return found;
            }
        }
        return null;
    }

    // Recursively find and comment out javaJarFile= in all mod.info files
    private void commentOutJavaJarFileRecursive(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                commentOutJavaJarFileRecursive(f);
            } else if (f.getName().equals("mod.info")) {
                commentOutJavaJarFile(f);
            }
        }
    }

    // Comment out javaJarFile= line in mod.info so ZombieBuddy skips addURL
    private void commentOutJavaJarFile(File modInfo) throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(modInfo))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("javaJarFile=")) {
                    lines.add("// " + line);
                } else {
                    lines.add(line);
                }
            }
        }
        try (java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.FileWriter(modInfo, false))) {
            for (String line : lines) {
                bw.write(line);
                bw.newLine();
            }
        }
    }

    // Replace ZBBetterFPS.jar in the installed mod with our Java 21 compatible version.
    // For B41: only replaces in 41/ subfolder.
    // For B42: replaces in all 42.x/ subfolders.
    private void replaceZbBetterFpsJars(File modDir, boolean isBuild42) throws IOException {
        // Only replace jars in 42.x folders — the 41/ folder has its own compatible jar.
        if (!isBuild42) return;
        File[] children = modDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            boolean isB42Folder = name.equals("42") || name.startsWith("42.");
            if (isB42Folder) {
                File jar = findFileRecursive(child, "ZBBetterFPS.jar");
                if (jar != null) {
                    File backup = new File(jar.getParent(), "ZBBetterFPS.jar.ver25");
                    jar.renameTo(backup);
                    Log.d("ZBBetterFPS", "Backed up: " + backup.getAbsolutePath());
                    try (InputStream assetIs = getAssets().open("patches/ZBBetterFPS.jar.ver21");
                         FileOutputStream fos = new FileOutputStream(jar)) {
                        byte[] buf = new byte[8192]; int len;
                        while ((len = assetIs.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    Log.d("ZBBetterFPS", "Replaced with Java 21 jar: " + jar.getAbsolutePath());
                }
            }
        }
    }

    // -------------------- IMPORT GAME SETTINGS --------------------
    // Imports options.ini from user-selected file into Zomboid/ folder of the instance.
    // File is always saved as options.ini regardless of original name.
    private void doImportGameSettings(Intent intent) {
        String taskTitle = getString(R.string.game_settings_importing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        Uri iniUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
        if (iniUri == null) { finishWithError(taskTitle, "File URI is missing"); return; }

        executorService.submit(() -> {
            try {
                // Ensure Zomboid/ folder exists
                File zomboidDir = new File(gameInstance.getHomePath(), "Zomboid");
                zomboidDir.mkdirs();

                File destFile = new File(zomboidDir, "options.ini");
                try (InputStream is = getContentResolver().openInputStream(iniUri);
                     OutputStream os = new FileOutputStream(destFile, false)) {
                    if (is == null) throw new IllegalStateException("openInputStream returned null");
                    byte[] buf = new byte[64 * 1024]; int r;
                    while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                }
                Log.d("GameSettings", "Imported options.ini to: " + destFile.getAbsolutePath());
                finish(getString(R.string.game_settings_imported), null);
            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            }
        });
    }

    // -------------------- EXPORT GAME SETTINGS --------------------
    // Exports options.ini from Zomboid/ folder to user-selected output file.
    private void doExportGameSettings(Intent intent) {
        String taskTitle = getString(R.string.game_settings_exporting);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);

        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        if (outUri == null) { finishWithError(taskTitle, "Output URI is missing"); return; }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        executorService.submit(() -> {
            try {
                File iniFile = new File(gameInstance.getHomePath(), "Zomboid/options.ini");
                if (!iniFile.exists()) {
                    finishWithError(taskTitle, getString(R.string.game_settings_not_found));
                    return;
                }
                try (InputStream is = new java.io.FileInputStream(iniFile);
                     OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    byte[] buf = new byte[64 * 1024]; int r;
                    while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                }
                Log.d("GameSettings", "Exported options.ini");
                finish(getString(R.string.game_settings_exported), null);
            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            }
        });
    }

    // -------------------- INSTALL NATIVE LIBS --------------------
    // Extracts .so files from ZIP into game/android/arm64-v8a folder.
    // Used to add multiplayer libraries (libRakNet64.so, libZNetNoSteam64.so) to B41 instances.
    private void doInstallNativeLibs(Intent intent) {
        String taskTitle = getString(R.string.native_libs_installing);
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + gameInstanceName); return; }

        Uri archiveUri = intent.getParcelableExtra(EXTRA_NATIVE_LIBS_URI);
        if (archiveUri == null) { finishWithError(taskTitle, "Archive URI is missing"); return; }

        executorService.submit(() -> {
            try {
                String nativeLibsPath = gameInstance.getGamePath() + "/android/arm64-v8a";
                File nativeLibsDir = new File(nativeLibsPath);
                nativeLibsDir.mkdirs();

                try (InputStream is = getContentResolver().openInputStream(archiveUri)) {
                    if (is == null) throw new IllegalStateException("openInputStream returned null");
                    FileUtils.extractZipToDisk(is, nativeLibsPath, this,
                            FileUtils.queryFileSize(getContentResolver(), archiveUri));
                }
                Log.i(LOG_TAG, "Native libs installed to: " + nativeLibsPath);
                finish(getString(R.string.native_libs_installed), null);
            } catch (Exception e) {
                finishWithError(taskTitle, e.toString());
            }
        });
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
        this.taskState.postValue(new TaskState(
                currentState == null ? null : currentState.title,
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

    public LiveData<TaskState> getTaskState() {
        return taskState;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public String getCurrentInstallPresetName() {
        return currentInstallPresetName;
    }

    public String getCurrentGpuVendor() {
        return currentGpuVendor;
    }

    public class LocalBinder extends Binder {
        public InstallerService getService() {
            return InstallerService.this;
        }
    }

    // -------------------- ENUMS / DATA CLASSES --------------------

    public enum Task {
        CREATE_GAME_INSTANCE,
        DELETE_GAME_INSTANCE,
        INSTALL_DEPENDENCIES,
        INSTALL_MOD_TO_INSTANCE,
        INSTALL_CONTROLS_TO_INSTANCE,
        INSTALL_SAVES_TO_INSTANCE,
        EXPORT_SAVES_FROM_INSTANCE,
        EXPORT_CONTROLS_FROM_INSTANCE,
        IMPORT_CUSTOM_DRIVER,
        EXPORT_CUSTOM_DRIVER,
        EXPORT_LOG,
        INSTALL_BETTERFPS,
        INSTALL_MOD_WITH_FIX,
        INSTALL_MOD_SMART,
        INSTALL_ETO,
        INSTALL_ZOMBIEBUDDY,
        INSTALL_ZBBETTERFPS,
        IMPORT_GAME_SETTINGS,
        EXPORT_GAME_SETTINGS,
        INSTALL_NATIVE_LIBS,
        INSTALL_RENDER_LESS_ZOMBIE
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
