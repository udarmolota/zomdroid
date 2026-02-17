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

import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentInstallModBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//import android.app.AlertDialog;
import androidx.appcompat.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class InstallModFragment extends Fragment {

    private FragmentInstallModBinding binding;

    private final String ZIP_MIME = "application/zip";

    private Uri modZipUri = null;
    private List<GameInstance> instances;

    // ZIP picker
    private final ActivityResultLauncher<String> actionOpenModsLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;

                ContentResolver cr = requireContext().getContentResolver();

                if (Objects.equals(cr.getType(uri), ZIP_MIME)) {
                    modZipUri = uri;
                    binding.installModZipPathEt.setText(extractFileName(uri));
                } else {
                    Toast.makeText(requireContext(),
                            getString(R.string.game_instance_unsupported_extension),
                            Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        binding = FragmentInstallModBinding.inflate(inflater, container, false);
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

        binding.installModInstanceSpinner.setAdapter(adapter);

        // Browse button
        binding.installModBrowseIb.setOnClickListener(v ->
                actionOpenModsLauncher.launch(ZIP_MIME)
        );

        // Install button
        binding.installModInstallBtn.setOnClickListener(v -> {

            if (modZipUri == null) {
                Toast.makeText(requireContext(),
                        R.string.game_instance_no_file_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            int position = binding.installModInstanceSpinner.getSelectedItemPosition();
            if (position < 0 || position >= instances.size()) {
                Toast.makeText(requireContext(),
                        "Invalid instance selected",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            GameInstance selectedInstance = instances.get(position);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(
                    InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.INSTALL_MOD_TO_INSTANCE.ordinal()
            );
            installerIntent.putExtra(
                    InstallerService.EXTRA_GAME_INSTANCE_NAME,
                    selectedInstance.getName()
            );
            installerIntent.putExtra(
                    InstallerService.EXTRA_MODS_URI,
                    modZipUri
            );

            requireContext().startForegroundService(installerIntent);

            clearSelectedModZip();

            Toast.makeText(requireContext(),
                    "Mod import successfull",
                    Toast.LENGTH_SHORT).show();

        });

        binding.installModZipHelpIb.setOnClickListener(v -> {

            MaterialAlertDialogBuilder builder =
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.install_mod_zip_help_title)
                            .setMessage(getString(R.string.install_mod_zip_help_message))
                            .setPositiveButton(android.R.string.ok, null);

            AlertDialog dialog = builder.show();

            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) {
                messageView.setTypeface(Typeface.MONOSPACE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void clearSelectedModZip() {
        modZipUri = null;
        if (binding != null) {
            binding.installModZipPathEt.setText(getString(R.string.game_instance_no_file_selected));
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
