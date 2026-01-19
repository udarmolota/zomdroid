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

import androidx.annotation.NonNull;
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
    private ActivityControlsEditorBinding binding;
    private TextWatcher controlElementTextWatcher = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityControlsEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        binding.inputControlsV.setEditMode(true);
        binding.inputControlsV.setBackgroundColor(0xFF232323);

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

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
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

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });


                applyInputType(element, element.getInputType());

                ArrayAdapter<AbstractControlElement.InputType> inputTypeAdapter =
                        new ArrayAdapter<>(ControlsEditorActivity.this, android.R.layout.simple_spinner_dropdown_item,
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
                        public void onNothingSelected(AdapterView<?> parent) {

                        }
                    });
                });
                binding.elementInputTypeS.setVisibility(View.VISIBLE);


                switch (element.getType()) {
                    case BUTTON_CIRCLE:
                    case BUTTON_RECT: {
                        binding.elementTextTv.setVisibility(View.VISIBLE);
                        binding.elementTextEt.setVisibility(View.VISIBLE);
                        binding.elementTextEt.removeTextChangedListener(controlElementTextWatcher);
                        controlElementTextWatcher = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                            }

                            @Override
                            public void onTextChanged(CharSequence s, int start, int before, int count) {
                                element.setText(s.toString());
                            }

                            @Override
                            public void afterTextChanged(Editable s) {
                            }
                        };
                        binding.elementTextEt.setText(element.getText());
                        binding.elementTextEt.addTextChangedListener(controlElementTextWatcher);

                        binding.elementIconTv.setVisibility(View.VISIBLE);
                        binding.elementIconS.setVisibility(View.VISIBLE);
/*                        BaseAdapter iconAdapter = new BaseAdapter() {
                            @Override
                            public int getCount() {
                                return elementIcons.size();
                            }

                            @Override
                            public Object getItem(int position) {
                                return elementIcons.get(position);
                            }

                            @Override
                            public long getItemId(int position) {
                                return position;
                            }

                            @Override
                            public View getView(int position, View convertView, ViewGroup parent) {
                                LayoutInflater inflater = LayoutInflater.from(GameActivity.this);
                                View view = convertView != null ? convertView :
                                        inflater.inflate(R.layout.spinner_item_with_icon, parent, false);

                                TextView textView = view.findViewById(R.id.text_tv);
                                ImageView imageView = view.findViewById(R.id.icon_iv);

                                ControlElementDescription.Icon icon = elementIcons.get(position);
                                textView.setText(icon.name());
                                imageView.setImageResource(icon.resId);


//                                if (isDropdown) {
//                                    // Customize dropdown item appearance
//                                    view.setPadding(16, 16, 16, 16);
//                                } else {
//                                    // Customize selected item appearance
//                                    view.setPadding(0, 0, 0, 0);
//                                    // Optionally show just the icon when collapsed
//                                    textView.setVisibility(View.GONE);
//                                }

                                return view;
                            }
                        };*/
                        ArrayAdapter<ControlElementDescription.Icon> adapterIcon = new ArrayAdapter<>(ControlsEditorActivity.this,
                                android.R.layout.simple_spinner_dropdown_item,
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
                                public void onNothingSelected(AdapterView<?> parent) {

                                }
                            });
                        });

                        binding.elementBindingsAddIb.setOnClickListener(v -> {
                            GLFWBinding newBinding = GLFWBinding.valuesForType(element.getInputType())[0];
                            element.addBinding(newBinding);
                            addElementBindingField(element, element.getInputType(), newBinding,
                                    element.getBindings().length - 1);
                        });
                        break;
                    }
                    case DPAD:
                    case STICK: {
                        binding.elementTextTv.setVisibility(View.GONE);
                        binding.elementTextEt.setVisibility(View.GONE);

                        binding.elementIconTv.setVisibility(View.GONE);
                        binding.elementIconS.setVisibility(View.GONE);
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

        binding.controlElementSettingsCv.setVisibility(View.INVISIBLE); // originally GONE, but we need layout before first open()


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
        switch (inputType) {
            case MNK: {
                if (element.getType() == AbstractControlElement.Type.BUTTON_CIRCLE
                        || element.getType() == AbstractControlElement.Type.BUTTON_RECT) {
                    binding.elementBindingsTv.setVisibility(View.VISIBLE);
                    binding.elementBindingsAddIb.setVisibility(View.VISIBLE);
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


                    GLFWBinding bindingLeft = element.getBindingLeft();
                    ArrayAdapter<GLFWBinding> adapterLeft = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item,
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
                            public void onNothingSelected(AdapterView<?> parent) {

                            }
                        });
                    });


                    GLFWBinding bindingUp = element.getBindingUp();
                    ArrayAdapter<GLFWBinding> adapterUp = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item,
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
                            public void onNothingSelected(AdapterView<?> parent) {

                            }
                        });
                    });


                    GLFWBinding bindingRight = element.getBindingRight();
                    ArrayAdapter<GLFWBinding> adapterRight = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item,
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
                            public void onNothingSelected(AdapterView<?> parent) {

                            }
                        });
                    });


                    GLFWBinding bindingDown = element.getBindingDown();
                    ArrayAdapter<GLFWBinding> adapterDown = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item,
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
                            public void onNothingSelected(AdapterView<?> parent) {

                            }
                        });
                    });

                    binding.elementDirectionalBindingsCl.setVisibility(View.VISIBLE);

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

                        binding.elementTogglingTv.setVisibility(View.VISIBLE);
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

                        binding.elementTogglingTv.setVisibility(View.GONE);
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
                        
                        binding.elementTogglingTv.setVisibility(View.GONE);
                        binding.elementTogglingCb.setVisibility(View.GONE);

                        binding.elementDirectionalBindingsCl.setVisibility(View.GONE);

                        binding.elementStickBindingTv.setVisibility(View.VISIBLE);

                        ArrayAdapter<GLFWBinding> adapterStick = new ArrayAdapter<>(this,
                                android.R.layout.simple_spinner_dropdown_item,
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
                                public void onNothingSelected(AdapterView<?> parent) {

                                }
                            });
                        });
                        binding.elementStickBindingS.setVisibility(View.VISIBLE);

                        break;
                    }
                }
                break;
            }
        }
    }

    private void addElementBindingField(@NonNull AbstractControlElement element, @NonNull AbstractControlElement.InputType inputType,
                                        @NonNull GLFWBinding binding, int bindingIndex) {
        ElementBindingFieldBinding fieldBinding = ElementBindingFieldBinding.inflate(getLayoutInflater());

        ArrayAdapter<GLFWBinding> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
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
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });
        });


        fieldBinding.elementBindingDeleteIb.setOnClickListener(v -> {
            this.binding.elementBindingsContainerLl.removeView(fieldBinding.getRoot());
            element.removeBinding(bindingIndex);
        });

        this.binding.elementBindingsContainerLl.addView(fieldBinding.getRoot());
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.inputControlsV.saveControlElementsToDisk();
    }

}
