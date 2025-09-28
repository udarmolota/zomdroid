package com.zomdroid.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent; // <-- added for triggers better support
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
 * Now includes LT/RT triggers mapping (axes or button fallback).
 */
public class GamepadMapperFragment extends Fragment {
    /* ========================= NEW: trigger support metadata ========================= */

    // --- Step model ---
    // We add 2 extra steps for LT and RT mapping (was 10, now 12).
    private static final int STEP_COUNT = 12; // <-- changed from 11

     // Indexes of steps in UI order (keep previous order, then add LT/RT at the end)
    private static final int STEP_A       = 0;
    private static final int STEP_B       = 1;
    private static final int STEP_X       = 2;
    private static final int STEP_Y       = 3;
    private static final int STEP_LB      = 4;
    private static final int STEP_RB      = 5;
    private static final int STEP_SELECT  = 6;
    private static final int STEP_START   = 7;
    private static final int STEP_L3      = 8;
    private static final int STEP_R3      = 9;
    private static final int STEP_LT      = 10; // <-- new
    private static final int STEP_RT      = 11; // <-- new

    // --- Target/storage indices in GamepadManager mapping array ---
    // IMPORTANT: GamepadManager must understand these two indices as LT/RT binding slots.
    // If current array from getCurrentMapping() is shorter, we will expand it before saving.
    private static final int STORE_IDX_LT = 15; // <-- reserve slot for LT binding
    private static final int STORE_IDX_RT = 16; // <-- reserve slot for RT binding

    // --- Sentinel encoding for non-key bindings we store into the int[] mapping ---
    // We need to store "axis" vs "button" triggers distinctly inside an int[] that historically carried keyCodes.
    // Encode as: TYPE << 24 | VALUE
    private static final int TYPE_AXIS   = 0x01;  // VALUE = axisIndex (e.g., 4 for LT, 5 for RT)
    private static final int TYPE_BUTTON = 0x02;  // VALUE = KeyEvent keyCode (e.g., KEYCODE_BUTTON_L2)

    private static final int ENCODE_SHIFT = 24;
    private static int encodeAxis(int axisIndex) { return (TYPE_AXIS << ENCODE_SHIFT) | (axisIndex & 0x00FFFFFF); }
    private static int encodeButton(int keyCode)  { return (TYPE_BUTTON << ENCODE_SHIFT) | (keyCode   & 0x00FFFFFF); }

    /** Axis indices we target in native/GLFW (a4/a5) */
    // NOTE: In GLFW standard mapping, LT=a4, RT=a5.
    private static final int AXIS_LT_INDEX = 4;
    private static final int AXIS_RT_INDEX = 5;

    /** Android keycodes for button-style triggers (fallback hardware) */
    private static final int KC_BUTTON_L2 = KeyEvent.KEYCODE_BUTTON_L2; // usually 104
    private static final int KC_BUTTON_R2 = KeyEvent.KEYCODE_BUTTON_R2; // usually 105

    /** Threshold to accept analog movement as "selected" in mapping UI */
    private static final float AXIS_THRESHOLD = 0.5f;

    /* ========================= end of NEW metadata ========================= */

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
                getString(R.string.gamepad_mapper_button_rstk),
                // --- NEW: labels for LT/RT steps (add these strings to resources) ---
                getString(R.string.gamepad_mapper_button_lt), // e.g. "LT (Left Trigger)"
                getString(R.string.gamepad_mapper_button_rt)  // e.g. "RT (Right Trigger)"
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

