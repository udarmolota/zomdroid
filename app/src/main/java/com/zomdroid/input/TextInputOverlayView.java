package com.zomdroid.input;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.function.Consumer;

public class TextInputOverlayView extends LinearLayout {
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
          System.out.println("[TextInputOverlay] Sent: " + value);
        } else {
          System.out.println("[TextInputOverlay] Nothing sent");
        }
      });
      addView(button);
  }

  /**
   * Устанавливает обработчик ввода текста — например, для передачи в чат.
   */
  public void setOnTextInputListener(Consumer<String> listener) {
    this.onTextInput = listener;
  }

  /**
   * Привязка к целевому View, если нужен системный inputConnection.
   * Можно использовать вместе с BaseInputConnection, если нужно.
   */
    public void attachTo(View targetView) {
        targetView.requestFocus();
    }
}
