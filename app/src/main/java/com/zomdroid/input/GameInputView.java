package com.zomdroid;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.InputNativeInterface;

/**
 * SurfaceView с поддержкой системной клавиатуры Android.
 * Принимает ввод через InputConnection — работает для всех IME,
 * включая китайский, японский, арабский и т.д.
 */
public class GameInputView extends SurfaceView {

    public GameInputView(Context context) {
        super(context);
        init();
    }

    public GameInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        // Говорим системе что этот View принимает текстовый ввод
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions =
                EditorInfo.IME_FLAG_NO_FULLSCREEN |
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        return new BaseInputConnection(this, false) {

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                // Сюда приходит готовый текст от IME:
                // обычные символы, иероглифы, арабские буквы и т.д.
                if (text == null) return false;
                for (int i = 0; i < text.length(); ) {
                    int cp = Character.codePointAt(text, i);
                    InputNativeInterface.sendChar(cp);
                    i += Character.charCount(cp);
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                // IME просит удалить символы (например при автокоррекции)
                for (int i = 0; i < beforeLength; i++) {
                    InputNativeInterface.sendKeyboard(GLFWBinding.KEY_BACKSPACE.code, true);
                    InputNativeInterface.sendKeyboard(GLFWBinding.KEY_BACKSPACE.code, false);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(android.view.KeyEvent event) {
                // Физические клавиши и Enter/Backspace от IME
                // Не обрабатываем здесь — это идёт через onKeyDown в GameActivity
                return super.sendKeyEvent(event);
            }
        };
    }
}