package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

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
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        ArrayAdapter<LauncherPreferences.VulkanDriver> vulkanDriverAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, LauncherPreferences.VulkanDriver.values());
        binding.settingsVulkanDriverS.setAdapter(vulkanDriverAdapter);
        binding.settingsVulkanDriverS.setSelection(vulkanDriverAdapter.getPosition(LauncherPreferences.requireSingleton().getVulkanDriver()));
        binding.settingsVulkanDriverS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.VulkanDriver vulkanDriver = (LauncherPreferences.VulkanDriver) parent.getSelectedItem();
                LauncherPreferences.requireSingleton().setVulkanDriver(vulkanDriver);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
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
    }



    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        LauncherPreferences.requireSingleton().saveToDisk();
    }
}
