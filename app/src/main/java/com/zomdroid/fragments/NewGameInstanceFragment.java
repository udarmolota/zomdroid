package com.zomdroid.fragments;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.zomdroid.InstallerService;
import com.zomdroid.LauncherPreferences;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentNewGameInstanceBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.InstallationPreset;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.game.PresetManager;
import com.zomdroid.gog.GogInstallerExtractor;
import com.zomdroid.gog.PrefixedZip;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

public class NewGameInstanceFragment extends Fragment {
    private FragmentNewGameInstanceBinding binding;
    private final String ZIP_MIME = "application/zip";

    private enum GpuVendor { QUALCOMM, MEDIATEK, UNKNOWN }

    private InstallationPreset detectedPreset = null;

    /**
     * Navigation argument: the absolute path of an archive to install, so the screen opens with the
     * file already chosen. The GOG downloader hands over the installer it fetched this way, because
     * the picker below is ZIP-only and most file managers would not show it a bare .sh anyway.
     */
    public static final String ARG_PRESELECTED_FILE = "preselected_file";

    // What kind of archive gameFilesZipUri is; InstallerService unpacks each differently.
    private String archiveKind = GogInstallerExtractor.KIND_GAME_ZIP;

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
                    detectedPreset = null;
                    archiveKind = GogInstallerExtractor.KIND_GAME_ZIP;
                    binding.newGameInstanceBannerIv.setImageResource(R.drawable.banner_default);
                    String fileName = extractFileName(uri);
                    binding.newGameInstanceFilesPathEt.setText(fileName);
                    detectAndSelectPreset(uri);
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

