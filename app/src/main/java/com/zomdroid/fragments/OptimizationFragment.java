package com.zomdroid.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
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
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.SharedPreferences;
import com.zomdroid.InstallerService;
import com.zomdroid.LauncherPreferences;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentOptimizationBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OptimizationFragment extends Fragment {

    private static final String LOG_TAG = OptimizationFragment.class.getName();

    private FragmentOptimizationBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;

    private final String ZIP_MIME = "application/zip";
    private Uri betterFpsZipUri = null;
    private Uri etoZipUri = null;
    private Uri zombiebuddyZipUri = null;
    private Uri zbbetterfpsZipUri = null;
    private List<GameInstance> instances;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            InstallerService installerService = binder.getService();
            isInstallerServiceBound = true;

            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(OptimizationFragment.this, OptimizationFragment.this::handleTaskState);
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
            requireContext().stopService(new Intent(requireContext(), InstallerService.class));
            Toast.makeText(requireContext(),
                    getString(R.string.mod_fix_installed),
                    Toast.LENGTH_SHORT).show();
        } else if (state.isFinishedWithError) {
            showTaskFinishedWithErrorDialog(state.title, state.message);
            unbindInstallerService();
            requireContext().stopService(new Intent(requireContext(), InstallerService.class));
        } else {
            showTaskProgressDialog(state.title, state.message, state.progress, state.progressMax);
        }
    }

    private final ActivityResultLauncher<String> betterFpsLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    betterFpsZipUri = uri;
                    binding.optimizationBetterfpsPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> etoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    etoZipUri = uri;
                    binding.optimizationEtoPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> zombiebuddyLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    zombiebuddyZipUri = uri;
                    binding.optimizationZombiebuddyPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> zbbetterfpsLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    zbbetterfpsZipUri = uri;
                    binding.optimizationZbbetterfpsPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentOptimizationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Setup progress dialog
        taskProgressDialogBinding = TaskProgressDialogBinding.inflate(getLayoutInflater());
        taskProgressDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(taskProgressDialogBinding.getRoot())
                .setCancelable(false)
                .create();
        taskProgressDialogBinding.progressDialogOkMb.setOnClickListener(v ->
                taskProgressDialog.dismiss());

        // Show what is currently seeded by default at instance creation (single source of
        // truth — same constant the seeding and the Settings reset button use). Info-only:
        // nothing to copy, the value is already applied; changing it lives in Settings.
        binding.optimizationJvmRecommendedTv.setText(LauncherPreferences.DEFAULT_JVM_ARGS);

        // Open Settings link
        binding.optimizationOpenSettingsTv.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.settings_fragment));

        // BetterFPS help
        binding.optimizationBetterfpsHelpIb.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.optimization_betterfps_help_title)
                        .setMessage(R.string.optimization_betterfps_help_message)
                        .setPositiveButton(R.string.dialog_button_ok, null)
                        .show());

        // Instance spinner
        instances = GameInstanceManager.requireSingleton().getInstances();

        List<String> names = new ArrayList<>();
        if (instances == null || instances.isEmpty()) {
            instances = new ArrayList<>();
            names.add(getString(R.string.select_instance));
            binding.optimizationBetterfpsInstallBtn.setEnabled(false);
        } else {
            if (instances.size() > 1) {
                names.add(getString(R.string.select_instance));
            }
            for (GameInstance gi : instances) {
                names.add(gi.getName());
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.spinner_item,
                names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.optimizationBetterfpsInstanceSpinner.setAdapter(adapter);
        binding.optimizationBetterfpsInstanceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
        if (instances.size() == 1) {
            binding.optimizationBetterfpsInstanceSpinner.setSelection(0);
        }

        // Browse button
        // BetterFPS mode spinner — PotatoePC / 1080p / 4K (only meaningful for B41)
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.spinner_item,
                new String[]{
                        getString(R.string.optimization_betterfps_mode_potato),
                        getString(R.string.optimization_betterfps_mode_1080p),
                        getString(R.string.optimization_betterfps_mode_4k)
                });
        modeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.optimizationBetterfpsModeSpinner.setAdapter(modeAdapter);
        binding.optimizationBetterfpsModeSpinner.setSelection(0); // PotatoePC by default

        binding.optimizationBetterfpsBrowseIb.setOnClickListener(v ->
                betterFpsLauncher.launch(ZIP_MIME));

        // Install button
        binding.optimizationBetterfpsInstallBtn.setOnClickListener(v -> {
            if (betterFpsZipUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            int position = binding.optimizationBetterfpsInstanceSpinner.getSelectedItemPosition();
            int instanceIndex = instances.size() > 1 ? position - 1 : position;

            if (instanceIndex < 0 || instanceIndex >= instances.size()) {
                Toast.makeText(requireContext(),
                        getString(R.string.select_instance),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance selectedInstance = instances.get(instanceIndex);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            String[] betterfpsModes = {"PotatoePC", "1080p", "4k"};
            int modePos = binding.optimizationBetterfpsModeSpinner.getSelectedItemPosition();
            String selectedMode = betterfpsModes[Math.max(0, Math.min(modePos, betterfpsModes.length - 1))];

            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_BETTERFPS.ordinal());
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(
                    InstallerService.EXTRA_BETTERFPS_MODE,
                    selectedMode);
            installerIntent.putExtra(
                    InstallerService.EXTRA_ARCHIVE_URI,
                    betterFpsZipUri);

            betterFpsZipUri = null;
            binding.optimizationBetterfpsPathEt.setText(getString(R.string.game_instance_no_file_selected));

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });

        // ===== ZombieBuddy section =====
        SharedPreferences prefs = LauncherPreferences.requireSingleton().getSharedPrefs();

        binding.optimizationZombiebuddyHelpIb.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.optimization_zombiebuddy_help_title)
                        .setMessage(R.string.optimization_zombiebuddy_help_message)
                        .setPositiveButton(R.string.dialog_button_ok, null)
                        .show());

        binding.optimizationZombiebuddySwitch.setChecked(false);
        binding.optimizationZombiebuddySwitch.setOnCheckedChangeListener((v, checked) -> {
            int pos = binding.optimizationZombiebuddyInstanceSpinner.getSelectedItemPosition();
            int idx = (instances != null && instances.size() > 1) ? pos - 1 : pos;
            if (instances != null && idx >= 0 && idx < instances.size()) {
                prefs.edit().putBoolean(
                        "zombiebuddy_enabled_" + instances.get(idx).getName(), checked).apply();
            }
        });

        // ZombieBuddy instance spinner
        List<String> zbNames = new ArrayList<>();
        if (instances == null || instances.isEmpty()) {
            zbNames.add(getString(R.string.select_instance));
            binding.optimizationZombiebuddyInstallBtn.setEnabled(false);
        } else {
            if (instances.size() > 1) zbNames.add(getString(R.string.select_instance));
            for (GameInstance gi : instances) zbNames.add(gi.getName());
        }
        ArrayAdapter<String> zbAdapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, zbNames);
        zbAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.optimizationZombiebuddyInstanceSpinner.setAdapter(zbAdapter);
        if (instances != null && instances.size() == 1)
            binding.optimizationZombiebuddyInstanceSpinner.setSelection(0);

        binding.optimizationZombiebuddyInstanceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               android.view.View view, int position, long id) {
                        int idx = (instances != null && instances.size() > 1) ? position - 1 : position;
                        if (instances != null && idx >= 0 && idx < instances.size()) {
                            String key = "zombiebuddy_enabled_" + instances.get(idx).getName();
                            binding.optimizationZombiebuddySwitch.setChecked(prefs.getBoolean(key, false));
                        }
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        binding.optimizationZombiebuddyBrowseIb.setOnClickListener(v ->
                zombiebuddyLauncher.launch(ZIP_MIME));

        binding.optimizationZombiebuddyInstallBtn.setOnClickListener(v -> {
            if (zombiebuddyZipUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            int position = binding.optimizationZombiebuddyInstanceSpinner.getSelectedItemPosition();
            int instanceIndex = (instances != null && instances.size() > 1) ? position - 1 : position;
            if (instances == null || instanceIndex < 0 || instanceIndex >= instances.size()) {
                Toast.makeText(requireContext(),
                        getString(R.string.select_instance),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            GameInstance selectedInstance = instances.get(instanceIndex);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_ZOMBIEBUDDY.ordinal());
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, zombiebuddyZipUri);
            zombiebuddyZipUri = null;
            binding.optimizationZombiebuddyPathEt.setText(getString(R.string.game_instance_no_file_selected));
            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });

        // ===== ZBBetterFPS section =====
        binding.optimizationZbbetterfpsHelpIb.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.optimization_zbbetterfps_help_title)
                        .setMessage(R.string.optimization_zbbetterfps_help_message)
                        .setPositiveButton(R.string.dialog_button_ok, null)
                        .show());

        // ZBBetterFPS instance spinner
        List<String> zbbNames = new ArrayList<>();
        if (instances == null || instances.isEmpty()) {
            zbbNames.add(getString(R.string.select_instance));
            binding.optimizationZbbetterfpsInstallBtn.setEnabled(false);
        } else {
            if (instances.size() > 1) zbbNames.add(getString(R.string.select_instance));
            for (GameInstance gi : instances) zbbNames.add(gi.getName());
        }
        ArrayAdapter<String> zbbAdapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, zbbNames);
        zbbAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.optimizationZbbetterfpsInstanceSpinner.setAdapter(zbbAdapter);
        if (instances != null && instances.size() == 1)
            binding.optimizationZbbetterfpsInstanceSpinner.setSelection(0);

        binding.optimizationZbbetterfpsInstanceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               android.view.View view, int position, long id) {
                        int idx = (instances != null && instances.size() > 1) ? position - 1 : position;
                        // Warn if GL4ES renderer is selected — ZBBetterFPS only works with ZINK
                        boolean isGl4es = com.zomdroid.LauncherPreferences.requireSingleton().getRenderer()
                                == com.zomdroid.LauncherPreferences.Renderer.GL4ES;
                        binding.optimizationZbbetterfpsGl4esWarningTv.setVisibility(
                                isGl4es ? android.view.View.VISIBLE : android.view.View.GONE);
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        binding.optimizationZbbetterfpsBrowseIb.setOnClickListener(v ->
                zbbetterfpsLauncher.launch(ZIP_MIME));

        binding.optimizationZbbetterfpsInstallBtn.setOnClickListener(v -> {
            if (zbbetterfpsZipUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            int position = binding.optimizationZbbetterfpsInstanceSpinner.getSelectedItemPosition();
            int instanceIndex = (instances != null && instances.size() > 1) ? position - 1 : position;
            if (instances == null || instanceIndex < 0 || instanceIndex >= instances.size()) {
                Toast.makeText(requireContext(),
                        getString(R.string.select_instance),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            GameInstance selectedInstance = instances.get(instanceIndex);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_ZBBETTERFPS.ordinal());
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(
                    InstallerService.EXTRA_BUILD_VERSION,
                    selectedInstance.getBuildVersion());
            installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, zbbetterfpsZipUri);
            zbbetterfpsZipUri = null;
            binding.optimizationZbbetterfpsPathEt.setText(getString(R.string.game_instance_no_file_selected));
            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });

        // ===== Collapsible sections =====
        setupCollapsible(
                binding.optimizationBetterfpsHeader,
                binding.optimizationBetterfpsContent,
                binding.optimizationBetterfpsExpandIv);
        setupCollapsible(
                binding.optimizationEtoHeader,
                binding.optimizationEtoContent,
                binding.optimizationEtoExpandIv);
        setupCollapsible(
                binding.optimizationZombiebuddyHeader,
                binding.optimizationZombiebuddyContent,
                binding.optimizationZombiebuddyExpandIv);
        setupCollapsible(
                binding.optimizationZbbetterfpsHeader,
                binding.optimizationZbbetterfpsContent,
                binding.optimizationZbbetterfpsExpandIv);

        // ===== ETO section =====
        binding.optimizationEtoHelpIb.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.optimization_eto_help_title)
                        .setMessage(R.string.optimization_eto_help_message)
                        .setPositiveButton(R.string.dialog_button_ok, null)
                        .show());

        // ETO spinner — reuse instances already loaded
        List<String> etoNames = new ArrayList<>();
        if (instances == null || instances.isEmpty()) {
            etoNames.add(getString(R.string.select_instance));
            binding.optimizationEtoInstallBtn.setEnabled(false);
        } else {
            if (instances.size() > 1) etoNames.add(getString(R.string.select_instance));
            for (GameInstance gi : instances) etoNames.add(gi.getName());
        }
        ArrayAdapter<String> etoAdapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, etoNames);
        etoAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.optimizationEtoInstanceSpinner.setAdapter(etoAdapter);
        if (instances != null && instances.size() == 1) binding.optimizationEtoInstanceSpinner.setSelection(0);

        binding.optimizationEtoBrowseIb.setOnClickListener(v ->
                etoLauncher.launch(ZIP_MIME));

        binding.optimizationEtoInstallBtn.setOnClickListener(v -> {
            if (etoZipUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            int position = binding.optimizationEtoInstanceSpinner.getSelectedItemPosition();
            int instanceIndex = instances.size() > 1 ? position - 1 : position;

            if (instanceIndex < 0 || instanceIndex >= instances.size()) {
                Toast.makeText(requireContext(),
                        getString(R.string.select_instance),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance selectedInstance = instances.get(instanceIndex);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_ETO.ordinal());
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(
                    InstallerService.EXTRA_BUILD_VERSION,
                    selectedInstance.getBuildVersion());
            installerIntent.putExtra(
                    InstallerService.EXTRA_ARCHIVE_URI,
                    etoZipUri);

            etoZipUri = null;
            binding.optimizationEtoPathEt.setText(getString(R.string.game_instance_no_file_selected));

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });
    }

    private void setupCollapsible(android.view.View header, android.view.View content,
                                  android.widget.ImageView expandIcon) {
        header.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() == android.view.View.VISIBLE;
            content.setVisibility(expanded ? android.view.View.GONE : android.view.View.VISIBLE);
            expandIcon.setImageResource(expanded
                    ? R.drawable.mt_icon_expand_more
                    : R.drawable.mt_icon_expand_less);
        });
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
        if (title != null) {
            taskProgressDialogBinding.progressDialogTitleTv.setText(title);
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.GONE);
        }
        if (message != null) {
            taskProgressDialogBinding.progressDialogMessageTv.setText(message);
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.GONE);
        }
        taskProgressDialogBinding.progressDialogProgressLpi.setVisibility(View.VISIBLE);
        if (progress < 0) {
            taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(true);
        } else {
            taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(false);
            taskProgressDialogBinding.progressDialogProgressLpi.setMax(progressMax);
            taskProgressDialogBinding.progressDialogProgressLpi.setProgress(progress);
        }
        taskProgressDialogBinding.progressDialogOkMb.setVisibility(View.GONE);
        taskProgressDialog.show();
    }

    private void showTaskFinishedWithErrorDialog(String title, String message) {
        if (title != null) {
            taskProgressDialogBinding.progressDialogTitleTv.setText(title);
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.GONE);
        }
        if (message != null) {
            taskProgressDialogBinding.progressDialogMessageTv.setText(message);
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.GONE);
        }
        taskProgressDialogBinding.progressDialogProgressLpi.setVisibility(View.GONE);
        taskProgressDialogBinding.progressDialogOkMb.setVisibility(View.VISIBLE);
        taskProgressDialog.show();
    }

    private String extractFileName(Uri uri) {
        String fileName = null;
        Cursor cursor = requireContext().getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return fileName;
    }
}