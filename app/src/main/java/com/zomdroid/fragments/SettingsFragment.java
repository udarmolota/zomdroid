package com.zomdroid.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.zomdroid.LauncherPreferences;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentSettingsBinding;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        ArrayAdapter<LauncherPreferences.Renderer> rendererArrayAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, LauncherPreferences.Renderer.values());
        binding.settingsRendererS.setAdapter(rendererArrayAdapter);
        binding.settingsRendererS.setSelection(rendererArrayAdapter.getPosition(LauncherPreferences.requireSingleton().getRenderer()));
        binding.settingsRendererS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.Renderer renderer = (LauncherPreferences.Renderer) parent.getSelectedItem();
                LauncherPreferences.requireSingleton().setRenderer(renderer);
                switch (renderer) {
                    case ZINK_ZFA:
                    case ZINK_OSMESA:
                        binding.settingsVulkanDriverTv.setVisibility(View.VISIBLE);
                        binding.settingsVulkanDriverS.setVisibility(View.VISIBLE);
                        break;
                    default:
                        binding.settingsVulkanDriverTv.setVisibility(View.GONE);
                        binding.settingsVulkanDriverS.setVisibility(View.GONE);
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        binding.settingsRenderHintHelpIb.setOnClickListener(v -> {
            Navigation.findNavController(v).navigate(R.id.wiki_fragment);
        });

        ArrayAdapter<LauncherPreferences.VulkanDriver> vulkanDriverAdapter =
                new ArrayAdapter<>(requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        LauncherPreferences.VulkanDriver.values());

        binding.settingsVulkanDriverS.setAdapter(vulkanDriverAdapter);
        binding.settingsVulkanDriverS.setSelection(
                vulkanDriverAdapter.getPosition(LauncherPreferences.requireSingleton().getVulkanDriver())
        );

        final boolean[] isInitialSelection = { true };

        binding.settingsVulkanDriverS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.VulkanDriver vulkanDriver =
                        (LauncherPreferences.VulkanDriver) parent.getSelectedItem();

                LauncherPreferences.requireSingleton().setVulkanDriver(vulkanDriver);

                if (isInitialSelection[0]) {
                    isInitialSelection[0] = false;
                    return;
                }

                if (vulkanDriver == LauncherPreferences.VulkanDriver.FREEDRENO_8XX_Expr) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Freedreno 8xx")
                            .setMessage(
                                    "Freedreno 8xx is experimental.\n\n" +
                                    "If you get crashes or rendering issues, switch back to System driver.\n" +
                                    "Recommended for Adreno 840/830/825/810/829. Snapdragon 8 Gen 3/4/5/Elite and 7 Gen 3 devices."
                            )
                            .setPositiveButton("OK", null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_bbdd688_8gen2) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("TURNIP bbdd688 8g2")
                            .setMessage(
                                    "This is for Snapdragon 8 Gen 2 only.\n\n" +
                                    "Fixes flickering that may occur after updating to HyperOS 3."
                            )
                            .setPositiveButton("OK", null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_bbdd688) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("TURNIP bbdd688")
                            .setMessage(
                                    "This driver is intended for Adreno 7xx GPUs.\n\n" +
                                    "Snapdragon 4-series, 5-series, and Elite (Adreno 8xx) devices are not supported.\n\n" +
                                    "For Snapdragon 4 / 5 / Elite select Freedreno 8xx."
                            )
                            .setPositiveButton("OK", null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.Turnip_25_1_3_GMEM) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("TURNIP 25.1.3 GMEM")
                        .setMessage(
                                "This driver is intended for Adreno 710 GPU only.\n\n" +
                                "Mostly for Snapdragon 7 Gen 1 and 778G+ devices."
                        )
                        .setPositiveButton("OK", null)
                        .show();
            }

            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        binding.settingsResolutionScaleSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                binding.settingsResolutionScalePercentTv.setText(getResources().getString(R.string.percentage_format, progress));
                LauncherPreferences.requireSingleton().setRenderScale((float) progress / 100);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        binding.settingsResolutionScaleSb.setProgress((int) (LauncherPreferences.requireSingleton().getRenderScale() * 100));

        ArrayAdapter<LauncherPreferences.AudioAPI> audioAPIAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, LauncherPreferences.AudioAPI.values());
        binding.settingsAudioApiS.setAdapter(audioAPIAdapter);
        binding.settingsAudioApiS.setSelection(audioAPIAdapter.getPosition(LauncherPreferences.requireSingleton().getAudioAPI()));
        binding.settingsAudioApiS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.AudioAPI audioAPI = (LauncherPreferences.AudioAPI) parent.getSelectedItem();
                LauncherPreferences.requireSingleton().setAudioAPI(audioAPI);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        binding.settingsJargsEt.setText(LauncherPreferences.requireSingleton().getJvmArgs());

        binding.settingsJargsEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                LauncherPreferences.requireSingleton().setJvmArgs(s.toString().trim());
            }
        });

        binding.settingsDebugSwitch.setChecked(LauncherPreferences.requireSingleton().isDebug());
        binding.settingsDebugSwitch.setOnCheckedChangeListener((v, isChecked) ->
            LauncherPreferences.requireSingleton().setDebug(isChecked));

        binding.settingsJargsInfo.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("JVM arguments")
                    .setMessage("Additional JVM arguments, e.g.:\n\n" +
                            "• -Xmx2G - allocate 2Gb of memory\n" +
                            "• -Xms1G - process starts with 1Gb RAM\n" +
                            "• -XX:+UseG1GC - enable G1 garbage collector\n\n" +
                            "Right now, we're not sure how much of an impact allocating more memory will have, but in theory, it should help when there's not enough.\n" +
                            "Some arguments are already set by settings.")
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        LauncherPreferences.requireSingleton().saveToPreferences();
    }
}
