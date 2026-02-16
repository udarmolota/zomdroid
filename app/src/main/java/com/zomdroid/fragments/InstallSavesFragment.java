package com.zomdroid.fragments;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentInstallSavesBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import androidx.appcompat.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.TextView;

public class InstallSavesFragment extends Fragment {

    private FragmentInstallSavesBinding binding;

    private static final String ZIP_MIME = "application/zip";

    private Uri savesZipUri = null;
    private List<GameInstance> instances;

    // Import: ZIP picker
    private final ActivityResultLauncher<String> actionOpenSavesLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;

                ContentResolver cr = requireContext().getContentResolver();
                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    savesZipUri = uri;
                    binding.installSavesZipPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    // Export: create ZIP document
    private final ActivityResultLauncher<String> actionCreateSavesZipLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(ZIP_MIME), outUri -> {
                if (outUri == null) return;

                GameInstance selectedInstance = getSelectedInstanceOrNull();
                if (selectedInstance == null) return;

                Intent installerIntent = new Intent(requireContext(), InstallerService.class);
                installerIntent.putExtra(
                        InstallerService.EXTRA_COMMAND,
                        InstallerService.Task.EXPORT_SAVES_FROM_INSTANCE.ordinal()
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
                        "Saves export started",
                        Toast.LENGTH_SHORT).show();
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        binding = FragmentInstallSavesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances == null || instances.isEmpty()) {
            Toast.makeText(requireContext(),
                    "No game instances found",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Spinner population
        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) {
            names.add(gi.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                names
        );
        binding.installSavesInstanceSpinner.setAdapter(adapter);

        // Import: Browse ZIP
        binding.installSavesBrowseIb.setOnClickListener(v ->
                actionOpenSavesLauncher.launch(ZIP_MIME)
        );

        // Import: Install
        binding.installSavesInstallBtn.setOnClickListener(v -> {
            if (savesZipUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance selectedInstance = getSelectedInstanceOrNull();
            if (selectedInstance == null) return;

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_SAVES_TO_INSTANCE.ordinal()
            );
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName()
            );
            installerIntent.putExtra(
                    InstallerService.EXTRA_SAVES_URI,
                    savesZipUri
            );

            requireContext().startForegroundService(installerIntent);

            clearSelectedSavesZip();

            Toast.makeText(requireContext(),
                    "Saves import started",
                    Toast.LENGTH_SHORT).show();
        });

        // Export: button (добавь эту кнопку в layout)
        binding.installSavesExportBtn.setOnClickListener(v -> {
            // имя по умолчанию — чтобы юзеру было удобно
            String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            String defaultName = "zomdroid_saves_" + ts + ".zip";
            actionCreateSavesZipLauncher.launch(defaultName);
        });

        // Help
        binding.installSavesZipHelpIb.setOnClickListener(v -> {
            MaterialAlertDialogBuilder builder =
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.install_saves_zip_help_title)
                            .setMessage(getString(R.string.install_saves_zip_help_message))
                            .setPositiveButton(android.R.string.ok, null);

            AlertDialog dialog = builder.show();
            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) {
                messageView.setTypeface(Typeface.MONOSPACE);
            }
        });
    }

    private GameInstance getSelectedInstanceOrNull() {
        int position = binding.installSavesInstanceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= instances.size()) {
            Toast.makeText(requireContext(),
                    "Invalid instance selected",
                    Toast.LENGTH_SHORT).show();
            return null;
        }
        return instances.get(position);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void clearSelectedSavesZip() {
        savesZipUri = null;
        if (binding != null) {
            binding.installSavesZipPathEt.setText(getString(R.string.game_instance_no_file_selected));
        }
    }

    private String extractFileName(Uri uri) {
        String fileName = null;

        Cursor cursor = requireContext().getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null,
                null,
                null
        );

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
