package com.zomdroid.fragments;

import android.os.Bundle;
import java.io.File;
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
import com.zomdroid.game.SuggestedPreset;
import com.zomdroid.input.GamepadManager;

public class SettingsFragment extends Fragment {
    /** Name of the instance whose settings this screen edits, passed by the card's gear button. */
    public static final String ARG_INSTANCE = "instance";

    private FragmentSettingsBinding binding;
    /**
     * The instance being edited. With no argument (or an unknown name) this reads and writes the
     * app-wide values, which keeps the screen usable if it is ever reached without a card.
     */
    private com.zomdroid.game.InstanceSettings settings;
    private String instanceName;
    // Set once the renderer spinner has delivered its initial restore callback, so the NG_GL4ES
    // warning fires only for a deliberate change by the user.
    private boolean rendererSelectionRestored = false;

    private void setUpMacosLibraries() {
        com.zomdroid.game.GameInstance instance = instanceName == null ? null
                : com.zomdroid.game.GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        boolean multiplayer = instance != null && "Build 41".equals(instance.getPresetName());
        boolean supported = instance != null && (instance.isBuild4220Plus() || multiplayer);
        binding.settingsMacosCard.setVisibility(supported ? View.VISIBLE : View.GONE);
        if (!supported) return;
        binding.settingsMacosRow.setVisibility(multiplayer ? View.GONE : View.VISIBLE);
        binding.settingsMpTitle.setVisibility(multiplayer ? View.VISIBLE : View.GONE);
        binding.settingsLibrariesHint.setText(multiplayer ? R.string.mp_libs_hint : R.string.macos_libs_short);
        binding.settingsMacosInfo.setOnClickListener(view -> showHelp(R.string.macos_libs_title, R.string.macos_libs_hint));
        binding.settingsMacosSwitch.setChecked(settings.isMacosLibrariesEnabled());
        binding.settingsMacosSwitch.setOnCheckedChangeListener((view, enabled) -> settings.setMacosLibrariesEnabled(enabled));
        binding.settingsNativeFmodSwitch.setVisibility(multiplayer ? View.GONE : View.VISIBLE);
        binding.settingsNativeFmodHint.setVisibility(multiplayer ? View.GONE : View.VISIBLE);
        binding.settingsNativeFmodSwitch.setChecked(settings.isNativeFmodEnabled());
        binding.settingsNativeFmodSwitch.setOnCheckedChangeListener((view, enabled) -> settings.setNativeFmodEnabled(enabled));
        binding.settingsMacosDownload.setOnClickListener(view -> openLibraries(view, multiplayer, false));
        binding.settingsMacosImport.setOnClickListener(view -> openLibraries(view, multiplayer, true));
        binding.settingsMacosModulesHeader.setVisibility(multiplayer ? View.GONE : View.VISIBLE);
        if (multiplayer) binding.settingsMacosModulesContent.setVisibility(View.GONE);
        setupCollapsible(binding.settingsMacosModulesHeader, binding.settingsMacosModulesContent,
                binding.settingsMacosModulesExpandIv);
        bindMacosModule(binding.settingsMacosLightingSwitch, "lighting");
        bindMacosModule(binding.settingsMacosPathfindSwitch, "pathfind");
        bindMacosModule(binding.settingsMacosPopmanSwitch, "popman");
        refreshMacosStatus();
    }

    private void bindMacosModule(androidx.appcompat.widget.SwitchCompat sw, String module) {
        sw.setChecked(settings.isMacosModuleEnabled(module));
        sw.setOnCheckedChangeListener((view, enabled) -> settings.setMacosModuleEnabled(module, enabled));
    }

    // "From Steam" / "From file" with this instance fixed. Build 41 installs its two multiplayer
    // libraries into android/arm64-v8a (the import that used to sit in the side menu), 42.20+ its
    // macOS libraries into game/macos. Shared by the card buttons and the Build 41 hosting dialog.
    private void openLibraries(View view, boolean multiplayer, boolean fromFile) {
        Bundle args = new Bundle();
        if (fromFile) {
            args.putString(multiplayer ? InstallNativeLibsFragment.ARG_MP_INSTANCE
                                       : InstallNativeLibsFragment.ARG_MACOS_INSTANCE, instanceName);
            Navigation.findNavController(view).navigate(
                    multiplayer ? R.id.install_native_libs_fragment : R.id.install_macos_libs_fragment, args);
        } else {
            args.putString(multiplayer ? SteamDownloadFragment.ARG_MP_INSTANCE
                                       : SteamDownloadFragment.ARG_MACOS_INSTANCE, instanceName);
            Navigation.findNavController(view).navigate(R.id.steam_download_fragment, args);
        }
    }

