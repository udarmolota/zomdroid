package com.zomdroid.fragments;

import static androidx.core.content.ContextCompat.registerReceiver;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
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
import com.zomdroid.game.GameInstancesManager;
import com.zomdroid.game.PresetManager;

import java.nio.file.FileSystemException;
import java.util.Objects;

public class NewGameInstanceFragment extends Fragment {

    private FragmentNewGameInstanceBinding binding;


    private final String ZIP_MIME = "application/zip";
    private Uri gameFilesZipUri = null;


    private final ActivityResultLauncher<String> actionOpenDocumentLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) return;
                ContentResolver contentResolver = requireContext().getContentResolver();
                if (Objects.equals(contentResolver.getType(uri), ZIP_MIME)) {
                    gameFilesZipUri = uri;
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
                    binding.newGameInstanceFilesPathEt.setText(fileName);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.game_instance_unsupported_extension), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentNewGameInstanceBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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

                    GameInstance gameInstance = null;
                    try {
                        gameInstance = new GameInstance(name, (InstallationPreset) binding.newGameInstancePresetS.getSelectedItem());
                    } catch (FileSystemException e) {
                        throw new RuntimeException(e);
                    }

                    GameInstancesManager.requireSingleton().registerInstance(gameInstance);

                    Intent installerIntent = new Intent(requireContext(), InstallerService.class);
                    installerIntent.putExtra(InstallerService.EXTRA_COMMAND, InstallerService.Task.CREATE_GAME_INSTANCE.ordinal());
                    installerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME, gameInstance.getName());
                    installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, gameFilesZipUri);
                    Navigation.findNavController(binding.getRoot()).navigateUp();
                    requireContext().startForegroundService(installerIntent);

                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        binding.newGameInstanceNameEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus();
            }
            return false;
        });

        binding.newGameInstanceNameEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

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
            public void afterTextChanged(Editable s) {
            }
        });

        binding.newGameInstancePresetS.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, PresetManager.getPresets()));

        binding.newGameInstanceFilesHelpIb.setOnClickListener(v -> {
            Navigation.findNavController(v).navigate(R.id.wiki_fragment);
        });

        binding.newGameInstanceFilesBrowseIb.setOnClickListener(v -> {
            actionOpenDocumentLauncher.launch(ZIP_MIME);
        });



    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }


}