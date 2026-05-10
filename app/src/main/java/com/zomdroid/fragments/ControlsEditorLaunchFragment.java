package com.zomdroid.fragments;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.zomdroid.AppStorage;
import com.zomdroid.ControlsEditorActivity;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentControlsEditorLaunchBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ControlsEditorLaunchFragment extends Fragment {

    private FragmentControlsEditorLaunchBinding binding;

    // Background image is stored globally, not per-instance
    // Path: <AppStorage>/controls/editor_background.jpg
    private static final String BACKGROUND_FILENAME = "editor_background.jpg";

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                saveBackgroundImage(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentControlsEditorLaunchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.controlsEditorBannerIv.setImageResource(R.drawable.banner_default);

        refreshBackgroundState();

        // Browse for background image
        binding.controlsEditorBgBrowseIb.setOnClickListener(v ->
                pickImageLauncher.launch("image/*")
        );

        // Clear background
        binding.controlsEditorBgClearBtn.setOnClickListener(v -> {
            File bgFile = getBackgroundFile();
            if (bgFile.exists()) {
                bgFile.delete();
            }
            refreshBackgroundState();
            Toast.makeText(requireContext(),
                    R.string.controls_editor_bg_cleared,
                    Toast.LENGTH_SHORT).show();
        });

        // Open editor
        binding.controlsEditorOpenBtn.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), ControlsEditorActivity.class);
            File bgFile = getBackgroundFile();
            if (bgFile.exists()) {
                intent.putExtra(ControlsEditorActivity.EXTRA_BACKGROUND_PATH,
                        bgFile.getAbsolutePath());
            }
            startActivity(intent);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private File getBackgroundFile() {
        File controlsDir = new File(
                AppStorage.requireSingleton().getHomePath(), "controls");
        controlsDir.mkdirs();
        return new File(controlsDir, BACKGROUND_FILENAME);
    }

    private void refreshBackgroundState() {
        File bgFile = getBackgroundFile();
        boolean hasBg = bgFile.exists();

        binding.controlsEditorBgPathEt.setText(
                hasBg
                        ? bgFile.getName()
                        : getString(R.string.game_instance_no_file_selected)
        );
        binding.controlsEditorBgClearBtn.setVisibility(hasBg ? View.VISIBLE : View.GONE);
    }

    private void saveBackgroundImage(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(getBackgroundFile())) {
            if (in == null) throw new Exception("Cannot open image");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            refreshBackgroundState();
            Toast.makeText(requireContext(),
                    R.string.controls_editor_bg_saved,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.controls_editor_bg_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }
}