package com.zomdroid.fragments;

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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.AppStorage;
import com.zomdroid.C;
import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentInstallDriverBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class InstallDriverFragment extends Fragment {

    private static final String LOG_TAG = InstallDriverFragment.class.getName();
    private static final String SO_MIME = "application/octet-stream";

    private FragmentInstallDriverBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;

    private Uri driverSoUri = null;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            InstallerService installerService = binder.getService();
            isInstallerServiceBound = true;

            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(InstallDriverFragment.this, InstallDriverFragment.this::handleTaskState);
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
            updateExportButtonState();
        } else if (state.isFinishedWithError) {
            showTaskFinishedWithErrorDialog(state.title, state.message);
            unbindInstallerService();
            requireContext().stopService(new Intent(requireContext(), InstallerService.class));
        } else {
            showTaskProgressDialog(state.title, state.message, state.progress, state.progressMax);
        }
    }

    // Import: .so picker — используем OpenDocument чтобы принимать любой .so файл
    private final ActivityResultLauncher<String[]> actionOpenDriverLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                String fileName = extractFileName(uri);
                if (fileName != null && fileName.endsWith(".so")) {
                    driverSoUri = uri;
                    binding.installDriverSoPathEt.setText(fileName);
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.install_driver_wrong_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    // Export: создаём файл для сохранения
    private final ActivityResultLauncher<String> actionCreateDriverSoLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(SO_MIME), outUri -> {
                if (outUri == null) return;

                Intent installerIntent = new Intent(requireContext(), InstallerService.class);
                installerIntent.putExtra(
                        InstallerService.EXTRA_COMMAND,
                        InstallerService.Task.EXPORT_CUSTOM_DRIVER.ordinal()
                );
                installerIntent.putExtra(
                        InstallerService.EXTRA_OUTPUT_URI,
                        outUri
                );

                requireContext().startForegroundService(installerIntent);
                bindInstallerService();
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentInstallDriverBinding.inflate(inflater, container, false);
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
                taskProgressDialog.dismiss()
        );

        binding.installDriverBrowseIb.setOnClickListener(v ->
                actionOpenDriverLauncher.launch(new String[]{"application/octet-stream", "*/*"})
        );

        binding.installDriverImportBtn.setOnClickListener(v -> {
            if (driverSoUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.IMPORT_CUSTOM_DRIVER.ordinal()
            );
            installerIntent.putExtra(
                    InstallerService.EXTRA_DRIVER_URI,
                    driverSoUri
            );

            driverSoUri = null;
            binding.installDriverSoPathEt.setText(getString(R.string.game_instance_no_file_selected));

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
        });

        binding.installDriverExportBtn.setOnClickListener(v -> {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            String defaultName = "custom_driver_" + ts + ".so";
            actionCreateDriverSoLauncher.launch(defaultName);
        });

        updateExportButtonState();
    }

    /** Export доступен только если файл уже импортирован */
    private void updateExportButtonState() {
        if (binding == null) return;
        File driverFile = getCustomDriverFile();
        binding.installDriverExportBtn.setEnabled(driverFile != null && driverFile.exists());
    }

    private File getCustomDriverFile() {
        String homePath = AppStorage.requireSingleton().getHomePath();
        if (homePath == null || homePath.isEmpty()) return null;
        return new File(homePath, C.deps.CUSTOM_DRIVER);
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

    private String extractFileName(Uri uri) {
        String fileName = null;
        try (Cursor cursor = requireContext().getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (idx != -1) fileName = cursor.getString(idx);
            }
        }
        return fileName;
    }
}
