package com.zomdroid.fragments;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentNewGameInstanceBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.InstallationPreset;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.game.PresetManager;

import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NewGameInstanceFragment extends Fragment {
    private FragmentNewGameInstanceBinding binding;
    private final String ZIP_MIME = "application/zip";

    private boolean isPresetSelected = false;

    // URIs for selected ZIP files
    private Uri gameFilesZipUri = null;
    private Uri nativeLibsZipUri = null;
    private Uri savesZipUri = null;
    private Uri modsZipUri = null;

    // Launcher for selecting game ZIP
    private final ActivityResultLauncher<String> actionOpenDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver contentResolver = requireContext().getContentResolver();
                if (Objects.equals(contentResolver.getType(uri), ZIP_MIME)) {
                    gameFilesZipUri = uri;
                    String fileName = extractFileName(uri);
                    binding.newGameInstanceFilesPathEt.setText(fileName);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.game_instance_unsupported_extension), Toast.LENGTH_SHORT).show();
                }
            });

    // Launcher for selecting native libs ZIP
    private final ActivityResultLauncher<String> actionOpenNativeLibsLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                ContentResolver contentResolver = requireContext().getContentResolver();
                if (Objects.equals(contentResolver.getType(uri), ZIP_MIME)) {
                    nativeLibsZipUri = uri;
                    String fileName = extractFileName(uri);
                    binding.newGameInstanceNativeLibsPathEt.setText(fileName);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.game_instance_unsupported_extension), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentNewGameInstanceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Hide keyboard when user presses "Done"
        binding.newGameInstanceNameEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus();
            }
            return false;
        });

        // Validate game instance name as user types
        binding.newGameInstanceNameEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!GameInstance.isValidName(s.toString())) {
                    binding.newGameInstanceNameEt.setError(getString(R.string.game_instance_name_invalid));
                } else if (!GameInstance.isUniqueName(s.toString())) {
                    binding.newGameInstanceNameEt.setError(getString(R.string.game_instance_name_already_exists));
                } else {
                    binding.newGameInstanceNameEt.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Populate preset spinner with empty first item
        List<Object> presetItems = new ArrayList<>();
        presetItems.add(getString(R.string.new_game_instance_select_preset));
        presetItems.addAll(PresetManager.getPresets());
        ArrayAdapter<Object> presetAdapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.spinner_item,
                presetItems);
        presetAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.newGameInstancePresetS.setAdapter(presetAdapter);

        // Banner + preset dialog on spinner selection
        binding.newGameInstancePresetS.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    binding.newGameInstanceBannerIv.setImageResource(R.drawable.banner_default);
                    isPresetSelected = false;
                    return;
                }

                isPresetSelected = true;
                InstallationPreset preset = (InstallationPreset) parent.getItemAtPosition(position);

                // Update banner
                int bannerRes;
                switch (preset.name) {
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
                binding.newGameInstanceBannerIv.setImageResource(bannerRes);

                // Show preset info dialog
                int titleRes;
                int messageRes;
                switch (preset.name) {
                    case "Build 42.12+":
                        titleRes = R.string.preset_dialog_title_b4212;
                        messageRes = R.string.preset_dialog_message_b4212;
                        break;
                    case "Build 42":
                        titleRes = R.string.preset_dialog_title_b42;
                        messageRes = R.string.preset_dialog_message_b42;
                        break;
                    default:
                        titleRes = R.string.preset_dialog_title_b41;
                        messageRes = R.string.preset_dialog_message_b41;
                        break;
                }
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(titleRes)
                        .setMessage(messageRes)
                        .setPositiveButton(R.string.dialog_button_ok, null)
                        .show();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Help button for game files
        binding.newGameInstanceFilesHelpIb.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.game_instance_files_help_title)
                    .setMessage(R.string.game_instance_files_help_message)
                    .setPositiveButton(R.string.dialog_button_ok, null)
                    .show();
        });

        // Help button for native libs
        binding.newGameInstanceNativeLibsHelpIb.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.native_libs_dialog_title)
                    .setMessage(R.string.native_libs_dialog_message)
                    .setPositiveButton(R.string.dialog_button_ok, null)
                    .setNeutralButton(R.string.dialog_button_wiki, (dialog, which) ->
                            Navigation.findNavController(v).navigate(R.id.wiki_fragment))
                    .show();
        });

        // Browse button for game ZIP
        binding.newGameInstanceFilesBrowseIb.setOnClickListener(v ->
                actionOpenDocumentLauncher.launch(ZIP_MIME));

        // Browse button for native libs ZIP
        binding.newGameInstanceNativeLibsBrowseIb.setOnClickListener(v ->
                actionOpenNativeLibsLauncher.launch(ZIP_MIME));

        // Create button
        binding.newGameInstanceCreateBtn.setOnClickListener(v -> {
            if (!isPresetSelected) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.new_game_instance_no_preset_title)
                        .setMessage(R.string.new_game_instance_no_preset_selected)
                        .setPositiveButton(R.string.dialog_button_ok, null)
                        .show();
                return;
            }
            String name = binding.newGameInstanceNameEt.getText().toString();
            if (!GameInstance.isValidName(name)) {
                Toast.makeText(requireContext(), R.string.game_instance_name_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!GameInstance.isUniqueName(name)) {
                Toast.makeText(requireContext(), R.string.game_instance_name_already_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            if (gameFilesZipUri == null) {
                Toast.makeText(requireContext(), R.string.game_instance_no_file_selected, Toast.LENGTH_SHORT).show();
                return;
            }

            int presetIndex = binding.newGameInstancePresetS.getSelectedItemPosition() - 1;
            InstallationPreset selectedPreset = PresetManager.getPresets().get(presetIndex);

            GameInstance gameInstance;
            try {
                gameInstance = new GameInstance(name, selectedPreset);
            } catch (FileSystemException e) {
                throw new RuntimeException(e);
            }

            GameInstanceManager.requireSingleton().registerInstance(gameInstance);

            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND, InstallerService.Task.CREATE_GAME_INSTANCE.ordinal());
            installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME, gameInstance.getName());
            installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, gameFilesZipUri);
            if (nativeLibsZipUri != null) {
                installerIntent.putExtra(InstallerService.EXTRA_NATIVE_LIBS_URI, nativeLibsZipUri);
            }
            if (savesZipUri != null) {
                installerIntent.putExtra(InstallerService.EXTRA_SAVES_URI, savesZipUri);
            }
            if (modsZipUri != null) {
                installerIntent.putExtra(InstallerService.EXTRA_MODS_URI, modsZipUri);
            }

            Navigation.findNavController(binding.getRoot()).navigateUp();
            requireContext().startForegroundService(installerIntent);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
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