package com.zomdroid.input;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RectShape;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;


import java.util.Arrays;

public class ButtonControlElement extends AbstractControlElement {
    private final ButtonControlDrawable drawable;
    private int pointerId = -1;
    private boolean isToggledOn = false;

    public ButtonControlElement(InputControlsView parentView, ControlElementDescription elementDescription) {
        super(parentView, elementDescription);
        this.drawable = new ButtonControlDrawable(parentView, elementDescription);
        this.bindings.addAll(Arrays.asList(elementDescription.bindings));
    }

    @Override
    public void setInputType(InputType inputType) {
        if (inputType == null || inputType == this.inputType) return;
        clearBindings();
        this.inputType = inputType;
    }

    private void dispatchEvent(boolean isPressed) {
        for (GLFWBinding binding : bindings) {
            if (binding == GLFWBinding.UI_TOGGLE_OVERLAY) {
                if (isPressed) {
                    parentView.toggleOverlayVisibility();
                }
                return; // важно: не отправлять дальше ни MNK, ни GAMEPAD
            }
            if (binding == GLFWBinding.UI_TOGGLE_KEYBOARD) {
                if (isPressed) {
                    TextInputOverlayView.toggle(
                            parentView.getContext(),
                            (ViewGroup) parentView.getParent()
                    );
                }
                return;
            }
        }

        switch (this.inputType) {
            case MNK:
                for (GLFWBinding binding : bindings) {
                    handleMNKBinding(binding, isPressed);
                }
                break;
            case GAMEPAD:
                for (GLFWBinding binding : bindings) {
                    handleGamepadBinding(binding, isPressed);
                }
                break;
        }
    }


    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        int pointerId = e.getPointerId(actionIndex);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(actionIndex);
                float y = e.getY(actionIndex);
                if (!this.drawable.isPointOver(x, y)) return false;
                this.pointerId = pointerId;

