package com.zomdroid.fragments;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.io.File;

public class InstallControlsFragment extends Fragment {

    private FragmentInstallControlsBinding binding;
    private final String ZIP_MIME = "application/zip";

    private Uri controlsZipUri = null;
    private List<GameInstance> instances;

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

                int position = binding.installControlsInstanceSpinner.getSelectedItemPosition();
                if (position < 0 || position >= instances.size()) {
                    Toast.makeText(requireContext(), "Invalid instance selected", Toast.LENGTH_SHORT).show();
                    return;
                }

                GameInstance selectedInstance = instances.get(position);

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

                Toast.makeText(requireContext(),
                        "Controls export started",
                        Toast.LENGTH_SHORT).show();
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInstallControlsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances == null || instances.isEmpty()) {
            Toast.makeText(requireContext(), "No game instances found", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) names.add(gi.getName());

        binding.installControlsInstanceSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                names
        ));

        binding.installControlsInstallBtn.setEnabled(false);

        binding.installControlsBrowseIb.setOnClickListener(v ->
                actionOpenControlsLauncher.launch(ZIP_MIME)
        );

        binding.installControlsInstallBtn.setOnClickListener(v -> {
            if (controlsZipUri == null) {
                Toast.makeText(requireContext(), R.string.game_instance_no_file_selected, Toast.LENGTH_SHORT).show();
                return;
            }

            int position = binding.installControlsInstanceSpinner.getSelectedItemPosition();
            if (position < 0 || position >= instances.size()) {
                Toast.makeText(requireContext(), "Invalid instance selected", Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance selectedInstance = instances.get(position);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_CONTROLS_TO_INSTANCE.ordinal());
            installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName());
            installerIntent.putExtra(InstallerService.EXTRA_CONTROLS_URI,
                    controlsZipUri);

            requireContext().startForegroundService(installerIntent);

            clearSelectedZip();

            Toast.makeText(requireContext(), "Controls installation successfull", Toast.LENGTH_SHORT).show();
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
            int position = binding.installControlsInstanceSpinner.getSelectedItemPosition();
            if (position < 0 || position >= instances.size()) {
                Toast.makeText(requireContext(), "Invalid instance selected", Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance gi = instances.get(position);

            // ВАЖНО: проверяем ДО CreateDocument
            java.io.File controlsDir = new java.io.File(gi.getGamePath(), "controls");

            if (!controlsDir.exists()) {
                Toast.makeText(requireContext(),
                        getString(R.string.dialog_title_controls_export_skipped_default),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(new java.util.Date());
            String suggested = "zomdroid_controls_" + ts + ".zip";
            actionCreateControlsZipLauncher.launch(suggested);
        });
    }

    private void clearSelectedZip() {
        controlsZipUri = null;
        if (binding != null) {
            binding.installControlsZipPathEt.setText(getString(R.string.game_instance_no_file_selected));
            binding.installControlsInstallBtn.setEnabled(false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
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

    private boolean hasControlsToExport(GameInstance gi) {
        if (gi == null) return false;
        File controlsDir = new File(gi.getGamePath(), "controls");
        File controlsJson = new File(controlsDir, "controls.json");
        return controlsDir.isDirectory() && controlsJson.isFile() && controlsJson.length() > 0;
    }
}
