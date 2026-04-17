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
import com.zomdroid.InstallerService;
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
                    getString(R.string.optimization_betterfps_installed),
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

        // JVM Copy buttons
        binding.optimizationJvm4gBalancedCopyBtn.setOnClickListener(v ->
                copyToClipboard(binding.optimizationJvm4gBalancedTv.getText().toString()));
        binding.optimizationJvm4gAggressiveCopyBtn.setOnClickListener(v ->
                copyToClipboard(binding.optimizationJvm4gAggressiveTv.getText().toString()));
        binding.optimizationJvm6gBalancedCopyBtn.setOnClickListener(v ->
                copyToClipboard(binding.optimizationJvm6gBalancedTv.getText().toString()));
        binding.optimizationJvm6gAggressiveCopyBtn.setOnClickListener(v ->
                copyToClipboard(binding.optimizationJvm6gAggressiveTv.getText().toString()));

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

        if (instances == null || instances.isEmpty()) {
            binding.optimizationBetterfpsInstanceSpinner.setEnabled(false);
            binding.optimizationBetterfpsInstallBtn.setEnabled(false);
        } else {
            List<String> names = new ArrayList<>();
            if (instances.size() > 1) {
                names.add(getString(R.string.select_instance));
            }
            for (GameInstance gi : instances) {
                names.add(gi.getName());
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
                            int instanceIndex = instances.size() > 1 ? position - 1 : position;
                            if (instanceIndex < 0 || instanceIndex >= instances.size()) {
                                binding.optimizationBannerIv.setImageResource(R.drawable.banner_default);
                                return;
                            }
                            GameInstance selected = instances.get(instanceIndex);
                            int bannerRes;
                            switch (selected.getPresetName()) {
                                case "Build 42.12+":
                                    bannerRes = R.drawable.banner_build42_12;
                                    break;
                                case "Build 42":
                                    bannerRes = R.drawable.banner_build42;
                                    break;
                                default:
                                    bannerRes = R.drawable.banner_build41;
                                    break;
                            }
                            binding.optimizationBannerIv.setImageResource(bannerRes);
                        }

                        @Override
                        public void onNothingSelected(android.widget.AdapterView<?> parent) {
                            binding.optimizationBannerIv.setImageResource(R.drawable.banner_default);
                        }
                    });

            if (instances.size() == 1) {
                binding.optimizationBetterfpsInstanceSpinner.setSelection(0);
            }
        }

        // Browse button
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
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_BETTERFPS.ordinal());
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(
                    InstallerService.EXTRA_ARCHIVE_URI,
                    betterFpsZipUri);

            betterFpsZipUri = null;
            binding.optimizationBetterfpsPathEt.setText(getString(R.string.game_instance_no_file_selected));

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("jvm_args", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(),
                R.string.optimization_jvm_copied,
                Toast.LENGTH_SHORT).show();
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