    /** Hosting on Build 41 needs its two multiplayer libraries; Build 42 ships them itself. */
    private boolean hostingLibrariesPresent() {
        com.zomdroid.game.GameInstance instance = instanceName == null ? null
                : com.zomdroid.game.GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (instance == null || !"Build 41".equals(instance.getPresetName())) return true;
        // Presence is enough: libraries a player copied in by hand work as well as ours.
        return com.zomdroid.steam.MultiplayerLibraries.hasFiles(new File(instance.getGamePath()));
    }

    private void showHostingLibrariesDialog(View anchor) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_coop_hosting)
                .setMessage(R.string.coop_b41_libraries_required)
                .setPositiveButton(R.string.macos_libs_download, (d, w) -> openLibraries(anchor, true, false))
                .setNeutralButton(R.string.macos_libs_import, (d, w) -> openLibraries(anchor, true, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshMacosStatus() {
        if (binding == null || instanceName == null) return;
        com.zomdroid.game.GameInstance instance =
                com.zomdroid.game.GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (instance == null) return;
        File game = new File(instance.getGamePath());
        if ("Build 41".equals(instance.getPresetName())) {
            boolean ready = com.zomdroid.steam.MultiplayerLibraries.isReady(game);
            binding.settingsMacosStatus.setText(ready ? R.string.mp_libs_ready
                    : com.zomdroid.steam.MultiplayerLibraries.hasFiles(game) ? R.string.mp_libs_manual : R.string.macos_libs_missing);
            return;
        }
        // Installed or not - that is all a player can act on. The build number the manifest
        // carries meant nothing to anyone (an instance keeps the build it was created with).
        binding.settingsMacosStatus.setText(com.zomdroid.steam.MacosLibraries.isReady(game)
                ? R.string.macos_libs_ready : R.string.macos_libs_missing);
    }

    @Override public void onResume() {
        super.onResume();
        refreshMacosStatus();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        instanceName = getArguments() != null ? getArguments().getString(ARG_INSTANCE) : null;
        settings = new com.zomdroid.game.InstanceSettings(instanceName);

        setUpPresetCard();
        setUpEtc2CacheRow();
        setUpMacosLibraries();

        // Renderer
        ArrayAdapter<LauncherPreferences.Renderer> rendererArrayAdapter = new ArrayAdapter<>(
            requireContext(),
            R.layout.spinner_item,
            java.util.Arrays.stream(LauncherPreferences.Renderer.values())
                    .filter(renderer -> renderer != LauncherPreferences.Renderer.MOBILEGLUES_EXPERIMENTAL)
                    .toArray(LauncherPreferences.Renderer[]::new));
        rendererArrayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.settingsRendererS.setAdapter(rendererArrayAdapter);
        int rendererPosition = rendererArrayAdapter.getPosition(settings.getRenderer());
        // An existing experimental selection is hidden too; keep the spinner on a valid item.
        if (rendererPosition < 0) {
            settings.setRenderer(LauncherPreferences.Renderer.NG_GL4ES);
            rendererPosition = rendererArrayAdapter.getPosition(settings.getRenderer());
        }
        binding.settingsRendererS.setSelection(rendererPosition);
        binding.settingsRendererS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.Renderer renderer = (LauncherPreferences.Renderer) parent.getSelectedItem();
                settings.setRenderer(renderer);
                // Only the renderers that write the ETC2 cache (NG_GL4ES, both ZINKs) show the
                // cache row, so it follows the choice rather than raising questions elsewhere.
                updateEtc2CacheRow(renderer);
                // The spinner fires this once while being restored, before the user touches
                // anything. Warn only on a real choice, or opening Settings would greet everyone
                // with a dialog about a renderer they already use.
                if (rendererSelectionRestored) {
                    if (renderer == LauncherPreferences.Renderer.NG_GL4ES) {
                        // NG_GL4ES and texture shrinking are one decision in practice - shrinking is
                        // what holds the framerate, and it applies to this renderer only. Keeping
                        // them apart is what made our own advice untrue: "switch to NG_GL4ES" on its
                        // own changes nothing, and nobody went on to find the setting. A value the
                        // user chose themselves is never overwritten.
                        boolean enabled = false;
                        if (SuggestedPreset.readShrink(binding.settingsEnvVarsEt.getText().toString()) == null) {
                            binding.settingsEnvVarsEt.setText(SuggestedPreset.withShrink(
                                    binding.settingsEnvVarsEt.getText().toString(),
                                    SuggestedPreset.SHRINK_BALANCED));
                            enabled = true;
                        }
                        if (warningIsRelevantFor(true)) {
                            new AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.renderer_ng_build42_only_title)
                                    .setMessage(R.string.renderer_ng_build42_only_message)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        } else if (enabled) {
                            Toast.makeText(requireContext(), R.string.renderer_ng_shrink_enabled,
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (renderer == LauncherPreferences.Renderer.GL4ES
                            && warningIsRelevantFor(false)) {
                        // The mirror image, and the cause of a real report: GL4ES picked by hand on
                        // 42.20 gives a black screen at startup.
                        new AlertDialog.Builder(requireContext())
                                .setTitle(R.string.renderer_gl4es_build41_title)
                                .setMessage(R.string.renderer_gl4es_build41_message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
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
                vulkanDriverAdapter.getPosition(settings.getVulkanDriver())
        );

        final boolean[] isInitialSelection = { true };

        binding.settingsVulkanDriverS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.VulkanDriver vulkanDriver =
                        (LauncherPreferences.VulkanDriver) parent.getSelectedItem();

                settings.setVulkanDriver(vulkanDriver);

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
                settings.setRenderScale((float) progress / 100);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.settingsResolutionScaleSb.setProgress((int) (settings.getRenderScale() * 100));

        // The Audio API selector is gone: OpenSL ES is retired and AAudio is the only backend now.
        // See LauncherPreferences.getAudioAPI() for why.

        // The theme is app-wide and lives in AppSettingsFragment now; this screen edits one
        // instance.

        binding.settingsJargsEt.setText(settings.getJvmArgs());

        binding.settingsJargsEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String args = s.toString().trim();
                settings.setJvmArgs(args);
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
        binding.settingsEnvVarsEt.setText(settings.getEnvVars());

        binding.settingsEnvVarsEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                settings.setEnvVars(s.toString().trim());
                syncShrinkSpinner();
            }
        });

        binding.settingsEnvVarsClearBtn.setOnClickListener(v -> binding.settingsEnvVarsEt.setText(""));

        setUpTextureShrinkSpinner();

        binding.settingsCoopHostingSwitch.setChecked(settings.isCoopHostingEnabled());
        binding.settingsCoopHostingSwitch.setOnCheckedChangeListener((v, checked) -> {
            // Build 41 ships no ARM64 RakNet/ZNet of its own: without the two multiplayer
            // libraries the server cannot start, so the switch stays off and says where to get them.
            if (checked && !hostingLibrariesPresent()) {
                v.setChecked(false);
                showHostingLibrariesDialog(v);
                return;
            }
            settings.setCoopHostingEnabled(checked);
        });

        binding.settingsMemorySaverSwitch.setChecked(settings.isMemorySaver());
        binding.settingsMemorySaverSwitch.setOnCheckedChangeListener((v, isChecked) ->
                settings.setMemorySaver(isChecked));

        // The F10 backup is opt-in behind a priced warning: the copy is world-sized (players report
        // 300-600 MB) and the game visibly freezes while it is written. Turning it ON requires
        // reading and accepting that; turning it off is one tap.
        binding.settingsBackupSwitch.setChecked(settings.isQuickSaveBackup());
        binding.settingsBackupSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            if (!isChecked) {
                settings.setQuickSaveBackup(false);
                return;
            }
            if (settings.isQuickSaveBackup()) return; // restore echo
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.backup_warning_title)
                    .setMessage(R.string.backup_warning_message)
                    .setPositiveButton(R.string.dialog_button_confirm, (d, w) ->
                            settings.setQuickSaveBackup(true))
                    .setNegativeButton(R.string.dialog_button_cancel, (d, w) ->
                            binding.settingsBackupSwitch.setChecked(false))
                    .setOnCancelListener(d -> binding.settingsBackupSwitch.setChecked(false))
                    .show();
        });

        binding.settingsDebugSwitch.setChecked(settings.isDebug());
        binding.settingsDebugSwitch.setOnCheckedChangeListener((v, isChecked) ->
                settings.setDebug(isChecked));

        binding.touchControlsSwitch.setChecked(settings.isTouchControlsEnabled());
        binding.touchControlsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setTouchControlsEnabled(isChecked);
            GamepadManager.setTouchOverride(isChecked);
            Toast.makeText(requireContext(),
                isChecked ? getString(R.string.touch_controls_enabled_toast)
                          : getString(R.string.touch_controls_disabled_toast),
                Toast.LENGTH_SHORT).show();
        });

        binding.vibrateOnTouchSwitch.setChecked(settings.isVibrateOnTouch());
        binding.vibrateOnTouchSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setVibrateOnTouch(isChecked));

        binding.settingsJargsInfo.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.jvm_args_dialog_title))
                    .setMessage(getString(R.string.jvm_args_dialog_message))
                    .setPositiveButton(getString(R.string.dialog_button_ok), null)
                    .show();
        });
        // The "?" next to a title opens the long explanation; the line under the title stays short.
        binding.settingsJargsB42Info.setOnClickListener(v ->
                showHelp(R.string.settings_jvm_args_b42_preset_title, R.string.settings_jvm_args_b42_recommendation));
        binding.settingsTextureCompressionInfo.setOnClickListener(v ->
                showHelp(R.string.settings_texture_compression, R.string.settings_texture_compression_hint));
        binding.settingsCoopHostingInfo.setOnClickListener(v ->
                showHelp(R.string.settings_coop_hosting, R.string.settings_coop_hosting_hint));

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
                        Navigation.findNavController(v).navigate(R.id.wiki_fragment, WikiFragment.section("renderers"));
                    })
                    .show();
        });

        // Advanced section — collapsible
        setupCollapsible(
                binding.settingsAdvancedHeader,
                binding.settingsAdvancedContent,
                binding.settingsAdvancedExpandIv);
    }

    private void showHelp(int titleRes, int messageRes) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(titleRes))
                .setMessage(android.text.Html.fromHtml(getString(messageRes), android.text.Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .show();
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

    // -------------------- SUGGESTED PRESETS --------------------

    private void setUpPresetCard() {
        String gpuVendor = SuggestedPreset.detectGpuVendor();
        SuggestedPreset build42 = SuggestedPreset.forBuild42(gpuVendor);

        binding.settingsPresetB42Btn.setText(build42.getLabelRes());
        binding.settingsPresetB42Btn.setOnClickListener(v -> confirmPreset(build42));
        binding.settingsPresetB41Btn.setOnClickListener(v -> confirmPreset(SuggestedPreset.BUILD_41));

        // Only where ZINK is the Build 42 default does the compatibility set need its own button;
        // everywhere else the "Build 42" button already is that set.
        if (SuggestedPreset.hasCompatibilityAlternative(gpuVendor)) {
            binding.settingsPresetB42CompatBtn.setVisibility(View.VISIBLE);
            binding.settingsPresetB42CompatBtn.setOnClickListener(
                    v -> confirmPreset(SuggestedPreset.BUILD_42_COMPATIBILITY));
        }

        updatePresetStatus();
    }

    // ---- NG_GL4ES compressed-texture cache ---------------------------------------------------

    /**
     * Clears NG_GL4ES's on-disk ETC2 cache. Shared by every instance (the entries are addressed by
     * content hash), which is why the label carries the size: the same number in every instance is
     * the plainest way to say "shared".
     *
     * <p>Sizing and deleting both walk thousands of files - Inna's device holds ~6 000 - so both
     * run off the main thread. Clearing is safe by construction: the renderer re-encodes whatever
     * it misses, at a cost of seconds on the next load, and nothing here touches saves.
     */
    private void setUpEtc2CacheRow() {
        // The cache fold: header = size, content = explanation + clear button.
        setupCollapsible(binding.settingsEtc2CacheRow, binding.settingsEtc2CacheContent,
                binding.settingsEtc2CacheExpandIv);
        binding.settingsTextureCompressionSwitch.setChecked(settings.isTextureCompression());
        binding.settingsTextureCompressionSwitch.setOnCheckedChangeListener((v, isChecked) ->
                settings.setTextureCompression(isChecked));
        binding.settingsEtc2CacheClearBtn.setOnClickListener(v -> {
            binding.settingsEtc2CacheClearBtn.setEnabled(false);
            new Thread(() -> {
                // The directory itself stays: the library expects to find it, and recreating it is
                // one more thing that can fail on a device with odd permissions.
                File cacheDir = etc2CacheDir();
                File[] entries = cacheDir.listFiles();
                if (entries != null) {
                    for (File entry : entries) {
                        if (entry.isDirectory()) com.zomdroid.FileUtils.deleteDirectory(entry);
                        else //noinspection ResultOfMethodCallIgnored
                            entry.delete();
                    }
                }
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.settingsEtc2CacheClearBtn.setEnabled(true);
                    Toast.makeText(requireContext(), R.string.settings_etc2_cache_cleared,
                            Toast.LENGTH_SHORT).show();
                    updateEtc2CacheRow(settings.getRenderer());
                });
            }, "etc2-cache-clear").start();
        });
        updateEtc2CacheRow(settings.getRenderer());
    }

    /** Shows the rows of the Texture compression card that apply to the renderer, and refreshes
     *  the cache size off the main thread. */
    private void updateEtc2CacheRow(LauncherPreferences.Renderer renderer) {
        if (binding == null) return;
        // Shrinking is a GL4ES/NG_GL4ES thing (LIBGL_SHRINK); ZINK has no such knob.
        boolean shrink = renderer == LauncherPreferences.Renderer.GL4ES
                || renderer == LauncherPreferences.Renderer.NG_GL4ES;
        binding.settingsTextureShrinkSection.setVisibility(shrink ? View.VISIBLE : View.GONE);
        // NG_GL4ES and both ZINK variants write this cache (same encoder, same store); plain
        // GL4ES has no ETC2 path, so the rows would only raise questions there.
        boolean visible = renderer == LauncherPreferences.Renderer.NG_GL4ES
                || renderer == LauncherPreferences.Renderer.ZINK_ZFA
                || renderer == LauncherPreferences.Renderer.ZINK_OSMESA;
        int visibility = visible ? View.VISIBLE : View.GONE;
        binding.settingsTextureCompressionRow.setVisibility(visibility);
        binding.settingsTextureCompressionHintTv.setVisibility(visibility);
        binding.settingsEtc2CacheRow.setVisibility(visibility);
        if (!visible) binding.settingsEtc2CacheContent.setVisibility(View.GONE);
        if (!visible) return;

        // Sizing walks thousands of files, so the label starts empty and fills in when the walk
        // finishes rather than holding up the screen.
        binding.settingsEtc2CacheLabelTv.setText(null);
        new Thread(() -> {
            long bytes = directorySize(etc2CacheDir());
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                binding.settingsEtc2CacheLabelTv.setText(getString(
                        R.string.settings_etc2_cache_label, formatSize(bytes)));
            });
        }, "etc2-cache-size").start();
    }

    private static File etc2CacheDir() {
        return new File(com.zomdroid.AppStorage.requireSingleton().getHomePath(),
                com.zomdroid.C.NGG_ETC2_CACHE_DIR);
    }

    private static long directorySize(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return 0;
        long total = 0;
        for (File entry : entries) {
            total += entry.isDirectory() ? directorySize(entry) : entry.length();
        }
        return total;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024f * 1024 * 1024));
        if (bytes >= 1024L * 1024)
            return String.format(java.util.Locale.US, "%d MB", bytes / (1024 * 1024));
        if (bytes >= 1024) return String.format(java.util.Locale.US, "%d KB", bytes / 1024);
        return bytes + " B";
    }

    /** Name the preset the current settings match, so a drifted setup is visible without digging. */
    private void updatePresetStatus() {
        if (binding == null) return;
        for (SuggestedPreset preset : SuggestedPreset.values()) {
            if (preset.describeChanges(requireContext(), settings).isEmpty()) {
                binding.settingsPresetCurrentTv.setText(
                        getString(R.string.preset_current, getString(preset.getLabelRes())));
                return;
            }
        }
        binding.settingsPresetCurrentTv.setText(R.string.preset_current_none);
    }

    /** Never a black box: list what will change, then apply only if the user agrees. */
    private void confirmPreset(SuggestedPreset preset) {
        String name = getString(preset.getLabelRes());
        java.util.List<String> changes = preset.describeChanges(requireContext(), settings);
        if (changes.isEmpty()) {
            Toast.makeText(requireContext(), R.string.preset_confirm_nothing_to_do,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder message = new StringBuilder();
        for (String change : changes) message.append("• ").append(change).append('\n');
        if (preset == SuggestedPreset.BUILD_42_COMPATIBILITY)
            message.append(getString(R.string.preset_confirm_compat_note));

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.preset_confirm_title, name))
                .setMessage(message.toString().trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    preset.apply(requireContext(), settings);
                    // The screen reads its values once, at creation; after writing behind its back
                    // the controls have to be pointed at the new state or they would show - and on
                    // the next edit write back - the old one.
                    refreshFromPreferences();
                    Toast.makeText(requireContext(), getString(R.string.preset_applied, name),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshFromPreferences() {
        if (binding == null) return;
        com.zomdroid.game.InstanceSettings prefs = settings;
        binding.settingsRendererS.setSelection(
                ((ArrayAdapter<LauncherPreferences.Renderer>) binding.settingsRendererS.getAdapter())
                        .getPosition(prefs.getRenderer()));
        binding.settingsJargsEt.setText(prefs.getJvmArgs());
        binding.settingsEnvVarsEt.setText(prefs.getEnvVars());
        binding.settingsMemorySaverSwitch.setChecked(prefs.isMemorySaver());
        binding.settingsTextureCompressionSwitch.setChecked(prefs.isTextureCompression());
        binding.settingsResolutionScaleSb.setProgress(Math.round(prefs.getRenderScale() * 100));
        syncShrinkSpinner();
        updatePresetStatus();
    }

    /**
     * Whether a "this renderer is for the other build" warning applies to this player at all.
     *
     * <p>Renderer settings are global while the warning is about a build, so the honest answer only
     * exists when there is no ambiguity: exactly one instance installed. With one Build 42 instance,
     * telling someone who just picked NG_GL4ES that it does not work on Build 41 is noise about a
     * build they do not have. With several, or none, we cannot know which they will launch and the
     * warning is worth showing.
     */
    private boolean warningIsRelevantFor(boolean rendererIsForBuild42) {
        java.util.ArrayList<com.zomdroid.game.GameInstance> instances =
                com.zomdroid.game.GameInstanceManager.requireSingleton().getInstances();
        if (instances.size() != 1) return true;
        String build = instances.get(0).getBuildVersion();
        if (build == null) return true;
        boolean instanceIsBuild42 = build.startsWith("42");
        return instanceIsBuild42 != rendererIsForBuild42;
    }

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
                // Fires for a real tap AND for setSelection() from syncShrinkSpinner(). The two are
                // told apart without flags or timing: a selection that already matches what the
                // field says is the spinner catching up to a hand edit and must not write back -
                // writing back on it is what used to delete a hand-typed mode mid-keystroke (the
                // old suppress flag was reset via post(), which raced the spinner's own posted
                // callback, so the "Off" echo fired as if the user chose it and stripped the
                // variable). Only a selection that DIFFERS from the field is a user decision.
                String envVars = binding.settingsEnvVarsEt.getText().toString();
                if (position == spinnerPositionFor(envVars)) return;
                String value = position == 1 ? SHRINK_BALANCED : position == 2 ? SHRINK_ULTRA : null;
                binding.settingsEnvVarsEt.setText(withShrink(envVars, value));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Which spinner row the field's LIBGL_SHRINK value corresponds to. A hand-typed mode we do not
     * offer (say 3) lands on "Off": the spinner cannot express it, and it must survive untouched.
     */
    private int spinnerPositionFor(String envVars) {
        String value = readShrink(envVars);
        return SHRINK_BALANCED.equals(value) ? 1 : SHRINK_ULTRA.equals(value) ? 2 : 0;
    }

    /** Point the spinner at whatever the field actually says, including a value typed by hand. */
    private void syncShrinkSpinner() {
        if (binding == null) return;
        int position = spinnerPositionFor(binding.settingsEnvVarsEt.getText().toString());
        if (binding.settingsTextureShrinkSpinner.getSelectedItemPosition() != position)
            binding.settingsTextureShrinkSpinner.setSelection(position);
    }

    // Reading and rewriting LIBGL_SHRINK inside the free-text field lives in SuggestedPreset, which
    // needs the same two operations to apply a preset.
    private static String readShrink(String envVars) {
        return SuggestedPreset.readShrink(envVars);
    }

    private static String withShrink(String envVars, String value) {
        return SuggestedPreset.withShrink(envVars, value);
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
