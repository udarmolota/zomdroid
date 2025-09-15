package com.zomdroid.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.zomdroid.R;
import com.zomdroid.input.GamepadManager;

/**
 * Fragment for step-by-step mapping of 10 gamepad buttons (NOT including GUIDE).
 * User maps each button in order. Mapping is saved at the end or reset to default.
 */
public class GamepadMapperFragment extends Fragment {
    private static final int STEP_COUNT = 10;

    // 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 SELECT, 7 START, 8 GUIDE, 9 L3, 10 R3
    private static final int GUIDE_INDEX = 8;
    
    private int currentStep = 0;
    private int[] mapping = new int[STEP_COUNT];
    private boolean[] mapped = new boolean[STEP_COUNT];
    private TextView stepLabel;
    private Button resetButton;
    private Button startButton;
    private String[] buttonLabels;
    private boolean mappingActive = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_gamepad_mapper, container, false);
        stepLabel = view.findViewById(R.id.gamepad_mapper_step_label);
        resetButton = view.findViewById(R.id.gamepad_mapper_reset_button);
        startButton = view.findViewById(R.id.gamepad_mapper_start_button);

        buttonLabels = new String[] {
                getString(R.string.gamepad_mapper_button_a),
                getString(R.string.gamepad_mapper_button_b),
                getString(R.string.gamepad_mapper_button_x),
                getString(R.string.gamepad_mapper_button_y),
                getString(R.string.gamepad_mapper_button_lb),
                getString(R.string.gamepad_mapper_button_rb),
                getString(R.string.gamepad_mapper_button_select),
                getString(R.string.gamepad_mapper_button_start),
                getString(R.string.gamepad_mapper_button_lstk),
                getString(R.string.gamepad_mapper_button_rstk)
        };

        resetButton.setOnClickListener(v -> {
            mappingActive = false;
            resetMapping();
            Context ctx = getContext();
            if (ctx != null) {
                Toast.makeText(ctx, R.string.gamepad_mapper_reset_confirmation, Toast.LENGTH_SHORT).show();
            }
        });
        startButton.setOnClickListener(v -> {
            mappingActive = true;
            startButton.setVisibility(View.GONE);
            resetMapping();
            // Focus fragment view
            view.setFocusableInTouchMode(true);
            view.requestFocus();
        });


        // Mapping starts inactive
        mappingActive = false;
        stepLabel.setText("");

        // Enable key events
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.setOnKeyListener((v, keyCode, event) -> {
            if (!mappingActive) return false;
            return handleKeyEvent(keyCode, event);
        });

        return view;
    }

     private void resetMapping() {
        int[] defaultMap = GamepadManager.getCurrentMapping();
        int[] logicalOrder = {0,1,2,3,4,5,6,7,9,10};

        for (int i = 0; i < STEP_COUNT; i++) {
            mapping[i] = defaultMap[logicalOrder[i]];
            mapped[i] = false;
        }
        currentStep = 0;
        if (mappingActive) {
            updateStepLabel();
        } else {
            stepLabel.setText("");
            startButton.setVisibility(View.VISIBLE);
        }
    }

    private void updateStepLabel() {
        if (currentStep < STEP_COUNT) {
            String label = getString(R.string.gamepad_mapper_step, buttonLabels[currentStep]);
            stepLabel.setText(label);
        } else {
            stepLabel.setText(R.string.gamepad_mapper_save);
        }
    }

    private boolean handleKeyEvent(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (currentStep >= STEP_COUNT || !isAdded()) return false;
        // Prevent duplicate button
        for (int i = 0; i < currentStep; i++) {
            if (mapping[i] == keyCode) {
                Toast.makeText(getContext(), "Button already assigned!", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        mapping[currentStep] = keyCode;
        mapped[currentStep] = true;
        currentStep++;
        if (currentStep >= STEP_COUNT) {
            saveFullMapping();
            if (isAdded() && startButton != null) startButton.setVisibility(View.VISIBLE);
            mappingActive = false;
        }
        if (isAdded() && stepLabel != null) updateStepLabel();
        return true;
    }

    private void saveFullMapping() {
        Context ctx = getContext();
        if (ctx == null) return;

        int[] base = GamepadManager.getCurrentMapping().clone();

        int[] logicalOrder = {0,1,2,3,4,5,6,7,9,10};
        for (int i = 0; i < STEP_COUNT; i++) {
            base[logicalOrder[i]] = mapping[i];
        }

        GamepadManager.setCustomMapping(base, ctx);
        Toast.makeText(ctx, "Success", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Ensure key events
        View v = getView();
        if (v != null) {
            v.setFocusableInTouchMode(true);
            v.requestFocus();
        }
    }
}