                if (getToggle()) {
                    if (isToggledOn) {
                        this.dispatchEvent(false);
                        isToggledOn = false;
                    } else {
                        this.dispatchEvent(true);
                        isToggledOn = true;
                    }
                } else {
                    this.dispatchEvent(true);
                }

                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerId != this.pointerId) return false;
                this.pointerId = -1;
                if (!getToggle()) {
                    this.dispatchEvent(false);
                }
                return true;
            case MotionEvent.ACTION_CANCEL: {
                if (this.pointerId != -1) {
                    this.pointerId = -1;
                    this.dispatchEvent(false);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public float getCenterX() {
        return this.drawable.centerX;
    }

    @Override
    public void draw(Canvas canvas) {
        this.drawable.draw(canvas);
    }

    @Override
    public boolean isPointOver(float x, float y) {
        return this.drawable.isPointOver(x, y);
    }

    @Override
    public void setHighlighted(boolean highlighted) {
        if (highlighted) {
            this.drawable.setColorFilter(this.HIGHLIGHT_COLOR_FILTER);
        } else {
            this.drawable.setColorFilter(null);
        }
        this.parentView.invalidate();
    }

    @Override
    public void setScale(float scale) {
        scale = Math.clamp(scale, MIN_SCALE, MAX_SCALE);
        this.drawable.setScale(scale);
        this.parentView.invalidate();
    }

    @Override
    public float getScale() {
        return this.drawable.scale;
    }

    @Override
    public void setAlpha(int alpha) {
        this.drawable.setAlpha(alpha);
        this.parentView.invalidate();
    }

    @Override
    public int getAlpha() {
        return this.drawable.alpha;
    }

    @Override
    public void setCenterPosition(float x, float y) {
        this.drawable.setCenterPosition(x, y);
        this.parentView.invalidate();
    }

    @Override
    public void moveCenterPosition(float dx, float dy) {
        this.drawable.moveCenterPosition(dx, dy);
        this.parentView.invalidate();
    }

    @Override
    public void setText(String text) {
        this.drawable.setText(text);
        this.parentView.invalidate();
    }

    @Override
    public String getText() {
        return this.drawable.text;
    }

    @Override
    public void setIcon(ControlElementDescription.Icon icon) {
        this.drawable.setIcon(icon);
        this.parentView.invalidate();
    }

    @Override
    public ControlElementDescription.Icon getIcon() {
        return this.drawable.icon;
    }

    @Override
    public void addBinding(GLFWBinding binding) {
        this.bindings.add(binding);
    }

    @Override
    public void setBinding(int index, GLFWBinding binding) {
        this.bindings.set(index, binding);
    }

    @Override
    public void removeBinding(int index) {
        this.bindings.remove(index);
    }

    @Override
    public ControlElementDescription describe() {
        return new ControlElementDescription(
                this.drawable.centerX / this.parentView.getWidth(),
                this.drawable.centerY / this.parentView.getHeight(),
                this.drawable.scale, this.drawable.shapeDrawable.getShape().getClass() == RectShape.class ? Type.BUTTON_RECT : Type.BUTTON_CIRCLE,
                this.bindings.toArray(new GLFWBinding[0]), this.drawable.text, this.drawable.color,
                this.drawable.alpha,
                this.inputType,
                this.drawable.icon,
                this.isToggle);
    }

    public class ButtonControlDrawable {
        private static final int PAINT_STROKE_WIDTH = 4;
        private static final int OUTLINE_ALPHA = 70;          // 0..255
        //private static final float OUTLINE_EXTRA_PX = 5f;
        private static final float OUTLINE_EXTRA_PX = 1.25f; // или 1.5f
        private static final float TEXT_OUTLINE_PX = 2.5f; //4f;
        private static final float BUTTON_CIRCLE_DIAMETER = 160.f;
        private static final float BUTTON_RECT_WIDTH = 240.f;
        private static final float BUTTON_RECT_HEIGHT = 120.f;

        private final Type type;
        private int color;
        private int alpha;
        private ColorFilter colorFilter;
        private float scale;
        private float width;
        private float height;
        private float centerX;
        private float centerY;
        private float x;
        private float y;
        private String text;
        private final TextPaint textPaint = new TextPaint();
        private float textY;
        private final ShapeDrawable shapeDrawable = new ShapeDrawable();
        private ControlElementDescription.Icon icon;
        private Drawable iconDrawable;

        public ButtonControlDrawable(InputControlsView parent, ControlElementDescription description) {
            this.type = description.type;

            setColor(description.color);
            setAlpha(description.alpha);

            this.colorFilter = null;

            switch (description.type) {
                case BUTTON_CIRCLE:
                    shapeDrawable.setShape(new OvalShape());
                    break;
                case BUTTON_RECT:
                    shapeDrawable.setShape(new RectShape());
                    break;

            }
            this.shapeDrawable.getPaint().setStyle(Paint.Style.STROKE);

            setScale(description.scale);
            setCenterPosition(description.centerXRelative * parentView.getWidth(),
                    description.centerYRelative * parentView.getHeight());

            this.textPaint.setStyle(Paint.Style.FILL);
            this.textPaint.setTextAlign(Paint.Align.CENTER);

            this.text = description.text;
            setTextSizeToFit();

            setIcon(description.icon);
        }

        public void draw(@NonNull Canvas canvas) {
            // --- Outline pass (black, a bit thicker) ---
            Paint p = this.shapeDrawable.getPaint();

            int oldColor = p.getColor();
            int oldAlpha = p.getAlpha();
            float oldStroke = p.getStrokeWidth();
            ColorFilter oldFilter = p.getColorFilter();

            p.setColor(android.graphics.Color.rgb(40, 40, 40));
            //p.setAlpha(OUTLINE_ALPHA);
            p.setAlpha(Math.min(oldAlpha, OUTLINE_ALPHA));
            p.setStrokeWidth(oldStroke + OUTLINE_EXTRA_PX * parentView.pixelScale);
            p.setColorFilter(null);
            this.shapeDrawable.draw(canvas);

            // --- Normal pass (your current style/color/alpha) ---
            p.setColor(oldColor);
            p.setAlpha(oldAlpha);
            p.setStrokeWidth(oldStroke);
            p.setColorFilter(oldFilter);
            this.shapeDrawable.draw(canvas);

            if (this.iconDrawable != null) {
                this.iconDrawable.draw(canvas);
            } else if (this.text != null) {
                float o = TEXT_OUTLINE_PX * parentView.pixelScale;

                // сохраняем параметры
                int oldTextColor = this.textPaint.getColor();
                int oldTextAlpha = this.textPaint.getAlpha();
                ColorFilter oldTextFilter = this.textPaint.getColorFilter();

                // outline pass: чёрный без фильтра
                //this.textPaint.setColor(android.graphics.Color.BLACK);
                this.textPaint.setColor(android.graphics.Color.rgb(40, 40, 40));
                //this.textPaint.setAlpha(OUTLINE_ALPHA);
                this.textPaint.setAlpha(Math.min(oldAlpha, OUTLINE_ALPHA));
                this.textPaint.setColorFilter(null);

                canvas.drawText(this.text, this.centerX - o, this.textY, this.textPaint);
                canvas.drawText(this.text, this.centerX + o, this.textY, this.textPaint);
                canvas.drawText(this.text, this.centerX, this.textY - o, this.textPaint);
                canvas.drawText(this.text, this.centerX, this.textY + o, this.textPaint);

                // normal pass: вернуть как было
                this.textPaint.setColor(oldTextColor);
                this.textPaint.setAlpha(oldTextAlpha);
                this.textPaint.setColorFilter(oldTextFilter);
                canvas.drawText(this.text, this.centerX, this.textY, this.textPaint);
            }
        }

        public boolean isPointOver(float x, float y) {
            return x >= this.x && x <= this.x + this.width && y >= this.y && y <= this.y + this.height;
        }

        public void setColor(int color) {
            this.color = color;
            this.shapeDrawable.getPaint().setColor(this.color);
            this.textPaint.setColor(this.color);
            if (this.iconDrawable != null)
                iconDrawable.setTint(this.color);
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
            this.shapeDrawable.getPaint().setAlpha(this.alpha);
            this.textPaint.setAlpha(this.alpha);
            if (this.iconDrawable != null)
                iconDrawable.setAlpha(this.alpha);
        }

        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            this.shapeDrawable.getPaint().setColorFilter(this.colorFilter);
            this.textPaint.setColorFilter(this.colorFilter);
            if (this.iconDrawable != null)
                this.iconDrawable.setColorFilter(this.colorFilter);
        }

        public void setScale(float scale) {
            this.scale = scale;
            updateDimensions();
        }

        public void setCenterPosition(float x, float y) {
            this.centerX = x;
            this.centerY = y;
            updateBounds();
        }

        public void moveCenterPosition(float dx, float dy) {
            setCenterPosition(this.centerX + dx, this.centerY + dy);
        }

        public void setText(String text) {
            if (text == null || text.isEmpty()) text = null;
            this.text = text;
            setTextSizeToFit();
        }

        public void setIcon(@NonNull ControlElementDescription.Icon icon) {
            this.icon = icon;
            if (this.icon == ControlElementDescription.Icon.NO_ICON) {
                this.iconDrawable = null;
            } else {
                Drawable shared = AppCompatResources.getDrawable(parentView.getContext(), icon.resId);
                if (shared == null) {
                    this.iconDrawable = null;
                } else {
                    this.iconDrawable = shared.mutate();
                    this.iconDrawable.setTint(this.color);
                    this.iconDrawable.setAlpha(this.alpha);
                    this.iconDrawable.setColorFilter(this.colorFilter);
                    updateIconDrawable();
                }
            }
        }

        private void updateDimensions() {
            switch (this.type) {
                case BUTTON_CIRCLE:
                    this.width = this.height = BUTTON_CIRCLE_DIAMETER * parentView.pixelScale * this.scale;
                    break;
                case BUTTON_RECT:
                    this.width = BUTTON_RECT_WIDTH * parentView.pixelScale * this.scale;
                    this.height = BUTTON_RECT_HEIGHT * parentView.pixelScale * this.scale;
                    break;

            }
            updateBounds();
        }

        private void updateBounds() {
            this.x = this.centerX - this.width / 2;
            this.y = this.centerY - this.height / 2;

            this.shapeDrawable.setBounds(Math.round(this.x), Math.round(this.y),
                    Math.round(this.x + this.width), Math.round(this.y + this.height));
            this.shapeDrawable.getPaint().setStrokeWidth(PAINT_STROKE_WIDTH * parentView.pixelScale
                    * (float) Math.sqrt(this.scale));

            setTextSizeToFit();
            updateIconDrawable();
        }

        private void setTextSizeToFit() {
            if (this.text == null) return;

            RectF contentBounds = getContentBounds();

            float textSize = 5f;
            this.textPaint.setTextSize(textSize);

            Rect textBounds = new Rect();
            this.textPaint.getTextBounds(this.text, 0, this.text.length(), textBounds);

            while (textBounds.width() <= contentBounds.width() && textBounds.height() <= contentBounds.height()) {
                textSize += 1f;
                this.textPaint.setTextSize(textSize);
                this.textPaint.getTextBounds(this.text, 0, this.text.length(), textBounds);
            }

            this.textY = this.centerY - textBounds.exactCenterY();
        }

        private void updateIconDrawable() {
            if (this.iconDrawable == null) return;
            RectF bounds = getContentBounds();

            float iconAspect = (float) this.iconDrawable.getIntrinsicWidth() / this.iconDrawable.getIntrinsicHeight();
            float boundsAspect = bounds.width() / bounds.height();

            float scaledWidth, scaledHeight;

            if (iconAspect > boundsAspect) {
                scaledWidth = bounds.width();
                scaledHeight = bounds.width() / iconAspect;
            } else {
                scaledHeight = bounds.height();
                scaledWidth = bounds.height() * iconAspect;
            }

            int left = (int) (bounds.left + (bounds.width() - scaledWidth) / 2);
            int top = (int) (bounds.top + (bounds.height() - scaledHeight) / 2);

            this.iconDrawable.setBounds(left, top, (int) (left + scaledWidth), (int) (top + scaledHeight));
        }

        private RectF getContentBounds() {
            final float contentScale = 0.8f;
            RectF bounds = new RectF();
            float contentW = 0;
            float contentH = 0;
            if (this.type == Type.BUTTON_RECT) {
                contentW = this.width * contentScale;
                contentH = this.height * contentScale;
            } else if (this.type == Type.BUTTON_CIRCLE) {
                contentW = this.width * contentScale / (float) Math.sqrt(2);
                contentH = this.width * contentScale / (float) Math.sqrt(2);
            }
            bounds.set((this.width - contentW) / 2,
                    (this.height - contentH) / 2,
                    this.width - (this.width - contentW) / 2,
                    this.height - (this.height - contentH) / 2);
            bounds.offset(this.x, this.y);
            return bounds;
        }
    }
}