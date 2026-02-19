package com.zomdroid.input;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

public class TextInputOverlayView extends LinearLayout {

    // ======= Раскладка =======
    private static final String[][] ROWS = {
            { "1","2","3","4","5","6","7","8","9","0","-","/",":",".","@" },
            { "q","w","e","r","t","y","u","i","o","p","a","s","d","f","g","h" },
            { "j","k","l","z","x",",","_"," ","⌫","c","v","b","n","m","↵" }
    };

    public TextInputOverlayView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Color.argb(200, 20, 20, 20));
        setPadding(dp(6), dp(6), dp(6), dp(6));

        for (String[] row : ROWS) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, dp(2), 0, dp(2));
            rowLayout.setLayoutParams(rowParams);

            for (String key : row) {
                rowLayout.addView(makeKey(context, key));
            }
            addView(rowLayout);
        }
    }

    private Button makeKey(Context context, String label) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.MONOSPACE);
        btn.setAllCaps(false);
        btn.setBackgroundColor(Color.argb(220, 60, 60, 60));

        int keyW = label.equals(" ") ? dp(90) : dp(34);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(keyW, dp(36));
        p.setMargins(dp(2), 0, dp(2), 0);
        btn.setLayoutParams(p);
        btn.setPadding(0, 0, 0, 0);

        btn.setOnClickListener(v -> sendKey(label));
        return btn;
    }

    private void sendKey(String label) {
        switch (label) {
            case "⌫":
                InputNativeInterface.sendKeyboard(GLFWBinding.KEY_BACKSPACE.code, true);
                InputNativeInterface.sendKeyboard(GLFWBinding.KEY_BACKSPACE.code, false);
                return;
            case "↵":
                InputNativeInterface.sendKeyboard(GLFWBinding.KEY_ENTER.code, true);
                InputNativeInterface.sendKeyboard(GLFWBinding.KEY_ENTER.code, false);
                return;
            case " ":
                InputNativeInterface.sendKeyboard(GLFWBinding.KEY_SPACE.code, true);
                InputNativeInterface.sendChar(' ');
                InputNativeInterface.sendKeyboard(GLFWBinding.KEY_SPACE.code, false);
                return;
        }

        // Обычный символ
        char ch = label.charAt(0);
        GLFWBinding binding = charToBinding(ch);
        if (binding != null) {
            InputNativeInterface.sendKeyboard(binding.code, true);
            InputNativeInterface.sendChar(ch);
            InputNativeInterface.sendKeyboard(binding.code, false);
        }
    }

    private GLFWBinding charToBinding(char ch) {
        switch (ch) {
            case 'a': return GLFWBinding.KEY_A;
            case 'b': return GLFWBinding.KEY_B;
            case 'c': return GLFWBinding.KEY_C;
            case 'd': return GLFWBinding.KEY_D;
            case 'e': return GLFWBinding.KEY_E;
            case 'f': return GLFWBinding.KEY_F;
            case 'g': return GLFWBinding.KEY_G;
            case 'h': return GLFWBinding.KEY_H;
            case 'i': return GLFWBinding.KEY_I;
            case 'j': return GLFWBinding.KEY_J;
            case 'k': return GLFWBinding.KEY_K;
            case 'l': return GLFWBinding.KEY_L;
            case 'm': return GLFWBinding.KEY_M;
            case 'n': return GLFWBinding.KEY_N;
            case 'o': return GLFWBinding.KEY_O;
            case 'p': return GLFWBinding.KEY_P;
            case 'q': return GLFWBinding.KEY_Q;
            case 'r': return GLFWBinding.KEY_R;
            case 's': return GLFWBinding.KEY_S;
            case 't': return GLFWBinding.KEY_T;
            case 'u': return GLFWBinding.KEY_U;
            case 'v': return GLFWBinding.KEY_V;
            case 'w': return GLFWBinding.KEY_W;
            case 'x': return GLFWBinding.KEY_X;
            case 'y': return GLFWBinding.KEY_Y;
            case 'z': return GLFWBinding.KEY_Z;
            case '0': return GLFWBinding.KEY_0;
            case '1': return GLFWBinding.KEY_1;
            case '2': return GLFWBinding.KEY_2;
            case '3': return GLFWBinding.KEY_3;
            case '4': return GLFWBinding.KEY_4;
            case '5': return GLFWBinding.KEY_5;
            case '6': return GLFWBinding.KEY_6;
            case '7': return GLFWBinding.KEY_7;
            case '8': return GLFWBinding.KEY_8;
            case '9': return GLFWBinding.KEY_9;
            case '.': return GLFWBinding.KEY_PERIOD;
            case ',': return GLFWBinding.KEY_COMMA;
            case '-': return GLFWBinding.KEY_MINUS;
            case '/': return GLFWBinding.KEY_SLASH;
            // : @ _ — нет прямого GLFW биндинга, шлём только char
            default:  return null;
        }
    }

    // ======= Показать/скрыть =======
    public static TextInputOverlayView instance = null;

    public static void toggle(Context context, ViewGroup root) {
        if (instance != null) {
            root.removeView(instance);
            instance = null;
            return;
        }
        instance = new TextInputOverlayView(context);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = 20;
        instance.setLayoutParams(params);

        root.addView(instance);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}