package com.zomdroid.fragments;

import android.content.ContentResolver;
import android.content.Context;
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
import com.zomdroid.LauncherPreferences;

import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NewGameInstanceFragment extends Fragment {
    private FragmentNewGameInstanceBinding binding;
    private final String ZIP_MIME = "application/zip";

    private enum GpuVendor { QUALCOMM, MEDIATEK, UNKNOWN }

    private InstallationPreset detectedPreset = null;

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
                InstallationPreset preset = detectPreset(appCtx, uri);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null) return;
                    binding.newGameInstanceCreateBtn.setEnabled(true);
                    if (preset == null) {
                        Toast.makeText(requireContext(), R.string.new_game_instance_detect_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    detectedPreset = preset;
                    startInstall(name, preset);
                });
            }).start();
        });
    }

    // Picks the renderer for the new instance, then hands off to finishInstall. Must run on UI thread.
    private void startInstall(String name, InstallationPreset selectedPreset) {
        // Build 42 renderer choice. On any non-Adreno GPU (Mali across MediaTek/Exynos/Tensor,
        // and unknowns) offer the experimental NG_GL4ES; Cancel keeps the stable ZINK default
        // (best for ANGLE users too). Confirmed-Qualcomm/Adreno stays on ZINK automatically.
        // Build 41 keeps the current renderer.
        if ("42".equals(selectedPreset.buildVersion)) {
            // NG_GL4ES currently supports Build 42 only up to ~42.12. The "Build 42" preset
            // (42.6–42.11) is safe to offer it on; "Build 42.12+" spans versions NG can't yet
            // handle, so that preset always gets ZINK.
            boolean ngSupported = "Build 42".equals(selectedPreset.name);
            if (ngSupported && detectGpuVendor() != GpuVendor.QUALCOMM) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.ng_offer_title)
                        .setMessage(R.string.ng_offer_message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.ng_offer_use_ng, (d, w) -> {
                            LauncherPreferences.requireSingleton().setRenderer(LauncherPreferences.Renderer.NG_GL4ES);
                            finishInstall(name, selectedPreset);
                        })
                        .setNegativeButton(R.string.ng_offer_use_zink, (d, w) -> {
                            LauncherPreferences.requireSingleton().setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
                            finishInstall(name, selectedPreset);
                        })
                        .show();
                return;
            }
            LauncherPreferences.requireSingleton().setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
        }
        finishInstall(name, selectedPreset);
    }

    // Builds the GameInstance and starts the installer service. Must run on the UI thread.
    private void finishInstall(String name, InstallationPreset selectedPreset) {
        if (!isAdded() || binding == null) return;

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

    // Scans the selected game ZIP in a background thread, sets detectedPreset and updates banner.
    // This is a UX convenience (early banner); Create button also detects synchronously as a fallback.
    private void detectAndSelectPreset(Uri zipUri) {
        Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            InstallationPreset preset = detectPreset(ctx, zipUri);
            if (preset == null || !isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                detectedPreset = preset;
                FragmentNewGameInstanceBinding b = binding;
                if (b == null) return;
                b.newGameInstanceBannerIv.setImageResource(bannerForPreset(preset));
            });
        }).start();
    }

    private int bannerForPreset(InstallationPreset preset) {
        switch (preset.name) {
            case "Build 42.12+": return R.drawable.banner_build42_12;
            case "Build 42":     return R.drawable.banner_build42;
            default:             return R.drawable.banner_build41;
        }
    }

    // Detects the matching InstallationPreset from the game ZIP, or null on failure.
    // Tries fast central-directory read (ZipFile via /proc/self/fd/); if that provider/fd
    // isn't seekable, falls back to a sequential ZipInputStream scan (works on any stream).
    //   android/   → Build 42.12+
    //   imgui*.jar → Build 42
    //   neither    → Build 41
    private InstallationPreset detectPreset(Context ctx, Uri zipUri) {
        long t0 = System.currentTimeMillis();
        int idx = detectPresetIndexCentralDir(ctx, zipUri);
        android.util.Log.i("PresetDetect", "detectPreset: idx=" + idx
                + " took=" + (System.currentTimeMillis() - t0) + "ms");
        if (idx < 0) return null;
        List<InstallationPreset> presets = PresetManager.getPresets();
        if (idx >= presets.size()) return null;
        return presets.get(idx);
    }

    // presets index: 0=Build 42, 1=Build 42.12+, 2=Build 41
    // Reads ONLY the ZIP central directory (the archive's index at the end of the file)
    // via random access on the seekable content fd. This reads a few hundred KB regardless
    // of ZIP size — no scanning of entry data. Returns -1 if it can't (e.g. non-seekable fd).
    private int detectPresetIndexCentralDir(Context ctx, Uri zipUri) {
        android.os.ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(zipUri, "r");
            if (pfd == null) return -1;
            java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.nio.channels.FileChannel ch = fis.getChannel();
            long fileSize = ch.size();
            if (fileSize < 22) return -1; // smaller than a minimal EOCD record

            // 1) Locate End Of Central Directory (EOCD, signature PK\5\6) in the tail.
            int tailLen = (int) Math.min(fileSize, 64 * 1024 + 22);
            long tailStart = fileSize - tailLen;
            java.nio.ByteBuffer tail = readAt(ch, tailStart, tailLen);
            int eocdPos = -1;
            for (int i = tailLen - 22; i >= 0; i--) {
                if (tail.getInt(i) == 0x06054b50) { eocdPos = i; break; }
            }
            if (eocdPos < 0) return -1;

            long cdSize      = tail.getInt(eocdPos + 12) & 0xFFFFFFFFL;
            long cdOffset    = tail.getInt(eocdPos + 16) & 0xFFFFFFFFL;
            long totalEntries = tail.getShort(eocdPos + 10) & 0xFFFF;

            // 2) Zip64 fallback (archives > 4GB or > 65535 entries use sentinel values).
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || totalEntries == 0xFFFF) {
                long locPos = tailStart + eocdPos - 20; // Zip64 EOCD locator sits before EOCD
                if (locPos >= 0) {
                    java.nio.ByteBuffer loc = readAt(ch, locPos, 20);
                    if (loc.getInt(0) == 0x07064b50) {
                        long z64Off = loc.getLong(8);
                        java.nio.ByteBuffer z = readAt(ch, z64Off, 56);
                        if (z.getInt(0) == 0x06064b50) {
                            cdSize   = z.getLong(40);
                            cdOffset = z.getLong(48);
                        }
                    }
                }
            }

            if (cdOffset < 0 || cdSize <= 0 || cdOffset + cdSize > fileSize) return -1;
            if (cdSize > 64L * 1024 * 1024) return -1; // sanity guard against absurd CD size

            // 3) Read the central directory and scan entry filenames only.
            java.nio.ByteBuffer cd = readAt(ch, cdOffset, (int) cdSize);
            boolean hasImguiJar = false;
            int p = 0;
            int cap = cd.capacity();
            while (p + 46 <= cap) {
                if (cd.getInt(p) != 0x02014b50) break; // central-dir file header signature PK\1\2
                int nameLen    = cd.getShort(p + 28) & 0xFFFF;
                int extraLen   = cd.getShort(p + 30) & 0xFFFF;
                int commentLen = cd.getShort(p + 32) & 0xFFFF;
                int nameStart  = p + 46;
                if (nameStart + nameLen > cap) break;
                byte[] nameBytes = new byte[nameLen];
                for (int i = 0; i < nameLen; i++) nameBytes[i] = cd.get(nameStart + i);
                String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                if (isAndroidDirEntry(name)) return 1; // Build 42.12+
                if (isImguiJar(name)) hasImguiJar = true;
                p = nameStart + nameLen + extraLen + commentLen;
            }
            return hasImguiJar ? 0 : 2; // Build 42 : Build 41
        } catch (Exception e) {
            android.util.Log.w("PresetDetect", "central-dir read failed: " + e, e);
            return -1;
        } finally {
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        }
    }

    // Reads exactly len bytes at absolute position pos into a little-endian buffer (index-0 based).
    private java.nio.ByteBuffer readAt(java.nio.channels.FileChannel ch, long pos, int len) throws java.io.IOException {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(len);
        ch.position(pos);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) break;
        }
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.clear(); // reset position/limit so absolute getInt/getShort span the whole buffer
        return buf;
    }

    // Matches an "android" directory at ANY depth — tolerant of wrapper folders
    // (e.g. "PZ/android/arm64-v8a/...") so version detection still works on nested zips.
    private boolean isAndroidDirEntry(String name) {
        String n = name.replace('\\', '/');
        return n.equals("android") || n.startsWith("android/")
                || n.endsWith("/android") || n.contains("/android/");
    }

    private boolean isImguiJar(String name) {
        String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        String baseLower = base.toLowerCase();
        return baseLower.startsWith("imgui") && baseLower.endsWith(".jar");
    }

    private GpuVendor detectGpuVendor() {
        // Try /proc/cpuinfo first
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("qualcomm") || lower.contains("snapdragon")) {
                    reader.close();
                    return GpuVendor.QUALCOMM;
                }
                if (lower.contains("mediatek") || lower.contains("dimensity") || lower.contains("helio")) {
                    reader.close();
                    return GpuVendor.MEDIATEK;
                }
            }
            reader.close();
        } catch (Exception ignored) {}
    
        // Fallback: android.os.Build fields
        String[] buildFields = {
            android.os.Build.HARDWARE,
            android.os.Build.BOARD,
            android.os.Build.SOC_MODEL,     // API 31+
            android.os.Build.SOC_MANUFACTURER // API 31+
        };
        for (String field : buildFields) {
            if (field == null) continue;
            String lower = field.toLowerCase();
            if (lower.contains("qcom") || lower.contains("qualcomm") || lower.contains("snapdragon")) {
                return GpuVendor.QUALCOMM;
            }
            if (lower.contains("mt") || lower.contains("mediatek") || lower.contains("dimensity") || lower.contains("helio")) {
                return GpuVendor.MEDIATEK;
            }
        }
    
        return GpuVendor.UNKNOWN;
    }
}
