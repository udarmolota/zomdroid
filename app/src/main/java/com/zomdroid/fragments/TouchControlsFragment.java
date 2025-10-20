package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.zomdroid.GamepadManager;
import com.zomdroid.LauncherPreferences;
import com.zomdroid.databinding.FragmentTouchControlsBinding;
import com.zomdroid.R;

public class TouchControlsFragment extends Fragment {
    private FragmentTouchControlsBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate layout using ViewBinding
        binding = FragmentTouchControlsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Load current preference state
        boolean isEnabled = LauncherPreferences.requireSingleton().isTouchControlsEnabled();
        binding.touchControlsSwitch.setChecked(isEnabled);

        // Handle switch toggle
        binding.touchControlsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.requireSingleton().setTouchControlsEnabled(isChecked);
            GamepadManager.setTouchOverride(!isChecked);

            Toast.makeText(requireContext(),
                (CharSequence) (isChecked ? getString(R.string.touch_controls_enabled_toast)
                              : getString(R.string.touch_controls_disabled_toast)),
                Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        // Save preferences when fragment is paused
        LauncherPreferences.requireSingleton().saveToPreferences();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
