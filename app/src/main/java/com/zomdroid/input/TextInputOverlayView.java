package com.zomdroid.input;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.function.Consumer;

public class TextInputOverlayView extends LinearLayout {
  private static final String TAG = "TextInputOverlay";
  private Consumer<String> onTextInput;

  public TextInputOverlayView(Context context) {
    super(context);
    setOrientation(HORIZONTAL);
    setBackgroundColor(Color.argb(128, 0, 0, 0)); // полупрозрачный фон

    addKeyButton("A");
    addKeyButton("B");
    addKeyButton("C");
    addKeyButton("Space", " ");
    addKeyButton("Enter", "\n");
  }

  private void addKeyButton(String label) {
    addKeyButton(label, label);
  }

  private void addKeyButton(String label, String value) {
    Button button = new Button(getContext());
    button.setText(label);
    System.out.println("[TextInputOverlay] addKeyButton");

    button.setOnClickListener(v -> {
      if (onTextInput != null) {
        onTextInput.accept(value);
      }

      // вместо sendChar гоняем клавиши через sendKeyboard
      for (int offset = 0; offset < value.length(); ) {
        int codePoint = value.codePointAt(offset);
        int keyCode = mapCharToKey(codePoint);

        Log.d(TAG, "sendChar: char='" + (char) codePoint +
                "' code=" + codePoint + " -> key=" + keyCode);

        if (keyCode != 0) {
          // это как будто нажали клавишу на клавиатуре
          InputNativeInterface.sendKeyboard(keyCode, true);
          InputNativeInterface.sendKeyboard(keyCode, false);
        } else {
          Log.d(TAG, "sendChar: unsupported char, skipping");
        }

        offset += Character.charCount(codePoint);
      }

      System.out.println("[TextInputOverlay] Sent: " + value);
    });

    addView(button);
  }

  private int mapCharToKey(int codePoint) {
    switch (codePoint) {
      case 'A':
      case 'a':
        return GLFWBinding.KEY_A.code;
      case 'B':
      case 'b':
        return GLFWBinding.KEY_B.code;
      case 'C':
      case 'c':
        return GLFWBinding.KEY_C.code;
      case ' ':
        return GLFWBinding.KEY_SPACE.code;
      case '\n':
      case '\r':
        return GLFWBinding.KEY_ENTER.code;
      default:
        return 0;
    }
  }

  public void setOnTextInputListener(Consumer<String> listener) {
    this.onTextInput = listener;
  }

  public void attachTo(View targetView) {
    targetView.requestFocus();
  }
}