        binding.newGameInstanceBannerIv.setImageResource(R.drawable.banner_default);
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
                            Navigation.findNavController(v).navigate(R.id.wiki_fragment, WikiFragment.section("native-libraries")))
                    .show();
        });

        // This used to be a shortcut to the JVM arguments block, on the reasoning that the
        // arguments were global and could therefore be set before the instance existed. They are
        // per-instance now, so there is nothing here for it to point at: the instance being
        // described on this screen has not been created yet. Nothing is lost - creating an
        // instance applies the matching preset by itself, and the dialog that follows opens the
        // new instance's settings on its "presets" button.
        binding.newGameInstanceOpenSettingsTv.setVisibility(View.GONE);

        // Browse button for game ZIP
        binding.newGameInstanceFilesBrowseIb.setOnClickListener(v ->
                actionOpenDocumentLauncher.launch(ZIP_MIME));

        // Browse button for native libs ZIP
        binding.newGameInstanceNativeLibsBrowseIb.setOnClickListener(v ->
                actionOpenNativeLibsLauncher.launch(ZIP_MIME));

        // Opened with the file already chosen (see ARG_PRESELECTED_FILE). A path of our own never
        // goes through the picker, so its ZIP-only rule does not apply; detection reads the
        // installer in place.
        String preselected = getArguments() == null ? null : getArguments().getString(ARG_PRESELECTED_FILE);
        if (preselected != null) {
            File chosen = new File(preselected);
            if (chosen.isFile()) {
                gameFilesZipUri = Uri.fromFile(chosen);
                detectedPreset = null;
                binding.newGameInstanceFilesPathEt.setText(chosen.getName());
                detectAndSelectPreset(gameFilesZipUri);
            }
        }

        // Create button
        binding.newGameInstanceCreateBtn.setOnClickListener(v -> {
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

            // Fast path: background detection already produced a preset.
            if (detectedPreset != null) {
                startInstall(name, detectedPreset);
                return;
            }

            // Detection not ready yet — NEVER scan on the UI thread (huge ZIPs → ANR).
            // Run detection in the background, keep UI responsive, continue when done.
            binding.newGameInstanceCreateBtn.setEnabled(false);
            Toast.makeText(requireContext(), R.string.new_game_instance_analyzing, Toast.LENGTH_SHORT).show();
            final Uri uri = gameFilesZipUri;
            final Context appCtx = requireContext().getApplicationContext();
            new Thread(() -> {
                Detection detection = detect(appCtx, uri);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null) return;
                    binding.newGameInstanceCreateBtn.setEnabled(true);
                    if (detection == null) {
                        Toast.makeText(requireContext(), R.string.new_game_instance_detect_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    apply(detection);
                    startInstall(name, detection.preset);
                });
            }).start();
        });
    }

    // Records the detected preset/GPU for post-install setup, then starts installation.
    private void startInstall(String name, InstallationPreset selectedPreset) {
        boolean isBuild42 = "42".equals(selectedPreset.buildVersion);
        GpuVendor gpu = isBuild42 ? detectGpuVendor() : GpuVendor.UNKNOWN;
        finishInstall(name, selectedPreset, gpu);
    }

    // Builds the GameInstance and starts the installer service. Must run on the UI thread.
    private void finishInstall(String name, InstallationPreset selectedPreset, GpuVendor gpu) {
        if (!isAdded() || binding == null) return;

        // No JVM-args seeding here: LauncherPreferences.DEFAULT_JVM_ARGS is the field's own default
        // now, so every install starts from it regardless of when instances are created. The Build
        // 42 set is opt-in through the button in Settings → Advanced, because its capped heap is
        // wrong for Build 41 on a 4 GB device.

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
        installerIntent.putExtra(InstallerService.EXTRA_INSTALL_PRESET_NAME, selectedPreset.name);
        installerIntent.putExtra(InstallerService.EXTRA_GPU_VENDOR, gpu.name());
        installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_KIND, archiveKind);
        if (nativeLibsZipUri != null) {
            installerIntent.putExtra(InstallerService.EXTRA_NATIVE_LIBS_URI, nativeLibsZipUri);
        }
        if (savesZipUri != null) {
            installerIntent.putExtra(InstallerService.EXTRA_SAVES_URI, savesZipUri);
        }
        if (modsZipUri != null) {
            installerIntent.putExtra(InstallerService.EXTRA_MODS_URI, modsZipUri);
        }

        // Start the service before navigating: returning to the launcher immediately fires its
        // dependency re-check, and the install must already own the service by then.
        requireContext().startForegroundService(installerIntent);
        Navigation.findNavController(binding.getRoot()).navigateUp();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private String extractFileName(Uri uri) {
        if (isFileUri(uri)) return new File(uri.getPath()).getName();
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

    // Scans the archive in a background thread and records what it found: preset, kind, banner.
    // This is a UX convenience (early banner); the Create button detects as a fallback.
    private void detectAndSelectPreset(Uri zipUri) {
        Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            Detection detection = detect(ctx, zipUri);
            if (detection == null || !isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                apply(detection);
            });
        }).start();
    }

    // Records a detection. A ZIP of GOG installers keeps the default banner: which build is inside
    // is only known once the installer is unpacked, and the placeholder preset would mislead.
    private void apply(Detection detection) {
        detectedPreset = detection.preset;
        archiveKind = detection.archiveKind;
        FragmentNewGameInstanceBinding b = binding;
        if (b == null) return;
        b.newGameInstanceBannerIv.setImageResource(GogInstallerExtractor.KIND_BUNDLE.equals(detection.archiveKind)
                ? R.drawable.banner_default : bannerForPreset(detection.preset));
    }

    private int bannerForPreset(InstallationPreset preset) {
        switch (preset.name) {
            case "Build 42.12+": return R.drawable.banner_build42_12;
            case "Build 42":     return R.drawable.banner_build42;
            default:             return R.drawable.banner_build41;
        }
    }

    /** What the archive turned out to be. */
    private static final class Detection {
        final InstallationPreset preset;
        final String archiveKind;

        Detection(InstallationPreset preset, String archiveKind) {
            this.preset = preset;
            this.archiveKind = archiveKind;
        }
    }

    // Reads only the archive's central directory - the index at its end, a few hundred KB whatever
    // the archive size - through a seekable file descriptor, and picks the preset from the entry
    // names (PresetManager holds the rules). A bare GOG installer is a ZIP behind a shell script,
    // which PrefixedZip reads in place. A ZIP holding nothing but installers cannot say which build
    // is inside: it gets Build 41 as a placeholder, and InstallerService picks the real preset from
    // the extracted files. Returns null if the archive cannot be read (e.g. a non-seekable provider).
    private Detection detect(Context ctx, Uri uri) {
        long t0 = System.currentTimeMillis();
        List<String> names = entryNames(ctx, uri);
        if (names == null) return null;
        String kind = GogInstallerExtractor.KIND_GAME_ZIP;
        int idx;
        if (isFileUri(uri) && GogInstallerExtractor.isMakeselfInstaller(new File(uri.getPath()))) {
            kind = GogInstallerExtractor.KIND_INSTALLER;
            idx = PresetManager.detectPresetIndex(names);
        } else if (GogInstallerExtractor.isInstallerBundle(names)) {
            kind = GogInstallerExtractor.KIND_BUNDLE;
            idx = PresetManager.PRESET_BUILD_41;
        } else {
            idx = PresetManager.detectPresetIndex(names);
        }
        android.util.Log.i("PresetDetect", "detect: idx=" + idx + " kind=" + kind + " entries=" + names.size()
                + " took=" + (System.currentTimeMillis() - t0) + "ms");
        List<InstallationPreset> presets = PresetManager.getPresets();
        if (idx < 0 || idx >= presets.size()) return null;
        return new Detection(presets.get(idx), kind);
    }

    // The entry names of the archive behind uri, or null if it cannot be read as a ZIP.
    private List<String> entryNames(Context ctx, Uri uri) {
        ParcelFileDescriptor pfd;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        } catch (Exception e) {
            android.util.Log.w("PresetDetect", "cannot open " + uri + ": " + e);
            return null;
        }
        if (pfd == null) return null;
        // Closing the archive closes the channel, the stream behind it and with it the descriptor.
        java.nio.channels.FileChannel ch = new ParcelFileDescriptor.AutoCloseInputStream(pfd).getChannel();
        boolean handedOver = false;
        try {
            ZipFile zf = PrefixedZip.open(ch);
            handedOver = true;
            try (ZipFile archive = zf) {
                List<String> names = new ArrayList<>();
                for (Enumeration<ZipArchiveEntry> en = archive.getEntries(); en.hasMoreElements(); )
                    names.add(en.nextElement().getName());
                return names;
            }
        } catch (Exception e) {
            android.util.Log.w("PresetDetect", "central-dir read failed: " + e, e);
            return null;
        } finally {
            if (!handedOver) {
                try { ch.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static boolean isFileUri(Uri uri) {
        return "file".equals(uri.getScheme());
    }

    // The detector itself lives in SuggestedPreset: Settings asks the same question to decide which
    // preset buttons to show, and a second copy of this would drift from it.
    private GpuVendor detectGpuVendor() {
        String vendor = com.zomdroid.game.SuggestedPreset.detectGpuVendor();
        if (com.zomdroid.game.SuggestedPreset.QUALCOMM.equals(vendor)) return GpuVendor.QUALCOMM;
        if (com.zomdroid.game.SuggestedPreset.MEDIATEK.equals(vendor)) return GpuVendor.MEDIATEK;
        return GpuVendor.UNKNOWN;
    }
}
