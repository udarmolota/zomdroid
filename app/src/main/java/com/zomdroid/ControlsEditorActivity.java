package com.zomdroid;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.input.AbstractControlElement;
import com.zomdroid.input.ControlElementDescription;
import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.databinding.ActivityControlsEditorBinding;
import com.zomdroid.databinding.ElementBindingFieldBinding;

public class ControlsEditorActivity extends AppCompatActivity {
    public static final String EXTRA_BACKGROUND_PATH = "com.zomdroid.ControlsEditorActivity.EXTRA_BACKGROUND_PATH";
    public static final String EXTRA_INSTANCE_NAME = "com.zomdroid.ControlsEditorActivity.EXTRA_INSTANCE_NAME";
    private ActivityControlsEditorBinding binding;
    private TextWatcher controlElementTextWatcher = null;
    private ActivityResultLauncher<String> pickIconLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityControlsEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // ПЕРВЫМ ДЕЛОМ — до любых других вызовов на inputControlsV
        String instanceName = getIntent().getStringExtra(EXTRA_INSTANCE_NAME);
        if (instanceName != null) {
            binding.inputControlsV.setInstanceName(instanceName);
        }

        pickIconLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) onIconPicked(uri);
        });

        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        binding.inputControlsV.setEditMode(true);
        binding.inputControlsV.setBackgroundColor(0x00000000);

        String bgPath = getIntent().getStringExtra(EXTRA_BACKGROUND_PATH);
        if (bgPath != null) {
            Bitmap bg = BitmapFactory.decodeFile(bgPath);
            if (bg != null) {
                binding.controlsEditorBgIv.setImageBitmap(bg);
                binding.controlsEditorBgIv.setVisibility(android.view.View.VISIBLE);
            }
        }

        binding.layoutsBtn.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.controls_editor_layout_dialog_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.controls_editor_load_vkbd),
                            getString(R.string.controls_editor_reset_gamepad)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            binding.inputControlsV.replaceControlsFromAsset(
                                    "default_controls_vkbd.json",
                                    true
                            );
                            Toast.makeText(this,
                                    R.string.controls_editor_vkbd_loaded,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            binding.inputControlsV.replaceControlsFromAsset(
                                    C.assets.DEFAULT_CONTROLS,
                                    true
                            );
                            Toast.makeText(this,
                                    R.string.controls_editor_layout_restored,
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        binding.inputControlsV.setElementSettingsController(new InputControlsView.ElementSettingsController() {
            private void loadElement(AbstractControlElement element) {

                int scaleProgressValue = Math.round(element.getScale() * 100);
                binding.elementScalePercentTv.setText(getResources().getString(R.string.percentage_format,
                        scaleProgressValue));
                binding.elementScaleSb.setOnSeekBarChangeListener(null);
                binding.elementScaleSb.setProgress(scaleProgressValue);
                binding.elementScaleSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        binding.elementScalePercentTv.setText(getResources().getString(R.string.percentage_format, progress));
                        element.setScale((float) progress / 100);
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });

                int opacityProgressValue = Math.round((float) element.getAlpha() / 255 * 100);
                binding.elementOpacityPercentTv.setText(getResources().getString(R.string.percentage_format,
                        opacityProgressValue));
                binding.elementOpacitySb.setOnSeekBarChangeListener(null);
                binding.elementOpacitySb.setProgress(opacityProgressValue);
                binding.elementOpacitySb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        binding.elementOpacityPercentTv.setText(getResources().getString(R.string.percentage_format, progress));
                        element.setAlpha(Math.round((float) progress / 100 * 255));
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });

                binding.elementOpacityAllBtn.setOnClickListener(v -> {
                    int alpha = Math.round((float) binding.elementOpacitySb.getProgress() / 100 * 255);
                    for (AbstractControlElement e : binding.inputControlsV.getControlElements()) {
                        e.setAlpha(alpha);
                    }
                    binding.inputControlsV.invalidate();
                });

                // Sensitivity — only for TOUCHPAD, STICK_MOUSE and SCROLL_BAR
                boolean hasSensitivity = (element.getType() == AbstractControlElement.Type.TOUCHPAD
                        || element.getType() == AbstractControlElement.Type.STICK_MOUSE
                        || element.getType() == AbstractControlElement.Type.SCROLL_BAR
                        || element.getType() == AbstractControlElement.Type.RADIAL_MENU);

                if (hasSensitivity) {
                    final float currentSens;
                    if (element instanceof com.zomdroid.input.TouchpadControlElement) {
                        currentSens = ((com.zomdroid.input.TouchpadControlElement) element).getSensitivity();
                    } else if (element instanceof com.zomdroid.input.MouseStickControlElement) {
                        currentSens = ((com.zomdroid.input.MouseStickControlElement) element).getSensitivity();
                    } else if (element instanceof com.zomdroid.input.ScrollBarControlElement) {
                        currentSens = ((com.zomdroid.input.ScrollBarControlElement) element).getSensitivity();
                    } else if (element instanceof com.zomdroid.input.RadialMenuControlElement) {
                        currentSens = ((com.zomdroid.input.RadialMenuControlElement) element).getSensitivity();
                    } else {
                        currentSens = ControlElementDescription.DEFAULT_SENSITIVITY;
                    }

                    int sensProgress = Math.round(currentSens * 100);
                    binding.elementSensitivityTv.setVisibility(View.VISIBLE);
                    binding.elementSensitivityPercentTv.setVisibility(View.VISIBLE);
                    binding.elementSensitivityPercentTv.setText(
                            getResources().getString(R.string.percentage_format, sensProgress));
                    binding.elementSensitivitySb.setVisibility(View.VISIBLE);
                    binding.elementSensitivitySb.setOnSeekBarChangeListener(null);
                    binding.elementSensitivitySb.setProgress(sensProgress);
                    binding.elementSensitivitySb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            binding.elementSensitivityPercentTv.setText(
                                    getResources().getString(R.string.percentage_format, progress));
                            float s = progress / 100f;
                            if (element instanceof com.zomdroid.input.TouchpadControlElement) {
                                ((com.zomdroid.input.TouchpadControlElement) element).setSensitivity(s);
                            } else if (element instanceof com.zomdroid.input.MouseStickControlElement) {
                                ((com.zomdroid.input.MouseStickControlElement) element).setSensitivity(s);
                            } else if (element instanceof com.zomdroid.input.ScrollBarControlElement) {
                                ((com.zomdroid.input.ScrollBarControlElement) element).setSensitivity(s);
                            } else if (element instanceof com.zomdroid.input.RadialMenuControlElement) {
                                ((com.zomdroid.input.RadialMenuControlElement) element).setSensitivity(s);
                            }
                        }
                        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                    });
                } else {
                    binding.elementSensitivityTv.setVisibility(View.GONE);
                    binding.elementSensitivityPercentTv.setVisibility(View.GONE);
                    binding.elementSensitivitySb.setVisibility(View.GONE);
                }

                // apply UI for current type
                applyInputType(element, element.getInputType());

                // STICK_WASD / STICK_MOUSE: fixed MNK, не даём менять inputType
                boolean fixedMnkStick = (element.getType() == AbstractControlElement.Type.STICK_WASD
                        || element.getType() == AbstractControlElement.Type.STICK_MOUSE);

                if (fixedMnkStick) {
                    element.setInputType(AbstractControlElement.InputType.MNK);
                    applyInputType(element, AbstractControlElement.InputType.MNK);
                    binding.elementInputTypeS.setVisibility(View.GONE);
                } else {
                    ArrayAdapter<AbstractControlElement.InputType> inputTypeAdapter =
                            new ArrayAdapter<>(ControlsEditorActivity.this, R.layout.spinner_item,
                                    AbstractControlElement.InputType.values());

                    binding.elementInputTypeS.setVisibility(View.GONE);
                    binding.elementInputTypeS.setAdapter(inputTypeAdapter);
                    binding.elementInputTypeS.setOnItemSelectedListener(null);
                    binding.elementInputTypeS.setSelection(inputTypeAdapter.getPosition(element.getInputType()));
                    binding.elementInputTypeS.post(() -> {
                        binding.elementInputTypeS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            @Override
                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                AbstractControlElement.InputType inputType = (AbstractControlElement.InputType) parent.getSelectedItem();
                                element.setInputType(inputType);
                                applyInputType(element, inputType);
                            }
                            @Override
                            public void onNothingSelected(AdapterView<?> parent) { }
                        });
                    });
                    binding.elementInputTypeS.setVisibility(View.VISIBLE);
                }

                switch (element.getType()) {
                    case BUTTON_CIRCLE:
                    case BUTTON_RECT: {
                        binding.elementToggleTextRowLl.setVisibility(View.VISIBLE);
                        binding.elementTextTv.setVisibility(View.VISIBLE);
                        binding.elementTogglingTv.setVisibility(View.VISIBLE);
                        binding.elementToggleTextFieldsLl.setVisibility(View.VISIBLE);
                        binding.elementTogglingCb.setVisibility(View.VISIBLE);
                        binding.elementTextEt.setVisibility(View.VISIBLE);
                        binding.elementTextEt.removeTextChangedListener(controlElementTextWatcher);
                        controlElementTextWatcher = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            @Override
                            public void onTextChanged(CharSequence s, int start, int before, int count) {
                                element.setText(s.toString());
                            }
                            @Override
                            public void afterTextChanged(Editable s) {}
                        };
                        binding.elementTextEt.setText(element.getText());
                        binding.elementTextEt.addTextChangedListener(controlElementTextWatcher);

                        // The built-in Back/Start icon picker only makes sense for buttons bound
                        // to those gamepad buttons; hide it otherwise (use Custom icon instead).
                        boolean showBuiltinIcon = hasStartSelectBinding(element);
                        binding.elementIconTv.setVisibility(showBuiltinIcon ? View.VISIBLE : View.GONE);
                        binding.elementIconS.setVisibility(showBuiltinIcon ? View.VISIBLE : View.GONE);

                        ArrayAdapter<ControlElementDescription.Icon> adapterIcon = new ArrayAdapter<>(ControlsEditorActivity.this,
                                R.layout.spinner_item,
                                ControlElementDescription.Icon.values());
                        binding.elementIconS.setAdapter(adapterIcon);
                        binding.elementIconS.setOnItemSelectedListener(null);
                        binding.elementIconS.setSelection(adapterIcon.getPosition(element.getIcon()));
                        binding.elementIconS.post(() -> {
                            binding.elementIconS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                @Override
                                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                    element.setIcon((ControlElementDescription.Icon) parent.getSelectedItem());
                                }
                                @Override
                                public void onNothingSelected(AdapterView<?> parent) {}
                            });
                        });

                        // Style — only for buttons
                        binding.elementStyleTv.setVisibility(View.VISIBLE);
                        binding.elementStyleS.setVisibility(View.VISIBLE);
                        ArrayAdapter<ControlElementDescription.Style> adapterStyle = new ArrayAdapter<>(ControlsEditorActivity.this,
                                R.layout.spinner_item,
                                ControlElementDescription.Style.values());
                        binding.elementStyleS.setAdapter(adapterStyle);
                        binding.elementStyleS.setOnItemSelectedListener(null);
                        if (element instanceof com.zomdroid.input.ButtonControlElement) {
                            binding.elementStyleS.setSelection(adapterStyle.getPosition(
                                    ((com.zomdroid.input.ButtonControlElement) element).getStyle()));
                        }
                        binding.elementStyleS.post(() -> {
                            binding.elementStyleS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                @Override
                                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                    if (element instanceof com.zomdroid.input.ButtonControlElement) {
                                        ((com.zomdroid.input.ButtonControlElement) element).setStyle(
                                                (ControlElementDescription.Style) parent.getSelectedItem());
                                        binding.inputControlsV.invalidate();
                                    }
                                }
                                @Override
                                public void onNothingSelected(AdapterView<?> parent) {}
                            });
                        });

                        // Custom image icon (load your own picture)
                        binding.elementCustomIconB.setVisibility(View.VISIBLE);
                        binding.elementIconNoTintCb.setVisibility(View.VISIBLE);
                        binding.elementIconNoTintCb.setOnCheckedChangeListener(null);
                        if (element instanceof com.zomdroid.input.ButtonControlElement) {
                            binding.elementIconNoTintCb.setChecked(
                                    ((com.zomdroid.input.ButtonControlElement) element).isNoTint());
                        }
                        binding.elementCustomIconB.setOnClickListener(v -> pickIconLauncher.launch("image/*"));
                        binding.elementIconNoTintCb.setOnCheckedChangeListener((b, isChecked) -> {
                            if (element instanceof com.zomdroid.input.ButtonControlElement) {
                                ((com.zomdroid.input.ButtonControlElement) element).setNoTint(isChecked);
                            }
                        });

                        binding.elementBindingsAddIb.setOnClickListener(v -> {
                            GLFWBinding newBinding = GLFWBinding.valuesForType(element.getInputType())[0];
                            element.addBinding(newBinding);
                            addElementBindingField(element, element.getInputType(), newBinding,
                                    element.getBindings().length - 1);
                        });
                        break;
                    }
                    case RADIAL_MENU: {
                        // Center label (re-uses the button text field, but without the toggle
                        // checkbox); 4 sector bindings are shown via the directional UI in applyInputType().
                        binding.elementToggleTextRowLl.setVisibility(View.VISIBLE);
                        binding.elementTextTv.setVisibility(View.VISIBLE);
                        binding.elementTogglingTv.setVisibility(View.GONE);
                        binding.elementToggleTextFieldsLl.setVisibility(View.VISIBLE);
                        binding.elementTogglingCb.setVisibility(View.GONE);
                        binding.elementIconTv.setVisibility(View.GONE);
                        binding.elementIconS.setVisibility(View.GONE);
                        binding.elementStyleTv.setVisibility(View.GONE);
                        binding.elementStyleS.setVisibility(View.GONE);

                        // Custom center image (same as round buttons).
                        binding.elementCustomIconB.setVisibility(View.VISIBLE);
                        binding.elementIconNoTintCb.setVisibility(View.VISIBLE);
                        binding.elementIconNoTintCb.setOnCheckedChangeListener(null);
                        if (element instanceof com.zomdroid.input.RadialMenuControlElement) {
                            binding.elementIconNoTintCb.setChecked(
                                    ((com.zomdroid.input.RadialMenuControlElement) element).isNoTint());
                        }
                        binding.elementCustomIconB.setOnClickListener(v -> pickIconLauncher.launch("image/*"));
                        binding.elementIconNoTintCb.setOnCheckedChangeListener((b, isChecked) -> {
                            if (element instanceof com.zomdroid.input.RadialMenuControlElement) {
                                ((com.zomdroid.input.RadialMenuControlElement) element).setNoTint(isChecked);
                            }
                        });

                        binding.elementTextEt.setVisibility(View.VISIBLE);
                        binding.elementTextEt.removeTextChangedListener(controlElementTextWatcher);
                        controlElementTextWatcher = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            @Override
                            public void onTextChanged(CharSequence s, int start, int before, int count) {
                                element.setText(s.toString());
                            }
                            @Override
                            public void afterTextChanged(Editable s) {}
                        };
                        binding.elementTextEt.setText(element.getText());
                        binding.elementTextEt.addTextChangedListener(controlElementTextWatcher);
                        break;
                    }
                    case DPAD:
                    case STICK:
                    case STICK_WASD:
                    case STICK_MOUSE:
                    case TOUCHPAD: {
                        binding.elementToggleTextRowLl.setVisibility(View.GONE);
                        binding.elementToggleTextFieldsLl.setVisibility(View.GONE);
                        binding.elementTogglingCb.setVisibility(View.GONE);
                        binding.elementTextEt.setVisibility(View.GONE);
                        binding.elementIconTv.setVisibility(View.GONE);
                        binding.elementIconS.setVisibility(View.GONE);
                        binding.elementStyleTv.setVisibility(View.GONE);
                        binding.elementStyleS.setVisibility(View.GONE);
                        binding.elementCustomIconB.setVisibility(View.GONE);
                        binding.elementIconNoTintCb.setVisibility(View.GONE);
                        break;
                    }
                    case DPAD_UP:
                    case DPAD_RIGHT:
                    case DPAD_DOWN:
                    case DPAD_LEFT:
                    default: {
                        binding.elementToggleTextRowLl.setVisibility(View.GONE);
                        binding.elementToggleTextFieldsLl.setVisibility(View.GONE);
                        binding.elementTogglingCb.setVisibility(View.GONE);
                        binding.elementTextEt.setVisibility(View.GONE);
                        binding.elementIconTv.setVisibility(View.GONE);
                        binding.elementIconS.setVisibility(View.GONE);
                        binding.elementStyleTv.setVisibility(View.GONE);
                        binding.elementStyleS.setVisibility(View.GONE);
                        binding.elementCustomIconB.setVisibility(View.GONE);
                        binding.elementIconNoTintCb.setVisibility(View.GONE);
                        break;
                    }
                }
            }

            @Override
            public void open() {
                AbstractControlElement element = binding.inputControlsV.getSelectedElement();
                if (element == null) return;

                loadElement(element);

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.controlElementSettingsCv.getLayoutParams();
                params.gravity = (this.fromLeft ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
                params.width = (int) (binding.getRoot().getWidth() * 0.4f);
                binding.controlElementSettingsCv.setLayoutParams(params);

                float startX = this.fromLeft ? -binding.controlElementSettingsCv.getWidth() : binding.controlElementSettingsCv.getWidth();
                binding.controlElementSettingsCv.setTranslationX(startX);

                binding.controlElementSettingsCv.animate()
                        .withStartAction(() -> binding.controlElementSettingsCv.setVisibility(View.VISIBLE))
                        .translationX(0)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }

            @Override
            public void close() {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.controlElementSettingsCv.getLayoutParams();
                boolean exitLeft = (params.gravity & Gravity.START) == Gravity.START;
                float endX = exitLeft ? -binding.controlElementSettingsCv.getWidth() : binding.controlElementSettingsCv.getWidth();

                binding.controlElementSettingsCv.animate()
                        .withEndAction(() -> binding.controlElementSettingsCv.setVisibility(View.INVISIBLE))
                        .translationX(endX)
                        .setDuration(300)
                        .setInterpolator(new AccelerateInterpolator())
                        .start();
            }

            @Override
            protected void hide() {
                binding.controlElementSettingsCv.setVisibility(View.INVISIBLE);
            }
        });

        binding.controlElementSettingsCv.setVisibility(View.INVISIBLE);

        binding.elementTextEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus();
            }
            return false;
        });

        binding.elementDeleteB.setOnClickListener(v -> binding.inputControlsV.deleteSelectedElement());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_info)
                .setMessage(R.string.controls_editor_instructions)
                .setCancelable(true)
                .setPositiveButton(R.string.dialog_button_ok, null)
                .create()
                .show();
    }

    private void applyInputType(@NonNull AbstractControlElement element,
                                @NonNull AbstractControlElement.InputType inputType) {
        // RADIAL_MENU has 4 programmable sector bindings (Left/Up/Right/Down) for both
        // MNK and GAMEPAD — re-use the directional-bindings UI.
        if (element.getType() == AbstractControlElement.Type.RADIAL_MENU) {
            applyRadialMenuBindings(element, inputType);
            return;
        }
        switch (inputType) {
            case MNK: {
                if (element.getType() == AbstractControlElement.Type.BUTTON_CIRCLE
                        || element.getType() == AbstractControlElement.Type.BUTTON_RECT) {
                    binding.elementBindingsTv.setVisibility(View.VISIBLE);
                    binding.elementBindingsAddIb.setVisibility(View.VISIBLE);
                    binding.elementTogglingCb.setVisibility(View.VISIBLE);

                    binding.elementTogglingCb.setOnCheckedChangeListener(null);
                    binding.elementTogglingCb.setChecked(element.getToggle());
                    binding.elementTogglingCb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        element.setToggle(isChecked);
                    });

                    binding.elementBindingsContainerLl.removeAllViews();
                    GLFWBinding[] bindings = element.getBindings();
                    for (int i = 0; i < bindings.length; i++) {
                        addElementBindingField(element, element.getInputType(), bindings[i], i);
                    }

                    binding.elementDirectionalBindingsCl.setVisibility(View.GONE);
                    binding.elementStickBindingTv.setVisibility(View.GONE);
                    binding.elementStickBindingS.setVisibility(View.GONE);

                } else if (element.getType() == AbstractControlElement.Type.DPAD
                        || element.getType() == AbstractControlElement.Type.STICK) {
                    binding.elementBindingsTv.setVisibility(View.GONE);
                    binding.elementBindingsAddIb.setVisibility(View.GONE);
                    binding.elementBindingsContainerLl.removeAllViews();
                    binding.elementTogglingCb.setVisibility(View.GONE);

                    GLFWBinding bindingLeft = element.getBindingLeft();
                    ArrayAdapter<GLFWBinding> adapterLeft = new ArrayAdapter<>(this,
                            R.layout.spinner_item,
                            GLFWBinding.valuesForType(AbstractControlElement.InputType.MNK));
                    binding.elementBindingLeftS.setAdapter(adapterLeft);
                    binding.elementBindingLeftS.setOnItemSelectedListener(null);
                    binding.elementBindingLeftS.setSelection(adapterLeft.getPosition(bindingLeft));
                    binding.elementBindingLeftS.post(() -> {
                        binding.elementBindingLeftS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            @Override
                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                element.setBindingLeft((GLFWBinding) parent.getSelectedItem());
                            }
                            @Override
                            public void onNothingSelected(AdapterView<?> parent) {}
                        });
                    });

                    GLFWBinding bindingUp = element.getBindingUp();
                    ArrayAdapter<GLFWBinding> adapterUp = new ArrayAdapter<>(this,
                            R.layout.spinner_item,
                            GLFWBinding.valuesForType(AbstractControlElement.InputType.MNK));
                    binding.elementBindingUpS.setAdapter(adapterUp);
                    binding.elementBindingUpS.setOnItemSelectedListener(null);
                    binding.elementBindingUpS.setSelection(adapterUp.getPosition(bindingUp), false);
                    binding.elementBindingUpS.post(() -> {
                        binding.elementBindingUpS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            @Override
                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                element.setBindingUp((GLFWBinding) parent.getSelectedItem());
                            }
                            @Override
                            public void onNothingSelected(AdapterView<?> parent) {}
                        });
                    });

                    GLFWBinding bindingRight = element.getBindingRight();
                    ArrayAdapter<GLFWBinding> adapterRight = new ArrayAdapter<>(this,
                            R.layout.spinner_item,
                            GLFWBinding.valuesForType(AbstractControlElement.InputType.MNK));
                    binding.elementBindingRightS.setAdapter(adapterRight);
                    binding.elementBindingRightS.setOnItemSelectedListener(null);
                    binding.elementBindingRightS.setSelection(adapterRight.getPosition(bindingRight));
                    binding.elementBindingRightS.post(() -> {
                        binding.elementBindingRightS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            @Override
                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                element.setBindingRight((GLFWBinding) parent.getSelectedItem());
                            }
                            @Override
                            public void onNothingSelected(AdapterView<?> parent) {}
                        });
                    });

                    GLFWBinding bindingDown = element.getBindingDown();
                    ArrayAdapter<GLFWBinding> adapterDown = new ArrayAdapter<>(this,
                            R.layout.spinner_item,
                            GLFWBinding.valuesForType(AbstractControlElement.InputType.MNK));
                    binding.elementBindingDownS.setAdapter(adapterDown);
                    binding.elementBindingDownS.setOnItemSelectedListener(null);
                    binding.elementBindingDownS.setSelection(adapterDown.getPosition(bindingDown));
                    binding.elementBindingDownS.post(() -> {
                        binding.elementBindingDownS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            @Override
                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                element.setBindingDown((GLFWBinding) parent.getSelectedItem());
                            }
                            @Override
                            public void onNothingSelected(AdapterView<?> parent) {}
                        });
                    });

                    binding.elementDirectionalBindingsCl.setVisibility(View.VISIBLE);
                    binding.elementStickBindingTv.setVisibility(View.GONE);
                    binding.elementStickBindingS.setVisibility(View.GONE);

                } else if (element.getType() == AbstractControlElement.Type.STICK_WASD
                        || element.getType() == AbstractControlElement.Type.STICK_MOUSE) {
                    binding.elementBindingsTv.setVisibility(View.GONE);
                    binding.elementBindingsAddIb.setVisibility(View.GONE);
                    binding.elementBindingsContainerLl.removeAllViews();
                    binding.elementTogglingCb.setVisibility(View.GONE);
                    binding.elementDirectionalBindingsCl.setVisibility(View.GONE);
                    binding.elementStickBindingTv.setVisibility(View.GONE);
                    binding.elementStickBindingS.setVisibility(View.GONE);
                }
                break;
            }
            case GAMEPAD: {
                switch (element.getType()) {
                    case BUTTON_CIRCLE:
                    case BUTTON_RECT: {
                        binding.elementBindingsTv.setVisibility(View.VISIBLE);
                        binding.elementBindingsAddIb.setVisibility(View.VISIBLE);
                        binding.elementTogglingCb.setVisibility(View.VISIBLE);

                        binding.elementTogglingCb.setOnCheckedChangeListener(null);
                        binding.elementTogglingCb.setChecked(element.getToggle());
                        binding.elementTogglingCb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                            element.setToggle(isChecked);
                        });

                        binding.elementBindingsContainerLl.removeAllViews();
                        GLFWBinding[] bindings = element.getBindings();
                        for (int i = 0; i < bindings.length; i++) {
                            addElementBindingField(element, element.getInputType(), bindings[i], i);
                        }

                        binding.elementDirectionalBindingsCl.setVisibility(View.GONE);
                        binding.elementStickBindingTv.setVisibility(View.GONE);
                        binding.elementStickBindingS.setVisibility(View.GONE);
                        break;
                    }
                    case DPAD: {
                        binding.elementBindingsTv.setVisibility(View.GONE);
                        binding.elementBindingsAddIb.setVisibility(View.GONE);
                        binding.elementBindingsContainerLl.removeAllViews();
                        binding.elementTogglingCb.setVisibility(View.GONE);
                        binding.elementDirectionalBindingsCl.setVisibility(View.GONE);
                        binding.elementStickBindingTv.setVisibility(View.GONE);
                        binding.elementStickBindingS.setVisibility(View.GONE);
                        break;
                    }
                    case STICK: {
                        binding.elementBindingsTv.setVisibility(View.GONE);
                        binding.elementBindingsAddIb.setVisibility(View.GONE);
                        binding.elementBindingsContainerLl.removeAllViews();
                        binding.elementTogglingCb.setVisibility(View.GONE);
                        binding.elementDirectionalBindingsCl.setVisibility(View.GONE);

                        binding.elementStickBindingTv.setVisibility(View.VISIBLE);

                        ArrayAdapter<GLFWBinding> adapterStick = new ArrayAdapter<>(this,
                                R.layout.spinner_item,
                                new GLFWBinding[]{GLFWBinding.LEFT_JOYSTICK, GLFWBinding.RIGHT_JOYSTICK});
                        binding.elementStickBindingS.setAdapter(adapterStick);
                        binding.elementStickBindingS.setOnItemSelectedListener(null);
                        binding.elementStickBindingS.setSelection(adapterStick.getPosition(element.getBindingStick()));
                        binding.elementStickBindingS.post(() -> {
                            binding.elementStickBindingS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                @Override
                                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                    element.setBindingStick((GLFWBinding) parent.getSelectedItem());
                                }
                                @Override
                                public void onNothingSelected(AdapterView<?> parent) {}
                            });
                        });
                        binding.elementStickBindingS.setVisibility(View.VISIBLE);
                        break;
                    }
                    case DPAD_UP:
                    case DPAD_RIGHT:
                    case DPAD_DOWN:
                    case DPAD_LEFT:
                    default: {
                        binding.elementBindingsTv.setVisibility(View.GONE);
                        binding.elementBindingsAddIb.setVisibility(View.GONE);
                        binding.elementBindingsContainerLl.removeAllViews();
                        binding.elementTogglingCb.setVisibility(View.GONE);
                        binding.elementDirectionalBindingsCl.setVisibility(View.GONE);
                        binding.elementStickBindingTv.setVisibility(View.GONE);
                        binding.elementStickBindingS.setVisibility(View.GONE);
                        break;
                    }
                }
                break;
            }
        }
    }

    private void applyRadialMenuBindings(@NonNull AbstractControlElement element,
                                         @NonNull AbstractControlElement.InputType inputType) {
        binding.elementBindingsTv.setVisibility(View.GONE);
        binding.elementBindingsAddIb.setVisibility(View.GONE);
        binding.elementBindingsContainerLl.removeAllViews();
        binding.elementTogglingCb.setVisibility(View.GONE);
        binding.elementStickBindingTv.setVisibility(View.GONE);
        binding.elementStickBindingS.setVisibility(View.GONE);

        final com.zomdroid.input.RadialMenuControlElement radial =
                (element instanceof com.zomdroid.input.RadialMenuControlElement)
                        ? (com.zomdroid.input.RadialMenuControlElement) element : null;

        GLFWBinding[] options = GLFWBinding.valuesForType(inputType);

        // sector 0 = Left, 1 = Up, 2 = Right, 3 = Down
        setupRadialSectorSpinner(binding.elementBindingLeftS, options, element.getBindingLeft(),
                b -> { element.setBindingLeft(b); if (radial != null) radial.setSectorLabel(0, shortBindingLabel(b)); });
        setupRadialSectorSpinner(binding.elementBindingUpS, options, element.getBindingUp(),
                b -> { element.setBindingUp(b); if (radial != null) radial.setSectorLabel(1, shortBindingLabel(b)); });
        setupRadialSectorSpinner(binding.elementBindingRightS, options, element.getBindingRight(),
                b -> { element.setBindingRight(b); if (radial != null) radial.setSectorLabel(2, shortBindingLabel(b)); });
        setupRadialSectorSpinner(binding.elementBindingDownS, options, element.getBindingDown(),
                b -> { element.setBindingDown(b); if (radial != null) radial.setSectorLabel(3, shortBindingLabel(b)); });

        binding.elementDirectionalBindingsCl.setVisibility(View.VISIBLE);
    }

    private void setupRadialSectorSpinner(android.widget.Spinner spinner, GLFWBinding[] options,
                                          GLFWBinding current,
                                          java.util.function.Consumer<GLFWBinding> onPick) {
        ArrayAdapter<GLFWBinding> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, options);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(null);
        int pos = adapter.getPosition(current);
        spinner.setSelection(pos >= 0 ? pos : 0, false);
        spinner.post(() -> spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPick.accept((GLFWBinding) parent.getSelectedItem());
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        }));
    }

    /** Short human label from a binding name, e.g. KEY_A→A, GAMEPAD_BUTTON_X→X, MOUSE_WHEEL_UP→UP. */
    private static String shortBindingLabel(GLFWBinding b) {
        if (b == null) return "";
        String n = b.name();
        int us = n.lastIndexOf('_');
        return (us >= 0 && us < n.length() - 1) ? n.substring(us + 1) : n;
    }

    private void addElementBindingField(@NonNull AbstractControlElement element,
                                        @NonNull AbstractControlElement.InputType inputType,
                                        @NonNull GLFWBinding binding, int bindingIndex) {
        ElementBindingFieldBinding fieldBinding = ElementBindingFieldBinding.inflate(getLayoutInflater());

        ArrayAdapter<GLFWBinding> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item,
                GLFWBinding.valuesForType(inputType));
        fieldBinding.elementBindingS.setAdapter(adapter);
        fieldBinding.elementBindingS.setSelection(adapter.getPosition(binding));
        fieldBinding.elementBindingS.post(() -> {
            fieldBinding.elementBindingS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    element.setBinding(bindingIndex, (GLFWBinding) parent.getSelectedItem());
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        });

        fieldBinding.elementBindingDeleteIb.setOnClickListener(v -> {
            this.binding.elementBindingsContainerLl.removeView(fieldBinding.getRoot());
            element.removeBinding(bindingIndex);
        });

        this.binding.elementBindingsContainerLl.addView(fieldBinding.getRoot());
    }

    private boolean hasStartSelectBinding(@NonNull AbstractControlElement el) {
        for (GLFWBinding b : el.getBindings()) {
            if (b == GLFWBinding.GAMEPAD_BUTTON_BACK
                    || b == GLFWBinding.GAMEPAD_BUTTON_START
                    || b == GLFWBinding.GAMEPAD_BUTTON_GUIDE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Crops fully/near-transparent borders off an imported icon so its visible content maps
     * directly to the on-screen box. Returns the original bitmap if it has no trimmable border
     * (fully opaque to the edges) or is entirely transparent.
     */
    private static Bitmap trimTransparentBorder(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        final int ALPHA_MIN = 8; // treat alpha <= 8 as transparent
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int a = (px[row + x] >>> 24) & 0xff;
                if (a > ALPHA_MIN) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;               // fully transparent — leave as-is
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src; // nothing to trim
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private void onIconPicked(Uri uri) {
        AbstractControlElement el = binding.inputControlsV.getSelectedElement();
        boolean isButton = el instanceof com.zomdroid.input.ButtonControlElement;
        boolean isRadial = el instanceof com.zomdroid.input.RadialMenuControlElement;
        if (!isButton && !isRadial) {
            Toast.makeText(this, R.string.control_element_custom_icon_select_button, Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = binding.inputControlsV.getControlsIconsDir();
        if (dir == null) {
            Toast.makeText(this, R.string.control_element_custom_icon_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!dir.exists()) dir.mkdirs();
        try {
            // Decode bounds first to downscale large images (cap ~256px).
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int sample = 1;
            final int max = 256;
            while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) {
                Toast.makeText(this, R.string.control_element_custom_icon_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            // Trim transparent borders so the visible content fills the button regardless of how
            // much padding the source PNG had (otherwise a padded image looks tiny, an edge-to-edge
            // one looks oversized for the same on-screen box).
            bmp = trimTransparentBorder(bmp);
            String fileName = "icon_" + System.currentTimeMillis() + ".png";
            File out = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            boolean noTint = binding.elementIconNoTintCb.isChecked();
            if (isButton) {
                ((com.zomdroid.input.ButtonControlElement) el).setCustomIcon(fileName, noTint);
            } else {
                ((com.zomdroid.input.RadialMenuControlElement) el).setCustomIcon(fileName, noTint);
            }
            binding.inputControlsV.invalidate();
        } catch (Exception e) {
            Toast.makeText(this, R.string.control_element_custom_icon_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.inputControlsV.saveControlElementsToDisk();
    }
}
