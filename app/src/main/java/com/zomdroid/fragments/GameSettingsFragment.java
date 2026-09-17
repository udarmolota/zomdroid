package com.zomdroid.fragments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentGameSettingsBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GameSettingsFragment extends Fragment {

    private static final String LOG_TAG = GameSettingsFragment.class.getName();
    private static final String INI_MIME = "*/*";

    private FragmentGameSettingsBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;
    // Kept so a finished task stops the service it ran in, not whatever is started later.
    private InstallerService installerService;

    private Uri importIniUri = null;
    /** The selected tab: one of InstallerService.GAME_FILES_*, in tab order. */
    private int gameFilesKind = InstallerService.GAME_FILES_OPTIONS;
    private List<GameInstance> instances;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            installerService = binder.getService();
            isInstallerServiceBound = true;
            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(GameSettingsFragment.this, GameSettingsFragment.this::handleTaskState);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.e(LOG_TAG, "Connection to installer service has been lost");
            isInstallerServiceBound = false;
            if (taskProgressDialog != null) taskProgressDialog.dismiss();
        }
    };

    private void handleTaskState(InstallerService.TaskState state) {
        if (state == null) return;
        if (state.isFinished) {
            taskProgressDialog.dismiss();
            unbindInstallerService();
            if (installerService != null) installerService.stopUnlessRestarted();
            Toast.makeText(requireContext(), state.title, Toast.LENGTH_SHORT).show();
        } else if (state.isFinishedWithError) {
            showTaskFinishedWithErrorDialog(state.title, state.message);
            unbindInstallerService();
            if (installerService != null) installerService.stopUnlessRestarted();
        } else {
            showTaskProgressDialog(state.title, state.message, state.progress, state.progressMax);
        }
    }

    // INI file picker for import
    private final ActivityResultLauncher<String[]> importIniLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                String name = extractFileName(uri);
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                // Everything travels as .zip; a bare options.ini or .cfg is taken as well.
                boolean accepted = lower.endsWith(".zip")
                        || (gameFilesKind == InstallerService.GAME_FILES_OPTIONS && lower.endsWith(".ini"))
                        || (gameFilesKind == InstallerService.GAME_FILES_SANDBOX && lower.endsWith(".cfg"));
                if (accepted) {
                    importIniUri = uri;
                    binding.gameSettingsImportPathEt.setText(name);
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_settings_wrong_format),
                            Toast.LENGTH_SHORT).show();
                }
            });

    // File creator for export
    private final ActivityResultLauncher<String> exportIniLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), outUri -> {
                if (outUri == null) return;

                GameInstance selectedInstance = getSelectedInstanceOrNull();
                if (selectedInstance == null) return;

                Intent installerIntent = new Intent(requireContext(), InstallerService.class);
                installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                        InstallerService.Task.EXPORT_GAME_SETTINGS.ordinal());
                installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME,
                        selectedInstance.getName());
                installerIntent.putExtra(InstallerService.EXTRA_OUTPUT_URI, outUri);
                installerIntent.putExtra(InstallerService.EXTRA_GAME_FILES_KIND, gameFilesKind);

                requireContext().startForegroundService(installerIntent);
                bindInstallerService();
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentGameSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        taskProgressDialogBinding = TaskProgressDialogBinding.inflate(getLayoutInflater());
        taskProgressDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(taskProgressDialogBinding.getRoot())
                .setCancelable(false)
                .create();
        taskProgressDialogBinding.progressDialogOkMb.setOnClickListener(v ->
                taskProgressDialog.dismiss());

        // Default banner
        binding.gameSettingsBannerIv.setImageResource(R.drawable.banner_default);

        // Three kinds of the game's own files share this screen; the tab picks the kind.
        TabLayout tabs = binding.gameSettingsTabs;
        tabs.addTab(tabs.newTab().setText(R.string.game_settings_tab_options));
        tabs.addTab(tabs.newTab().setText(R.string.game_settings_tab_sandbox));
        tabs.addTab(tabs.newTab().setText(R.string.game_settings_tab_builds));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                gameFilesKind = tab.getPosition();
                importIniUri = null;
                binding.gameSettingsImportPathEt.setText(getString(R.string.game_instance_no_file_selected));
                updateGameFilesHints();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        updateGameFilesHints();

        instances = GameInstanceManager.requireSingleton().getInstances();

        List<String> names = new ArrayList<>();
        if (instances == null || instances.isEmpty()) {
            instances = new ArrayList<>();
            names.add(getString(R.string.select_instance));
            binding.gameSettingsImportBtn.setEnabled(false);
            binding.gameSettingsExportBtn.setEnabled(false);
        } else {
            if (instances.size() > 1) names.add(getString(R.string.select_instance));
            for (GameInstance gi : instances) names.add(gi.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.gameSettingsInstanceSpinner.setAdapter(adapter);
        binding.gameSettingsInstanceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        int idx = instances.size() > 1 ? position - 1 : position;
                        if (idx < 0 || idx >= instances.size()) {
                            binding.gameSettingsBannerIv.setImageResource(R.drawable.banner_default);
                            binding.gameSettingsBannerOverlay.setVisibility(View.INVISIBLE);
                            return;
                        }
                        GameInstance selected = instances.get(idx);
                        int bannerRes;
                        switch (selected.getPresetName()) {
                            case "Build 42.12+": bannerRes = R.drawable.banner_build42_12; break;
                            case "Build 42":     bannerRes = R.drawable.banner_build42;    break;
                            default:             bannerRes = R.drawable.banner_build41;    break;
                        }
                        binding.gameSettingsBannerIv.setImageResource(bannerRes);
                        binding.gameSettingsBannerOverlay.setVisibility(View.VISIBLE);
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                        binding.gameSettingsBannerIv.setImageResource(R.drawable.banner_default);
                        binding.gameSettingsBannerOverlay.setVisibility(View.INVISIBLE);
                    }
                });

        if (instances.size() == 1) binding.gameSettingsInstanceSpinner.setSelection(0);

        // Browse for INI to import
        binding.gameSettingsImportBrowseIb.setOnClickListener(v ->
                importIniLauncher.launch(new String[]{"*/*"}));

        // Import button
        binding.gameSettingsImportBtn.setOnClickListener(v -> {
            if (importIniUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            GameInstance selectedInstance = getSelectedInstanceOrNull();
            if (selectedInstance == null) return;

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.IMPORT_GAME_SETTINGS.ordinal());
            installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, importIniUri);
            installerIntent.putExtra(InstallerService.EXTRA_GAME_FILES_KIND, gameFilesKind);

            importIniUri = null;
            binding.gameSettingsImportPathEt.setText(getString(R.string.game_instance_no_file_selected));

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });

        // Export button
        binding.gameSettingsExportBtn.setOnClickListener(v -> {
            GameInstance selectedInstance = getSelectedInstanceOrNull();
            if (selectedInstance == null) return;

            String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            String prefix = gameFilesKind == InstallerService.GAME_FILES_SANDBOX ? "sandbox_presets_"
                    : gameFilesKind == InstallerService.GAME_FILES_BUILDS ? "character_builds_" : "options_";
            exportIniLauncher.launch(prefix + ts + ".zip");
        });
    }

    private void updateGameFilesHints() {
        int kind = gameFilesKind;
        binding.gameSettingsImportHintTv.setText(kind == InstallerService.GAME_FILES_SANDBOX
                ? R.string.game_settings_import_hint_sandbox
                : kind == InstallerService.GAME_FILES_BUILDS ? R.string.game_settings_import_hint_builds
                : R.string.game_settings_import_hint);
        binding.gameSettingsExportHintTv.setText(kind == InstallerService.GAME_FILES_SANDBOX
                ? R.string.game_settings_export_hint_sandbox
                : kind == InstallerService.GAME_FILES_BUILDS ? R.string.game_settings_export_hint_builds
                : R.string.game_settings_export_hint);
    }

    private GameInstance getSelectedInstanceOrNull() {
        int position = binding.gameSettingsInstanceSpinner.getSelectedItemPosition();
        int idx = instances.size() > 1 ? position - 1 : position;
        if (idx < 0 || idx >= instances.size()) {
            Toast.makeText(requireContext(), getString(R.string.select_instance), Toast.LENGTH_SHORT).show();
            return null;
        }
        return instances.get(idx);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbindInstallerService();
        binding = null;
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

    private void showTaskProgressDialog(String title, String message, int progress, int progressMax) {
        if (title != null) { taskProgressDialogBinding.progressDialogTitleTv.setText(title); taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.VISIBLE); }
        else taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.GONE);
        if (message != null) { taskProgressDialogBinding.progressDialogMessageTv.setText(message); taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.VISIBLE); }
        else taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.GONE);
        taskProgressDialogBinding.progressDialogProgressLpi.setVisibility(View.VISIBLE);
        if (progress < 0) taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(true);
        else { taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(false); taskProgressDialogBinding.progressDialogProgressLpi.setMax(progressMax); taskProgressDialogBinding.progressDialogProgressLpi.setProgress(progress); }
        taskProgressDialogBinding.progressDialogOkMb.setVisibility(View.GONE);
        taskProgressDialog.show();
    }

    private void showTaskFinishedWithErrorDialog(String title, String message) {
        if (title != null) { taskProgressDialogBinding.progressDialogTitleTv.setText(title); taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.VISIBLE); }
        else taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.GONE);
        if (message != null) { taskProgressDialogBinding.progressDialogMessageTv.setText(message); taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.VISIBLE); }
        else taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.GONE);
        taskProgressDialogBinding.progressDialogProgressLpi.setVisibility(View.GONE);
        taskProgressDialogBinding.progressDialogOkMb.setVisibility(View.VISIBLE);
        taskProgressDialog.show();
    }

    private String extractFileName(Uri uri) {
        String fileName = null;
        Cursor cursor = requireContext().getContentResolver().query(
                uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            if (idx != -1) fileName = cursor.getString(idx);
            cursor.close();
        }
        return fileName;
    }
}