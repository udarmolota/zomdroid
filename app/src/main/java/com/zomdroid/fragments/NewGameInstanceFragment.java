package com.zomdroid.fragments;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.Navigation;

import com.zomdroid.InstallerService;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentNewGameInstanceBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.InstallationPreset;

import java.nio.file.FileSystemException;
import java.util.Objects;

public class NewGameInstanceFragment extends Fragment {
    private FragmentNewGameInstanceBinding binding;
    private final String ZIP_MIME = "application/zip";

    // URIs for selected ZIP files
    private Uri gameFilesZipUri = null;
    private Uri nativeLibsZipUri = null;

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

        // Menu setup
        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_new_game_instance, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_install) {
                    String name = binding.newGameInstanceNameEt.getText().toString();

                    if (!GameInstance.isValidName(name)) {
                        Toast.makeText(requireContext(), R.string.game_instance_name_invalid, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    if (!GameInstance.isUniqueName(name)) {
                        Toast.makeText(requireContext(), R.string.game_instance_name_already_exists, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    if (gameFilesZipUri == null) {
                        Toast.makeText(requireContext(), R.string.game_instance_no_file_selected, Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    GameInstance gameInstance;
                    try {
                        gameInstance = new GameInstance(name, (InstallationPreset) binding.newGameInstancePresetS.getSelectedItem());
                    } catch (FileSystemException e) {
                        throw new RuntimeException(e);
                    }

                    // Start InstallerService with both ZIPs
                    Intent intent = new Intent(requireContext(), InstallerService.class);
                    intent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME, name);
                    intent.putExtra(InstallerService.EXTRA_GAME_FILES_URI, gameFilesZipUri);
                    if (nativeLibsZipUri != null) {
                        intent.putExtra(InstallerService.EXTRA_NATIVE_LIBS_URI, nativeLibsZipUri);
                    }

                    requireContext().startService(intent);
                    Navigation.findNavController(requireView()).navigateUp();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        // Browse button for game ZIP
        binding.newGameInstanceFilesBrowseIb.setOnClickListener(v -> {
            actionOpenDocumentLauncher.launch(ZIP_MIME);
        });

        // Browse button for native libs ZIP
        binding.newGameInstanceNativeLibsBrowseIb.setOnClickListener(v -> {
            actionOpenNativeLibsLauncher.launch(ZIP_MIME);
        });
    }

    // Helper to extract file name from URI
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
