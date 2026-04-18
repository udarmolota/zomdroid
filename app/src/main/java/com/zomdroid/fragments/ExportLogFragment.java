package com.zomdroid.fragments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
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
import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentExportLogBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExportLogFragment extends Fragment {

    private static final String LOG_TAG = ExportLogFragment.class.getName();
    private static final String TXT_MIME = "text/plain";

    private FragmentExportLogBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;

    private List<GameInstance> instances;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            InstallerService installerService = binder.getService();
            isInstallerServiceBound = true;

            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(ExportLogFragment.this, ExportLogFragment.this::handleTaskState);
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

    private final ActivityResultLauncher<String> actionCreateLogLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(TXT_MIME), outUri -> {
                if (outUri == null) return;

                GameInstance selectedInstance = getSelectedInstanceOrNull();
                if (selectedInstance == null) return;

                Intent installerIntent = new Intent(requireContext(), InstallerService.class);
                installerIntent.putExtra(
                        InstallerService.EXTRA_COMMAND,
                        InstallerService.Task.EXPORT_LOG.ordinal()
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
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentExportLogBinding.inflate(inflater, container, false);
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

        instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances == null || instances.isEmpty()) {
            Toast.makeText(requireContext(), "No game instances found", Toast.LENGTH_SHORT).show();
            return;
        }

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
                names
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.exportLogInstanceSpinner.setAdapter(adapter);
        binding.exportLogInstanceSpinner.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent,
                                       View view, int position, long id) {
                int instanceIndex = instances.size() > 1 ? position - 1 : position;
                if (instanceIndex < 0 || instanceIndex >= instances.size()) {
                    binding.exportLogBannerIv.setVisibility(View.INVISIBLE);
                    binding.exportLogBannerOverlay.setVisibility(View.INVISIBLE);
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
                binding.exportLogBannerIv.setImageResource(bannerRes);
                binding.exportLogBannerIv.setVisibility(View.VISIBLE);
                binding.exportLogBannerOverlay.setVisibility(View.VISIBLE);
            }
    
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                binding.exportLogBannerIv.setVisibility(View.INVISIBLE);
                binding.exportLogBannerOverlay.setVisibility(View.INVISIBLE);
            }
        });

        if (instances.size() == 1) {
            binding.exportLogInstanceSpinner.setSelection(0);
        }

        binding.exportLogExportBtn.setOnClickListener(v -> {
            GameInstance selectedInstance = getSelectedInstanceOrNull();
            if (selectedInstance == null) return;

            // Проверяем наличие console.txt до открытия диалога сохранения
            File logFile = new File(selectedInstance.getHomePath() + "/Zomboid/console.txt");
            if (!logFile.exists()) {
                Toast.makeText(requireContext(),
                        getString(R.string.export_log_not_found),
                        Toast.LENGTH_LONG).show();
                return;
            }

            String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            String defaultName = "console_" + ts + ".txt";
            actionCreateLogLauncher.launch(defaultName);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbindInstallerService();
        binding = null;
    }

    private GameInstance getSelectedInstanceOrNull() {
        int position = binding.exportLogInstanceSpinner.getSelectedItemPosition();
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
}
