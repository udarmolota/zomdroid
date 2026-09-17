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
import com.zomdroid.databinding.FragmentInstallNativeLibsBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class InstallNativeLibsFragment extends Fragment {

    private static final String LOG_TAG = InstallNativeLibsFragment.class.getName();
    /** Set by Settings: import macOS dylibs into this instance's game/macos instead of Build 41
     *  libraries into android/arm64-v8a. The instance is fixed, the files and the task differ. */
    public static final String ARG_MACOS_INSTANCE = "macos_instance";
    /** Set by a Build 41 instance's Settings: the same multiplayer import as always, instance fixed. */
    public static final String ARG_MP_INSTANCE = "mp_instance";
    private String macosInstanceName;

    private FragmentInstallNativeLibsBinding binding;
    private TaskProgressDialogBinding taskProgressDialogBinding;
    private AlertDialog taskProgressDialog;
    private boolean isInstallerServiceBound = false;
    // Kept so a finished task stops the service it ran in, not whatever is started later.
    private InstallerService installerService;

    private final String ZIP_MIME = "application/zip";
    private Uri archiveUri = null;
    private List<GameInstance> instances;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            installerService = binder.getService();
            isInstallerServiceBound = true;
            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(InstallNativeLibsFragment.this, InstallNativeLibsFragment.this::handleTaskState);
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

    private final ActivityResultLauncher<String> zipLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    archiveUri = uri;
                    binding.nativeLibsZipPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInstallNativeLibsBinding.inflate(inflater, container, false);
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

        binding.nativeLibsBannerIv.setImageResource(R.drawable.banner_default);

        instances = GameInstanceManager.requireSingleton().getInstances();

        List<String> names = new ArrayList<>();
        if (instances == null || instances.isEmpty()) {
            instances = new ArrayList<>();
            names.add(getString(R.string.select_instance));
            binding.nativeLibsInstallBtn.setEnabled(false);
        } else {
            if (instances.size() > 1) names.add(getString(R.string.select_instance));
            for (GameInstance gi : instances) names.add(gi.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.nativeLibsInstanceSpinner.setAdapter(adapter);
        binding.nativeLibsInstanceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        int idx = instances.size() > 1 ? position - 1 : position;
                        if (idx < 0 || idx >= instances.size()) {
                            binding.nativeLibsBannerIv.setImageResource(R.drawable.banner_default);
                            binding.nativeLibsBannerOverlay.setVisibility(View.INVISIBLE);
                            return;
                        }
                        GameInstance selected = instances.get(idx);
                        int bannerRes;
                        switch (selected.getPresetName()) {
                            case "Build 42.12+": bannerRes = R.drawable.banner_build42_12; break;
                            case "Build 42":     bannerRes = R.drawable.banner_build42;    break;
                            default:             bannerRes = R.drawable.banner_build41;    break;
                        }
                        binding.nativeLibsBannerIv.setImageResource(bannerRes);
                        binding.nativeLibsBannerOverlay.setVisibility(View.VISIBLE);
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                        binding.nativeLibsBannerIv.setImageResource(R.drawable.banner_default);
                        binding.nativeLibsBannerOverlay.setVisibility(View.INVISIBLE);
                    }
                });

        if (instances.size() == 1) binding.nativeLibsInstanceSpinner.setSelection(0);
        macosInstanceName = getArguments() == null ? null : getArguments().getString(ARG_MACOS_INSTANCE);
        String mpInstanceName = getArguments() == null ? null : getArguments().getString(ARG_MP_INSTANCE);
        if (macosInstanceName != null) binding.nativeLibsHintTv.setText(R.string.macos_libs_import_hint);
        String fixedInstance = macosInstanceName != null ? macosInstanceName : mpInstanceName;
        if (fixedInstance != null) {
            for (int i = 0; i < instances.size(); i++) {
                if (!fixedInstance.equals(instances.get(i).getName())) continue;
                binding.nativeLibsInstanceSpinner.setSelection(instances.size() > 1 ? i + 1 : i);
                break;
            }
            binding.nativeLibsInstanceSpinner.setEnabled(false);
        }

        binding.nativeLibsBrowseIb.setOnClickListener(v -> zipLauncher.launch(ZIP_MIME));

        binding.nativeLibsInstallBtn.setOnClickListener(v -> {
            if (archiveUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            int position = binding.nativeLibsInstanceSpinner.getSelectedItemPosition();
            int idx = instances.size() > 1 ? position - 1 : position;
            if (idx < 0 || idx >= instances.size()) {
                Toast.makeText(requireContext(),
                        getString(R.string.select_instance),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            GameInstance selectedInstance = instances.get(idx);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                    (macosInstanceName != null ? InstallerService.Task.INSTALL_MACOS_LIBS
                                               : InstallerService.Task.INSTALL_NATIVE_LIBS).ordinal());
            installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(InstallerService.EXTRA_NATIVE_LIBS_URI, archiveUri);

            archiveUri = null;
            binding.nativeLibsZipPathEt.setText(getString(R.string.game_instance_no_file_selected));

            requireContext().startForegroundService(installerIntent);
            bindInstallerService();
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