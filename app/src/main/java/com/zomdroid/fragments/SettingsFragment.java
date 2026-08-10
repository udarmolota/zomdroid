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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.zomdroid.LauncherPreferences;
import androidx.appcompat.app.AlertDialog;

import com.zomdroid.R;
import com.zomdroid.databinding.FragmentSettingsBinding;
import com.zomdroid.input.GamepadManager;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding binding;
    // Set once the renderer spinner has delivered its initial restore callback, so the NG_GL4ES
    // warning fires only for a deliberate change by the user.
    private boolean rendererSelectionRestored = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Renderer
        ArrayAdapter<LauncherPreferences.Renderer> rendererArrayAdapter = new ArrayAdapter<>(
            requireContext(),
            R.layout.spinner_item,
            LauncherPreferences.Renderer.values());
        rendererArrayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.settingsRendererS.setAdapter(rendererArrayAdapter);
        binding.settingsRendererS.setSelection(rendererArrayAdapter.getPosition(LauncherPreferences.requireSingleton().getRenderer()));
        binding.settingsRendererS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.Renderer renderer = (LauncherPreferences.Renderer) parent.getSelectedItem();
                LauncherPreferences.requireSingleton().setRenderer(renderer);
                // The spinner fires this once while being restored, before the user touches
                // anything. Warn only on a real choice, or opening Settings would greet everyone
                // with a dialog about a renderer they already use.
                if (rendererSelectionRestored && renderer == LauncherPreferences.Renderer.NG_GL4ES) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.renderer_ng_build42_only_title)
                            .setMessage(R.string.renderer_ng_build42_only_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                rendererSelectionRestored = true;
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

        //binding.settingsRenderHintHelpIb.setOnClickListener(v -> {
        //    Navigation.findNavController(v).navigate(R.id.wiki_fragment);
        //});

        // Custom adapter — switches off CUSTOM_DRIVER if no driver uploaded
        ArrayAdapter<LauncherPreferences.VulkanDriver> vulkanDriverAdapter =
                new ArrayAdapter<LauncherPreferences.VulkanDriver>(requireContext(),
                        R.layout.spinner_item,
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

        vulkanDriverAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
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

                if (vulkanDriver == LauncherPreferences.VulkanDriver.FREEDRENO_8XX) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_freedreno_8xx_title))
                            .setMessage(getString(R.string.vulkan_driver_freedreno_8xx_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.FREEDRENO_840) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_freedreno_840_title))
                            .setMessage(getString(R.string.vulkan_driver_freedreno_840_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_740) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_8gen2_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_8gen2_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_7XX) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_bbdd688_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_bbdd688_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.TURNIP_710) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_gmem_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_gmem_message))
                            .setPositiveButton(getString(R.string.dialog_button_ok), null)
                            .show();
                } else if (vulkanDriver == LauncherPreferences.VulkanDriver.Turnip_6XX) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.vulkan_driver_turnip_a6xx_title))
                            .setMessage(getString(R.string.vulkan_driver_turnip_a6xx_message))
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

        // The Audio API selector is gone: OpenSL ES is retired and AAudio is the only backend now.
        // See LauncherPreferences.getAudioAPI() for why.

        // Theme
        ArrayAdapter<LauncherPreferences.ThemeMode> themeAdapter = new ArrayAdapter<>(
            requireContext(),
            R.layout.spinner_item,
            LauncherPreferences.ThemeMode.values());
        themeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.settingsThemeS.setAdapter(themeAdapter);
        binding.settingsThemeS.setSelection(themeAdapter.getPosition(LauncherPreferences.requireSingleton().getThemeMode()));
        binding.settingsThemeS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.ThemeMode mode = (LauncherPreferences.ThemeMode) parent.getSelectedItem();
                LauncherPreferences.requireSingleton().setThemeMode(mode);
                AppCompatDelegate.setDefaultNightMode(mode.nightMode);
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
                String args = s.toString().trim();
                LauncherPreferences.requireSingleton().setJvmArgs(args);
                binding.settingsJargsApplyB42Btn.setEnabled(!isBuild42SetApplied(args));
            }
        });

        // Restore the recommended JVM args; the TextWatcher above persists the change.
        binding.settingsJargsResetBtn.setOnClickListener(v ->
                binding.settingsJargsEt.setText(LauncherPreferences.DEFAULT_JVM_ARGS));
        binding.settingsJargsClearBtn.setOnClickListener(v ->
                binding.settingsJargsEt.setText(""));
        // Replaces the whole field rather than appending: the Build 42 set carries its own -Xmx and
        // GC thread counts, so appending it to existing args would leave two conflicting -Xmx in
        // one line — and a log we would then have to untangle.
        binding.settingsJargsApplyB42Btn.setOnClickListener(v -> {
            String updated = LauncherPreferences.BUILD_42_JVM_ARGS;
            binding.settingsJargsEt.setText(updated);
            binding.settingsJargsEt.setSelection(updated.length());
        });
        binding.settingsJargsApplyB42Btn.setEnabled(
                !isBuild42SetApplied(binding.settingsJargsEt.getText().toString()));

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
                syncShrinkSpinner();
            }
        });

        binding.settingsEnvVarsClearBtn.setOnClickListener(v -> binding.settingsEnvVarsEt.setText(""));

        setUpTextureShrinkSpinner();

        binding.settingsMemorySaverSwitch.setChecked(LauncherPreferences.requireSingleton().isMemorySaver());
        binding.settingsMemorySaverSwitch.setOnCheckedChangeListener((v, isChecked) ->
                LauncherPreferences.requireSingleton().setMemorySaver(isChecked));

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

        binding.vibrateOnTouchSwitch.setChecked(LauncherPreferences.requireSingleton().isVibrateOnTouch());
        binding.vibrateOnTouchSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                LauncherPreferences.requireSingleton().setVibrateOnTouch(isChecked));

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

        binding.settingsTextureShrinkInfo.setOnClickListener(v -> {
            View content = getLayoutInflater().inflate(R.layout.dialog_texture_shrink, null);
            ((android.widget.TextView) content.findViewById(R.id.texture_shrink_table))
                    .setText(R.string.settings_texture_shrink_table);
            ((android.widget.TextView) content.findViewById(R.id.texture_shrink_notes))
                    .setText(R.string.settings_texture_shrink_notes);
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_texture_shrink_title))
                    .setView(content)
                    .setPositiveButton(getString(R.string.dialog_button_ok), null)
                    .show();
        });

        binding.settingsRendererTvInfo.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_renderer))
                    .setMessage(getString(R.string.settings_render_hint))
                    .setPositiveButton(getString(R.string.dialog_button_ok), null)
                    .setNeutralButton(getString(R.string.dialog_button_wiki), (dialog, which) -> {
                        Navigation.findNavController(v).navigate(R.id.wiki_fragment);
                    })
                    .show();
        });

        // Advanced section — collapsible
        setupCollapsible(
                binding.settingsAdvancedHeader,
                binding.settingsAdvancedContent,
                binding.settingsAdvancedExpandIv);
    }

    private void setupCollapsible(android.view.View header, android.view.View content,
                                   android.widget.ImageView expandIcon) {
        header.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() == android.view.View.VISIBLE;
            content.setVisibility(expanded ? android.view.View.GONE : android.view.View.VISIBLE);
            expandIcon.setImageResource(expanded
                    ? R.drawable.mt_icon_expand_more
                    : R.drawable.mt_icon_expand_less);
        });
    }

    // True when the field already holds the Build 42 set, so the button can grey itself out.
    // Whitespace-insensitive but order-sensitive: the button writes one exact string, and a user
    // who has since rearranged the flags is better served by an enabled button than by a guess.
    private static boolean isBuild42SetApplied(String args) {
        if (args == null) return false;
        return normalizeArgs(args).equals(normalizeArgs(LauncherPreferences.BUILD_42_JVM_ARGS));
    }

    private static String normalizeArgs(String args) {
        return args.trim().replaceAll("\\s+", " ");
    }

    // ---- Texture shrink presets (LIBGL_SHRINK) ----
    //
    // The two offered modes are not the ends of a scale: LIBGL_SHRINK takes 1..11 and each value is
    // a different strategy. 7 shrinks only textures above 512 and skips empty ones; 1 shrinks
    // everything including empty textures, which is where the crash reports come from. Modes in
    // between are neither milder nor harsher in order, so the spinner offers the two we trust and
    // the description points at the text field for the rest.
    private static final String SHRINK_KEY = "LIBGL_SHRINK";
    private static final String SHRINK_BALANCED = "7";
    private static final String SHRINK_ULTRA = "1";

    private boolean suppressShrinkCallback = false;

    private void setUpTextureShrinkSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.settings_texture_shrink_none),
                getString(R.string.settings_texture_shrink_balanced),
                getString(R.string.settings_texture_shrink_ultra)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.settingsTextureShrinkSpinner.setAdapter(adapter);
        syncShrinkSpinner();

        binding.settingsTextureShrinkSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressShrinkCallback) return;
                String value = position == 1 ? SHRINK_BALANCED : position == 2 ? SHRINK_ULTRA : null;
                binding.settingsEnvVarsEt.setText(withShrink(
                        binding.settingsEnvVarsEt.getText().toString(), value));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Point the spinner at whatever the field actually says, including a value typed by hand. */
    private void syncShrinkSpinner() {
        if (binding == null) return;
        String current = readShrink(binding.settingsEnvVarsEt.getText().toString());
        int position = SHRINK_BALANCED.equals(current) ? 1 : SHRINK_ULTRA.equals(current) ? 2 : 0;
        if (binding.settingsTextureShrinkSpinner.getSelectedItemPosition() == position) return;
        // A hand-typed mode we do not offer (say 4) lands on "Off" here. That is honest about what
        // the spinner can express and, crucially, does not rewrite what the player typed.
        suppressShrinkCallback = true;
        binding.settingsTextureShrinkSpinner.setSelection(position);
        binding.settingsTextureShrinkSpinner.post(() -> suppressShrinkCallback = false);
    }

    private static String readShrink(String envVars) {
        for (String token : envVars.trim().split("\\s+")) {
            if (token.startsWith(SHRINK_KEY + "=")) return token.substring(SHRINK_KEY.length() + 1);
        }
        return null;
    }

    /** Replace or drop LIBGL_SHRINK, leaving every other variable exactly where it was. */
    private static String withShrink(String envVars, String value) {
        StringBuilder out = new StringBuilder();
        for (String token : envVars.trim().split("\\s+")) {
            if (token.isEmpty() || token.startsWith(SHRINK_KEY + "=")) continue;
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        if (value != null) {
            if (out.length() > 0) out.append(' ');
            out.append(SHRINK_KEY).append('=').append(value);
        }
        return out.toString();
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