        /* ========================= NEW: listen for analog (axis) motion =========================
           We must capture LT/RT when they arrive as analog axes (LTRIGGER/RT or Z/RZ).
        */
        view.setOnGenericMotionListener((v, motionEvent) -> {
            if (!mappingActive) return false;
            if (motionEvent.getAction() != MotionEvent.ACTION_MOVE) return false;

            // Only care when current step expects LT or RT
            if (currentStep == STEP_LT || currentStep == STEP_RT) {
                float axisVal = readTriggerAxis(motionEvent, currentStep == STEP_LT);
                if (axisVal > AXIS_THRESHOLD) {
                    // Save as "axis a4/a5" sentinel so GamepadManager can later normalize to a4/a5.
                    int code = encodeAxis(currentStep == STEP_LT ? AXIS_LT_INDEX : AXIS_RT_INDEX);
                    if (isDuplicate(code)) {
                        Toast.makeText(getContext(), "Button already assigned!", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    assignCurrentStep(code);
                    return true;
                }
            }
            return false;
        });
        /* ========================= end NEW analog listener ========================= */

        return view;
    }

     private void resetMapping() {
        int[] defaultMap = GamepadManager.getCurrentMapping();
        // --- CHANGED: expand logicalOrder to include LT/RT storage indexes (STORE_IDX_LT/RT) ---
        // IMPORTANT: This array maps UI steps -> indices in the GamepadManager mapping storage.
        int[] logicalOrder = {0,1,2,3,4,5,6,7,9,10, STORE_IDX_LT, STORE_IDX_RT};

        // Ensure our local mapping array is clean and seeded with defaults (or placeholder for LT/RT).
        for (int i = 0; i < STEP_COUNT; i++) {
            int idx = logicalOrder[i];
            // If default map is too short for our new indices, seed with 0.
            mapping[i] = (idx < defaultMap.length) ? defaultMap[idx] : 0;
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
        // --- NEW: If current step expects a trigger (LT/RT) and user presses button L2/R2,
        //           capture it as a BUTTON sentinel (fallback hardware that sends keycodes).
        if (currentStep == STEP_LT && keyCode == KC_BUTTON_L2) {
            int code = encodeButton(KC_BUTTON_L2);
            if (isDuplicate(code)) { 
                Toast.makeText(getContext(), "Button already assigned!", Toast.LENGTH_SHORT).show();
                return true; 
            }
            assignCurrentStep(code);
            return true;
        }
        if (currentStep == STEP_RT && keyCode == KC_BUTTON_R2) {
            int code = encodeButton(KC_BUTTON_R2);
            if (isDuplicate(code)) { 
                Toast.makeText(getContext(), "Button already assigned!", Toast.LENGTH_SHORT).show();
                return true; 
            }
            assignCurrentStep(code);
            return true;
        }
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

    /* ========================= NEW helpers  ========================= */
    private void assignCurrentStep(int code) {
        mapping[currentStep] = code;
        mapped[currentStep] = true;
        currentStep++;
        if (currentStep >= STEP_COUNT) {
            saveFullMapping();
            if (isAdded() && startButton != null) startButton.setVisibility(View.VISIBLE);
            mappingActive = false;
        }
        if (isAdded() && stepLabel != null) updateStepLabel();
    }

    private boolean isDuplicate(int code) {
        for (int i = 0; i < currentStep; i++) {
            if (mapping[i] == code) return true;
        }
        return false;
    }

    /**
     * Read trigger axis for current step, with fallbacks:
     * - Preferred: AXIS_LTRIGGER / AXIS_RTRIGGER
     * - Fallbacks: AXIS_Z / AXIS_RZ, AXIS_BRAKE / AXIS_GAS
     * Returns normalized float [0..1] (clamped).
     */
    private float readTriggerAxis(MotionEvent ev, boolean leftTrigger) {
        float v = Float.NaN;

        // Preferred trigger axes available on many modern pads
        v = ev.getAxisValue(leftTrigger ? MotionEvent.AXIS_LTRIGGER : MotionEvent.AXIS_RTRIGGER);

        // Fallback to Z/RZ only if value is NaN (axis not present)
        if (Float.isNaN(v)) {
            v = ev.getAxisValue(leftTrigger ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RZ);
        }

        // Fallback to "car" style axes only if still NaN
        if (Float.isNaN(v)) {
            v = ev.getAxisValue(leftTrigger ? MotionEvent.AXIS_BRAKE : MotionEvent.AXIS_GAS);
        }

        if (Float.isNaN(v)) v = 0f;

        // Some devices report in [-1..1]; normalize to [0..1]
        if (v < 0f) v = -v;           // assume symmetric, split later in manager if needed
        if (v > 1f) v = 1f;
        return v;
    }

    /* ========================= end NEW helpers ========================= */

    private void saveFullMapping() {
        Context ctx = getContext();
        if (ctx == null) return;

        int[] base = GamepadManager.getCurrentMapping().clone();

        // --- CHANGED: include LT/RT slots at the end of logical order ---
        int[] logicalOrder = {0,1,2,3,4,5,6,7,9,10, STORE_IDX_LT, STORE_IDX_RT};

        // If base is too short to store LT/RT, expand it (so we don't crash).
        int maxIndex = STORE_IDX_RT;
        if (base.length <= maxIndex) {
            // NOTE: We keep other values, fill new tail with zeros.
            int[] expanded = new int[maxIndex + 1];
            System.arraycopy(base, 0, expanded, 0, base.length);
            base = expanded;
        }
        
        for (int i = 0; i < STEP_COUNT; i++) {
            base[logicalOrder[i]] = mapping[i];
        }

        // NOTE: GamepadManager must be updated to interpret sentinel-encoded values at STORE_IDX_LT/RT:
        // - (TYPE_AXIS << 24) | axisIndex  => normalize to a4/a5
        // - (TYPE_BUTTON << 24) | keyCode  => synthesize axis 1.0/0.0 on key down/up
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
