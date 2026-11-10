package com.zomdroid.fragments;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.AppStorage;
import com.zomdroid.C;
import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentTransferBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Every import and export in one screen. It used to be five drawer screens (saves, mods, game
 * settings, controls, driver), each with its own instance picker and its own file field. Now the
 * instance is picked once at the top and each card has an Import and an Export button; Import picks
 * the file and asks once before it overwrites anything. The work itself is unchanged: the same
 * InstallerService tasks the old screens started.
 */
public class TransferFragment extends Fragment {

    private static final String LOG_TAG = TransferFragment.class.getName();
    private static final String ZIP_MIME = "application/zip";

    /** What the pending system picker is for; kept across a recreation of this screen. */
    private static final String STATE_PENDING = "pending";
    private static final String STATE_GAME_FILES = "game_files";
    private static final int NONE = 0, SAVES = 1, MODS = 2, GAME_FILES = 3, CONTROLS = 4, DRIVER = 5;

    private FragmentTransferBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;
    // Kept so a finished task stops the service it ran in, not whatever is started later.
    private InstallerService installerService;

    private List<GameInstance> instances = new ArrayList<>();
    private int pending = NONE;
    /** One of InstallerService.GAME_FILES_*. */
    private int gameFilesKind = InstallerService.GAME_FILES_OPTIONS;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            installerService = binder.getService();
            isInstallerServiceBound = true;
            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(TransferFragment.this, TransferFragment.this::handleTaskState);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.e(LOG_TAG, "Connection to installer service has been lost");
            isInstallerServiceBound = false;
            if (taskProgressDialog != null) taskProgressDialog.dismiss();
        }
    };

    // ZIP imports (saves, mods, controls)
    private final ActivityResultLauncher<String> pickZip =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImportPicked);
    // Files that are not always a ZIP (game settings: .ini/.cfg; driver: .so)
    private final ActivityResultLauncher<String[]> pickDocument =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportPicked);
    // Exports: the ZIP (or .so) to write
    private final ActivityResultLauncher<String> createZip =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(ZIP_MIME), this::onExportTarget);
    private final ActivityResultLauncher<String> createSo =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), this::onExportTarget);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentTransferBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            pending = savedInstanceState.getInt(STATE_PENDING, NONE);
            gameFilesKind = savedInstanceState.getInt(STATE_GAME_FILES, InstallerService.GAME_FILES_OPTIONS);
        }

        taskProgressDialogBinding = TaskProgressDialogBinding.inflate(getLayoutInflater());
        taskProgressDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(taskProgressDialogBinding.getRoot())
                .setCancelable(false)
                .create();
        taskProgressDialogBinding.progressDialogOkMb.setOnClickListener(v -> taskProgressDialog.dismiss());

        setUpInstancePicker();

        // Saves
        binding.transferSavesImportBtn.setOnClickListener(v -> pickZipFor(SAVES));
        binding.transferSavesExportBtn.setOnClickListener(v -> {
            if (selectedInstanceOrToast() == null) return;
            pending = SAVES;
            createZip.launch("zomdroid_saves_" + stamp() + ".zip");
        });

        // Mods: import here; export, copying and Steam downloads live on the Mods screen
        binding.transferModsImportBtn.setOnClickListener(v -> pickZipFor(MODS));
        binding.transferModsOpenBtn.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_mods));

        // Game settings
        binding.transferGameFilesGroup.check(gameFilesKind == InstallerService.GAME_FILES_SANDBOX
                ? R.id.transfer_game_files_sandbox
                : gameFilesKind == InstallerService.GAME_FILES_BUILDS ? R.id.transfer_game_files_builds
                : R.id.transfer_game_files_options);
        binding.transferGameFilesGroup.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.transfer_game_files_options : ids.get(0);
            gameFilesKind = id == R.id.transfer_game_files_sandbox ? InstallerService.GAME_FILES_SANDBOX
                    : id == R.id.transfer_game_files_builds ? InstallerService.GAME_FILES_BUILDS
                    : InstallerService.GAME_FILES_OPTIONS;
            updateGameFilesHint();
        });
        updateGameFilesHint();
        binding.transferGameFilesImportBtn.setOnClickListener(v -> {
            if (selectedInstanceOrToast() == null) return;
            pending = GAME_FILES;
            pickDocument.launch(new String[]{"*/*"});
        });
        binding.transferGameFilesExportBtn.setOnClickListener(v -> {
            if (selectedInstanceOrToast() == null) return;
            String prefix = gameFilesKind == InstallerService.GAME_FILES_SANDBOX ? "sandbox_presets_"
                    : gameFilesKind == InstallerService.GAME_FILES_BUILDS ? "character_builds_" : "options_";
            pending = GAME_FILES;
            createZip.launch(prefix + stamp() + ".zip");
        });

        // Controls
        binding.transferControlsImportBtn.setOnClickListener(v -> pickZipFor(CONTROLS));
        binding.transferControlsExportBtn.setOnClickListener(v -> {
            GameInstance gi = selectedInstanceOrToast();
            if (gi == null) return;
            if (!hasControlsToExport(gi)) {
                Toast.makeText(requireContext(), R.string.dialog_title_controls_export_skipped_default, Toast.LENGTH_SHORT).show();
                return;
            }
            pending = CONTROLS;
            createZip.launch("zomdroid_controls_" + stamp() + ".zip");
        });
        binding.transferControlsHelpIb.setOnClickListener(v ->
                showFormatHelp(R.string.install_controls_zip_help_title, R.string.install_controls_zip_help_message));

        // Driver: app-wide, no instance needed
        binding.transferDriverImportBtn.setOnClickListener(v -> {
            pending = DRIVER;
            pickDocument.launch(new String[]{"application/octet-stream", "*/*"});
        });
        binding.transferDriverExportBtn.setOnClickListener(v -> {
            pending = DRIVER;
            createSo.launch("custom_driver_" + stamp() + ".so");
        });
        updateDriverExport();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PENDING, pending);
        outState.putInt(STATE_GAME_FILES, gameFilesKind);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDriverExport();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbindInstallerService();
        binding = null;
    }

    // -------------------- instance --------------------

    private void setUpInstancePicker() {
        binding.transferBannerIv.setImageResource(R.drawable.banner_default);
        List<GameInstance> all = GameInstanceManager.requireSingleton().getInstances();
        instances = all == null ? new ArrayList<>() : all;
        List<String> names = new ArrayList<>();
        if (instances.size() != 1) names.add(getString(R.string.select_instance));
        for (GameInstance gi : instances) names.add(gi.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.transferInstanceSpinner.setAdapter(adapter);
        binding.transferInstanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showBanner(selectedInstance());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                showBanner(null);
            }
        });
        if (instances.size() == 1) binding.transferInstanceSpinner.setSelection(0);
    }

    private void showBanner(@Nullable GameInstance gi) {
        if (binding == null) return;
        if (gi == null) {
            binding.transferBannerIv.setImageResource(R.drawable.banner_default);
            binding.transferBannerOverlay.setVisibility(View.INVISIBLE);
            return;
        }
        String preset = gi.getPresetName();
        binding.transferBannerIv.setImageResource("Build 42.12+".equals(preset) ? R.drawable.banner_build42_12
                : "Build 42".equals(preset) ? R.drawable.banner_build42 : R.drawable.banner_build41);
        binding.transferBannerOverlay.setVisibility(View.VISIBLE);
    }

    @Nullable
    private GameInstance selectedInstance() {
        if (binding == null) return null;
        int position = binding.transferInstanceSpinner.getSelectedItemPosition();
        int index = instances.size() == 1 ? position : position - 1;
        return index >= 0 && index < instances.size() ? instances.get(index) : null;
    }

    @Nullable
    private GameInstance selectedInstanceOrToast() {
        GameInstance gi = selectedInstance();
        if (gi == null) Toast.makeText(requireContext(), R.string.select_instance, Toast.LENGTH_SHORT).show();
        return gi;
    }

    // -------------------- import --------------------

    private void pickZipFor(int kind) {
        if (selectedInstanceOrToast() == null) return;
        pending = kind;
        pickZip.launch(ZIP_MIME);
    }

    private void onImportPicked(@Nullable Uri uri) {
        int kind = pending;
        pending = NONE;
        if (uri == null || binding == null) return;
        String name = fileName(uri);
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);

        if (kind == DRIVER) {
            if (!lower.endsWith(".so")) {
                Toast.makeText(requireContext(), R.string.install_driver_wrong_extension, Toast.LENGTH_SHORT).show();
                return;
            }
            confirmImport(name, null, R.string.install_driver_warning, () -> {
                Intent i = task(InstallerService.Task.IMPORT_CUSTOM_DRIVER, null);
                i.putExtra(InstallerService.EXTRA_DRIVER_URI, uri);
                start(i);
            });
            return;
        }

        GameInstance gi = selectedInstanceOrToast();
        if (gi == null) return;

        if (kind == GAME_FILES) {
            // Everything travels as .zip; a bare options.ini or .cfg is taken as well.
            boolean accepted = lower.endsWith(".zip")
                    || (gameFilesKind == InstallerService.GAME_FILES_OPTIONS && lower.endsWith(".ini"))
                    || (gameFilesKind == InstallerService.GAME_FILES_SANDBOX && lower.endsWith(".cfg"));
            if (!accepted) {
                Toast.makeText(requireContext(), R.string.game_settings_wrong_format, Toast.LENGTH_SHORT).show();
                return;
            }
            final int filesKind = gameFilesKind;
            confirmImport(name, gi, gameFilesImportHint(filesKind), () -> {
                Intent i = task(InstallerService.Task.IMPORT_GAME_SETTINGS, gi);
                i.putExtra(InstallerService.EXTRA_ARCHIVE_URI, uri);
                i.putExtra(InstallerService.EXTRA_GAME_FILES_KIND, filesKind);
                start(i);
            });
            return;
        }

        ContentResolver cr = requireContext().getContentResolver();
        if (!Objects.equals(cr.getType(uri), ZIP_MIME)) {
            Toast.makeText(requireContext(), R.string.game_instance_unsupported_extension, Toast.LENGTH_SHORT).show();
            return;
        }
        if (kind == SAVES) {
            confirmImport(name, gi, 0, () -> {
                Intent i = task(InstallerService.Task.INSTALL_SAVES_TO_INSTANCE, gi);
                i.putExtra(InstallerService.EXTRA_SAVES_URI, uri);
                start(i);
            });
        } else if (kind == CONTROLS) {
            confirmImport(name, gi, R.string.install_controls_zip_warning, () -> {
                Intent i = task(InstallerService.Task.INSTALL_CONTROLS_TO_INSTANCE, gi);
                i.putExtra(InstallerService.EXTRA_CONTROLS_URI, uri);
                start(i);
            });
        } else if (kind == MODS) {
            confirmImport(name, gi, R.string.install_mod_smart_hint, () -> warnIfHelperModThen(uri, name, () -> {
                Intent i = task(InstallerService.Task.INSTALL_MOD_WITH_FIX, gi);
                i.putExtra(InstallerService.EXTRA_BUILD_VERSION, gi.getBuildVersion());
                i.putExtra(InstallerService.EXTRA_MODS_URI, uri);
                start(i);
            }));
        }
    }

    /**
     * One question before anything is written: which file, into which instance, and (note, 0 for
     * none) what it does.
     */
    private void confirmImport(@Nullable String fileName, @Nullable GameInstance gi, @StringRes int note, Runnable go) {
        String file = fileName != null ? fileName : "?";
        String question = gi != null
                ? getString(R.string.transfer_import_confirm, file, gi.getName())
                : getString(R.string.transfer_import_confirm_app, file);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_label)
                .setMessage(note != 0 ? question + "\n\n" + getString(note) : question)
                .setPositiveButton(R.string.import_label, (d, w) -> go.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A mod the Optimization screen installs itself works better from there (adapted to Android);
     * say so before importing it plainly. Same check as the Import mods screen: by the zip's name
     * first, then by reading it off the UI thread. "Import anyway" goes on as before.
     */
    private void warnIfHelperModThen(Uri zipUri, @Nullable String name, Runnable proceed) {
        Integer byName = com.zomdroid.optimization.HelperMods.byFileName(name);
        if (byName != null) {
            showHelperModDialog(byName, proceed);
            return;
        }
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            Integer found = null;
            try (InputStream in = app.getContentResolver().openInputStream(zipUri)) {
                if (in != null) found = com.zomdroid.optimization.HelperMods.byContent(in);
            } catch (Exception e) {
                Log.w(LOG_TAG, "helper-mod check failed: " + e);
            }
            final Integer result = found;
            android.app.Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (binding == null) return;
                if (result != null) showHelperModDialog(result, proceed);
                else proceed.run();
            });
        }, "helper-mod-check").start();
    }

    private void showHelperModDialog(int sectionName, Runnable proceed) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.install_mod_helper_title)
                .setMessage(getString(R.string.install_mod_helper_message, getString(sectionName)))
                .setPositiveButton(R.string.install_mod_helper_open, (d, w) ->
                        Navigation.findNavController(requireView()).navigate(R.id.action_open_optimization))
                .setNeutralButton(R.string.install_mod_helper_anyway, (d, w) -> proceed.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // -------------------- export --------------------

    private void onExportTarget(@Nullable Uri outUri) {
        int kind = pending;
        pending = NONE;
        if (outUri == null || binding == null) return;
        if (kind == DRIVER) {
            Intent i = task(InstallerService.Task.EXPORT_CUSTOM_DRIVER, null);
            i.putExtra(InstallerService.EXTRA_OUTPUT_URI, outUri);
            start(i);
            return;
        }
        GameInstance gi = selectedInstanceOrToast();
        if (gi == null) return;
        Intent i;
        if (kind == SAVES) {
            i = task(InstallerService.Task.EXPORT_SAVES_FROM_INSTANCE, gi);
        } else if (kind == CONTROLS) {
            i = task(InstallerService.Task.EXPORT_CONTROLS_FROM_INSTANCE, gi);
        } else if (kind == GAME_FILES) {
            i = task(InstallerService.Task.EXPORT_GAME_SETTINGS, gi);
            i.putExtra(InstallerService.EXTRA_GAME_FILES_KIND, gameFilesKind);
        } else {
            return;
        }
        i.putExtra(InstallerService.EXTRA_OUTPUT_URI, outUri);
        start(i);
    }

    private boolean hasControlsToExport(GameInstance gi) {
        File controlsJson = new File(new File(gi.getGamePath(), "controls"), "controls.json");
        return controlsJson.isFile() && controlsJson.length() > 0;
    }

    private void updateDriverExport() {
        if (binding == null) return;
        String home = AppStorage.requireSingleton().getHomePath();
        boolean has = home != null && !home.isEmpty() && new File(home, C.deps.CUSTOM_DRIVER).isFile();
        binding.transferDriverExportBtn.setEnabled(has);
    }

    // -------------------- game settings hints --------------------

    private void updateGameFilesHint() {
        if (binding == null) return;
        binding.transferGameFilesHintTv.setText(gameFilesImportHint(gameFilesKind));
    }

    @StringRes
    private static int gameFilesImportHint(int kind) {
        return kind == InstallerService.GAME_FILES_SANDBOX ? R.string.game_settings_import_hint_sandbox
                : kind == InstallerService.GAME_FILES_BUILDS ? R.string.game_settings_import_hint_builds
                : R.string.game_settings_import_hint;
    }

    // -------------------- service --------------------

    private Intent task(InstallerService.Task task, @Nullable GameInstance gi) {
        Intent i = new Intent(requireContext(), InstallerService.class);
        i.putExtra(InstallerService.EXTRA_COMMAND, task.ordinal());
        if (gi != null) i.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME, gi.getName());
        return i;
    }

    private void start(Intent installerIntent) {
        requireContext().startForegroundService(installerIntent);
        bindInstallerService();
    }

    private void handleTaskState(InstallerService.TaskState state) {
        if (state == null) return;
        if (state.isFinished) {
            taskProgressDialog.dismiss();
            unbindInstallerService();
            if (installerService != null) installerService.stopUnlessRestarted();
            Toast.makeText(requireContext(), state.title, Toast.LENGTH_SHORT).show();
            updateDriverExport();
        } else if (state.isFinishedWithError) {
            showTaskDialog(state.title, state.message, -1, 0, true);
            unbindInstallerService();
            if (installerService != null) installerService.stopUnlessRestarted();
        } else {
            showTaskDialog(state.title, state.message, state.progress, state.progressMax, false);
        }
    }

    private void bindInstallerService() {
        Intent intent = new Intent(requireContext(), InstallerService.class);
        requireContext().bindService(intent, installerServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindInstallerService() {
        if (isInstallerServiceBound) {
            requireContext().unbindService(installerServiceConnection);
            isInstallerServiceBound = false;
        }
    }

    private void showTaskDialog(String title, String message, int progress, int progressMax, boolean error) {
        TaskProgressDialogBinding b = taskProgressDialogBinding;
        b.progressDialogTitleTv.setVisibility(title != null ? View.VISIBLE : View.GONE);
        if (title != null) b.progressDialogTitleTv.setText(title);
        b.progressDialogMessageTv.setVisibility(message != null ? View.VISIBLE : View.GONE);
        if (message != null) b.progressDialogMessageTv.setText(message);
        if (error) {
            b.progressDialogProgressLpi.setVisibility(View.GONE);
            b.progressDialogOkMb.setVisibility(View.VISIBLE);
        } else {
            b.progressDialogProgressLpi.setVisibility(View.VISIBLE);
            if (progress < 0) {
                b.progressDialogProgressLpi.setIndeterminate(true);
            } else {
                b.progressDialogProgressLpi.setIndeterminate(false);
                b.progressDialogProgressLpi.setMax(progressMax);
                b.progressDialogProgressLpi.setProgress(progress);
            }
            b.progressDialogOkMb.setVisibility(View.GONE);
        }
        taskProgressDialog.show();
    }

    // -------------------- helpers --------------------

    private void showFormatHelp(@StringRes int title, @StringRes int message) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setTypeface(Typeface.MONOSPACE);
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
    }

    @Nullable
    private String fileName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i != -1) return c.getString(i);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}
