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
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        binding.settingsRenderHintHelpIb.setOnClickListener(v -> {
            Navigation.findNavController(v).navigate(R.id.wiki_fragment);
        });

        // Кастомный адаптер — отключает CUSTOM_DRIVER если файл не загружен
        ArrayAdapter<LauncherPreferences.VulkanDriver> vulkanDriverAdapter =
                new ArrayAdapter<LauncherPreferences.VulkanDriver>(requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        LauncherPreferences.VulkanDriver.values()) {
 
                    private boolean isCustomDriverAvailable() {
                        String homePath = com.zomdroid.AppStorage.requireSingleton().getHomePath();
                        if (homePath == null || homePath.isEmpty()) return false;
                        return new java.io.File(homePath, com.zomdroid.C.deps.CUSTOM_DRIVER).exists();
                    }
 
                    @Override
                    public boolean isEnabled(int position) {
                        LauncherPreferences.VulkanDriver item = getItem(position);
                        if (item == LauncherPreferences.VulkanDriver.CUSTOM_DRIVER) {
                            return isCustomDriverAvailable();
                        }
                        return true;
                    }
 
                    @Override
                    public View getDropDownView(int position, View convertView, ViewGroup parent) {
                        View v = super.getDropDownView(position, convertView, parent);
                        LauncherPreferences.VulkanDriver item = getItem(position);
                        if (item == LauncherPreferences.VulkanDriver.CUSTOM_DRIVER) {
                            v.setAlpha(isCustomDriverAvailable() ? 1f : 0.4f);
                        } else {
                            v.setAlpha(1f);
                        }
                        return v;
                    }
                };
 
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
                            .setTitle(getString(R.string.vulkan_driver_freedreno_8xx_title))
                            .setMessage(getString(R.string.vulkan_driver_freedreno_8xx_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.FREEDRENO_840_v26) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_freedreno_840_title))
                            .setMessage(getString(R.string.vulkan_driver_freedreno_840_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_bbdd688_8gen2) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_8gen2_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_8gen2_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_bbdd688) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_bbdd688_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_bbdd688_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.Turnip_25_1_3_GMEM) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_gmem_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_gmem_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.FREEDRENO) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_freedreno))
                            .setMessage(getString(R.string.vulkan_driver_freedreno_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
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
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
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
            public void onNothingSelected(AdapterView<?> parent) {}
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

        // Enviroment variables
        binding.settingsEnvVarsEt.setText(LauncherPreferences.requireSingleton().getEnvVars());

        binding.settingsEnvVarsEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                LauncherPreferences.requireSingleton().setEnvVars(s.toString().trim());
            }
        });

        binding.settingsDebugSwitch.setChecked(LauncherPreferences.requireSingleton().isDebug());
        binding.settingsDebugSwitch.setOnCheckedChangeListener((v, isChecked) ->
                LauncherPreferences.requireSingleton().setDebug(isChecked));

        binding.touchControlsSwitch.setChecked(LauncherPreferences.requireSingleton().isTouchControlsEnabled());
        binding.touchControlsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.requireSingleton().setTouchControlsEnabled(isChecked);
            GamepadManager.setTouchOverride(isChecked);
            Toast.makeText(requireContext(),
                isChecked ? getString(R.string.touch_controls_enabled_toast)
                          : getString(R.string.touch_controls_disabled_toast),
                Toast.LENGTH_SHORT).show();
        });

        binding.settingsJargsInfo.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.jvm_args_dialog_title))
                    .setMessage(getString(R.string.jvm_args_dialog_message))
                    .setPositiveButton(getString(R.string.dialog_button_ok), null)
                    .show();
        });

        binding.settingsEnvVarsInfo.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_env_vars))
                    .setMessage(getString(R.string.settings_env_vars_dialog_message))
                    .setPositiveButton(getString(R.string.dialog_button_ok), null)
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
