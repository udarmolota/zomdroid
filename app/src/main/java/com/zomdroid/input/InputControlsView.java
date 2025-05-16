package com.zomdroid.input;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zomdroid.C;
import com.zomdroid.R;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;

public class InputControlsView extends View {
    private static final String LOG_TAG = InputControlsView.class.getName();
    private ArrayList<AbstractControlElement> controlElements = new ArrayList<>();
    boolean isEditMode = false;
    AbstractControlElement selectedElement;
    AbstractControlElement pointerOverElement;
    public float pixelScale = 1.f;
    GestureDetector gestureDetector;
    private Gson gson = new Gson();
    private SharedPreferences sharedPreferences;

    private ElementSettingsController elementSettingsController;


    public InputControlsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.sharedPreferences = context.getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);

        this.gestureDetector = new GestureDetector(context, new GestureDetector.OnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {

                return true;
            }

            @Override
            public void onShowPress(@NonNull MotionEvent e) {

            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (pointerOverElement == null) return false;
                selectElement(pointerOverElement);
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (pointerOverElement == null) return false;
                pointerOverElement.moveCenterPosition(-distanceX, -distanceY);
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                showAddElementDialog();
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                return false;
            }
        });
    }

    private void showAddElementDialog() {
        ArrayAdapter<AbstractControlElement.Type> adapter = new ArrayAdapter<>(this.getContext(),
                android.R.layout.simple_spinner_dropdown_item, AbstractControlElement.Type.values());

        new MaterialAlertDialogBuilder(this.getContext())
                .setTitle(this.getContext().getString(R.string.controls_editor_add_element))
                .setAdapter(adapter, (dialog, i) -> {
                    AbstractControlElement.Type type = adapter.getItem(i);
                    if (type == null) return;
                    ControlElementDescription description = ControlElementDescription.getDefaultForType(type);
                    controlElements.add(AbstractControlElement.fromDescription(this, description));
                    invalidate();
                })
                .create()
                .show();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        this.pixelScale = (float) w / 2560;

        if (this.controlElements.isEmpty()) {
            loadControlElementsFromDisk();
        } else {
            // recreate all elements
            for (int i = 0; i < this.controlElements.size(); i++) {
                AbstractControlElement controlElement = this.controlElements.get(i);
                ControlElementDescription description = controlElement.describe();
                controlElement = AbstractControlElement.fromDescription(this, description);
                this.controlElements.set(i, controlElement);
            }
        }

    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        for (AbstractControlElement controlElement: controlElements) {
            controlElement.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isEditMode) {
            /* this is here and not in gesture detector because longpressEnabled should be set before
            detector processes motion event */
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (selectedElement != null) deselectElement();
                pointerOverElement = null;
                gestureDetector.setIsLongpressEnabled(true);

                float x = e.getX();
                float y = e.getY();
                for (AbstractControlElement element : controlElements) {
                    if (element.isPointOver(x, y)) {
                        pointerOverElement = element;
                        gestureDetector.setIsLongpressEnabled(false);
                        break;
                    }
                }
            }
            return gestureDetector.onTouchEvent(e);
        } else {
            for (AbstractControlElement controlElement: controlElements) {
                if (controlElement.handleMotionEvent(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void selectElement(@NonNull AbstractControlElement element) {
        this.selectedElement = element;
        this.selectedElement.setHighlighted(true);

        if (this.elementSettingsController != null) {
            this.elementSettingsController.fromLeft = this.selectedElement.getCenterX() > (float) this.getWidth() / 2;
            this.elementSettingsController.open();
        }
    }

    private void deselectElement() {
        if (this.selectedElement == null) return;
        if (this.elementSettingsController != null) this.elementSettingsController.close();
        this.selectedElement.setHighlighted(false);
        this.selectedElement = null;
    }

    private void moveSelectedElement(float x, float y) {
        selectedElement.setCenterPosition(x, y);
        if (this.elementSettingsController != null) {
            boolean isLeft = x > (float) this.getWidth() / 2;
            if (isLeft != this.elementSettingsController.fromLeft) {
                this.elementSettingsController.fromLeft = isLeft;
                this.elementSettingsController.open();
            }
        }
    }

    public AbstractControlElement getSelectedElement() {
        return this.selectedElement;
    }

    public void deleteSelectedElement() {
        if (this.selectedElement == null) return;
        if (this.elementSettingsController != null) this.elementSettingsController.hide();
        this.controlElements.remove(this.selectedElement);
        this.selectedElement = null;
        invalidate();
    }

    public void setEditMode(boolean value) {
        this.isEditMode = value;
    }

    public void loadControlElementsFromDisk() {
        String json = this.sharedPreferences.getString(C.shprefs.keys.INPUT_CONTROLS, null);
        if (json == null) {
            try (InputStream is = getContext().getAssets().open(C.assets.DEFAULT_CONTROLS)) {
                byte[] bytes = new byte[is.available()];
                is.read(bytes);
                json = new String(bytes, Charset.defaultCharset());
            } catch (IOException e) {
                Log.d(LOG_TAG, e.toString());
                return;
            }
        }
        Type type = new TypeToken<ArrayList<ControlElementDescription>>(){}.getType();
        ArrayList<ControlElementDescription> savedDescriptions = gson.fromJson(json, type);
        if (savedDescriptions != null) {
            for (ControlElementDescription description: savedDescriptions) {
                this.controlElements.add(AbstractControlElement.fromDescription(this, description));
            }
        }
    }

    public void saveControlElementsToDisk() {
        ArrayList<ControlElementDescription> descriptions = new ArrayList<>();
        for (AbstractControlElement element: controlElements) {
            descriptions.add(element.describe());
        }
        if (descriptions.isEmpty()) return;
        String json = this.gson.toJson(descriptions);

        this.sharedPreferences
                .edit()
                .putString(C.shprefs.keys.INPUT_CONTROLS, json)
                .apply();
    }

    public void setElementSettingsController(ElementSettingsController elementSettingsController) {
        this.elementSettingsController = elementSettingsController;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public abstract static class ElementSettingsController {
        protected boolean fromLeft;
        protected abstract void open();
        protected abstract void close();
        protected abstract void hide();
    }
}
