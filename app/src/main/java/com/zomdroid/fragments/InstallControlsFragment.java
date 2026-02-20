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
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentInstallControlsBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class InstallControlsFragment extends Fragment {

    private static final String LOG_TAG = InstallControlsFragment.class.getName();

    private FragmentInstallControlsBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;

    private final String ZIP_MIME = "application/zip";

    private Uri controlsZipUri = null;
    private List<GameInstance> instances;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            InstallerService installerService = binder.getService();
            isInstallerServiceBound = true;

            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(InstallControlsFragment.this, InstallControlsFragment.this::handleTaskState);
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
            Toast.makeText(requireContext(), state.title, Toast.LENGTH_SHORT).show();
        } else if (state.isFinishedWithError) {
            showTaskFinishedWithErrorDialog(state.title, state.message);
            unbindInstallerService();
            requireContext().stopService(new Intent(requireContext(), InstallerService.class));
        } else {
            showTaskProgressDialog(state.title, state.message, state.progress, state.progressMax);
        }
    }

    private final ActivityResultLauncher<String> actionOpenControlsLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;

                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    controlsZipUri = uri;
                    binding.installControlsZipPathEt.setText(extractFileName(uri));
                    binding.installControlsInstallBtn.setEnabled(true);
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> actionCreateControlsZipLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(ZIP_MIME), outUri -> {
                if (outUri == null) return;

                GameInstance selectedInstance = getSelectedInstanceOrNull();
                if (selectedInstance == null) return;

                Intent installerIntent = new Intent(requireContext(), InstallerService.class);
                installerIntent.putExtra(
                        InstallerService.EXTRA_COMMAND,
                        InstallerService.Task.EXPORT_CONTROLS_FROM_INSTANCE.ordinal()
                );
                installerIntent.putExtra(
                        InstallerService.EXTRA_GAME_INSTANCE_NAME,
                        selectedInstance.getName()
                );
                installerIntent.putExtra(
                        InstallerService.EXTRA_OUTPUT_URI,
                        outUri
                );

                requireContext().startForegroundService(installerIntent);
                bindInstallerService();
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInstallControlsBinding.inflate(inflater, container, false);
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
                taskProgressDialog.dismiss()
        );

        instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances == null || instances.isEmpty()) {
            Toast.makeText(requireContext(), "No game instances found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Spinner population
        List<String> names = new ArrayList<>();
        if (instances.size() > 1) {
            names.add(getString(R.string.select_instance));
        }
        for (GameInstance gi : instances) names.add(gi.getName());

        binding.installControlsInstanceSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                names
        ));

        if (instances.size() == 1) {
            binding.installControlsInstanceSpinner.setSelection(0);
        }

        binding.installControlsInstallBtn.setEnabled(false);

        binding.installControlsBrowseIb.setOnClickListener(v ->
                actionOpenControlsLauncher.launch(ZIP_MIME)
        );

        binding.installControlsInstallBtn.setOnClickListener(v -> {
            if (controlsZipUri == null) {
                Toast.makeText(requireContext(), R.string.game_instance_no_file_selected, Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance selectedInstance = getSelectedInstanceOrNull();
            if (selectedInstance == null) return;

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_CONTROLS_TO_INSTANCE.ordinal());
            installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(InstallerService.EXTRA_CONTROLS_URI,
                    controlsZipUri);

            clearSelectedZip();

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });

        binding.installControlsZipHelpIb.setOnClickListener(v -> {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.install_controls_zip_help_title)
                    .setMessage(getString(R.string.install_controls_zip_help_message))
                    .setPositiveButton(android.R.string.ok, null);

            AlertDialog dialog = builder.show();
            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) messageView.setTypeface(Typeface.MONOSPACE);
        });

        binding.installControlsExportBtn.setOnClickListener(v -> {
            GameInstance gi = getSelectedInstanceOrNull();
            if (gi == null) return;

            if (!hasControlsToExport(gi)) {
                Toast.makeText(requireContext(),
                        getString(R.string.dialog_title_controls_export_skipped_default),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            String suggested = "zomdroid_controls_" + ts + ".zip";
            actionCreateControlsZipLauncher.launch(suggested);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbindInstallerService();
        binding = null;
    }

    private GameInstance getSelectedInstanceOrNull() {
        int position = binding.installControlsInstanceSpinner.getSelectedItemPosition();
        int instanceIndex = instances.size() > 1 ? position - 1 : position;

        if (instanceIndex < 0 || instanceIndex >= instances.size()) {
            Toast.makeText(requireContext(),
                    getString(R.string.select_instance),
                    Toast.LENGTH_SHORT).show();
            return null;
        }
        return instances.get(instanceIndex);
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
        if (progress < 0)
            taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(true);
        else {
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

    private void clearSelectedZip() {
        controlsZipUri = null;
        if (binding != null) {
            binding.installControlsZipPathEt.setText(getString(R.string.game_instance_no_file_selected));
            binding.installControlsInstallBtn.setEnabled(false);
        }
    }

    private boolean hasControlsToExport(GameInstance gi) {
        if (gi == null) return false;
        File controlsDir = new File(gi.getGamePath(), "controls");
        File controlsJson = new File(controlsDir, "controls.json");
        return controlsDir.isDirectory() && controlsJson.isFile() && controlsJson.length() > 0;
    }

    private String extractFileName(Uri uri) {
        String fileName = null;
        Cursor cursor = requireContext().getContentResolver().query(
                uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null
        );
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            if (nameIndex != -1) fileName = cursor.getString(nameIndex);
            cursor.close();
        }
        return fileName;
    }
}