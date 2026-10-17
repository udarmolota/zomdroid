package com.zomdroid.input;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import java.io.File;
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
    // Attention-color indicator shown while a toggle button is switched ON.
    private static final int TOGGLE_ON_COLOR = android.graphics.Color.parseColor("#FFA726");

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

    // Light haptic tick on press, only when the user enabled it (default off).
    private void maybeHaptic() {
        if (this.parentView.isVibrateOnTouch()) {
            this.parentView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
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
                    if (parentView.isPhysicalKeyboardConnected()) {
                        // Физическая клавиатура → маленькая встроенная клавиатурка
                        TextInputOverlayView.toggle(
                                parentView.getContext(),
                                (ViewGroup) parentView.getParent()
                        );
                    } else {
                        // Нет физической клавиатуры → системная Android IME
                        parentView.showTextInputOverlay();
                    }
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
                maybeHaptic();

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

                this.parentView.invalidate();
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
                    // A system touch-cancel must also clear the toggle ON state, otherwise the
                    // orange ON indicator stays lit even though the key was released.
                    isToggledOn = false;
                    this.parentView.invalidate();
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

    public void setStyle(ControlElementDescription.Style style) {
        this.drawable.setStyle(style);
        this.parentView.invalidate();
    }

    public ControlElementDescription.Style getStyle() {
        return this.drawable.getStyle();
    }

    public void setCustomIcon(String fileName, boolean noTint) {
        this.drawable.setCustomIcon(fileName, noTint);
        this.parentView.invalidate();
    }

    public String getIconFile() {
        return this.drawable.iconFile;
    }

    public boolean isNoTint() {
        return this.drawable.noTint;
    }

    public float getIconScale() {
        return this.drawable.iconScale;
    }

    public void setIconScale(float iconScale) {
        this.drawable.iconScale = iconScale;
        this.drawable.updateIconDrawable();
        this.parentView.invalidate();
    }

    public void setNoTint(boolean noTint) {
        if (this.drawable.iconFile != null) {
            this.drawable.setCustomIcon(this.drawable.iconFile, noTint);
        } else {
            this.drawable.noTint = noTint;
        }
        this.parentView.invalidate();
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
                this.isToggle,
                ControlElementDescription.DEFAULT_SENSITIVITY,
                this.drawable.style,
                this.drawable.iconFile,
                this.drawable.noTint,
                false, false, false,
                this.drawable.iconScale);
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
        private ControlElementDescription.Style style;
        private String iconFile;      // user image filename in controls/icons, or null
        private boolean noTint;       // keep custom image original colors when true
        private boolean roundBadge;   // the custom image is itself a round button: fill the circle
        private float iconScale = 1f; // custom image size relative to its default box (editor slider)

        public ButtonControlDrawable(InputControlsView parent, ControlElementDescription description) {
            this.type = description.type;

            setColor(description.color);
            setAlpha(description.alpha);

            this.colorFilter = null;
            this.style = (description.style != null) ? description.style : ControlElementDescription.DEFAULT_STYLE;

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
            this.iconScale = description.iconScaleOrDefault();
            if (description.iconFile != null && !description.iconFile.isEmpty()) {
                setCustomIcon(description.iconFile, description.noTint);
            }
        }

        public void draw(@NonNull Canvas canvas) {
            // --- Outline pass (black, a bit thicker) ---
            Paint p = this.shapeDrawable.getPaint();

            // Capture the base state from the drawable's own fields, NOT from the paint:
            // the paint is mutated across passes (and the toggle-ON contour leaves it orange),
            // so reading p.getColor() here would leak the ON color into the next frame and keep
            // the contour permanently highlighted.
            int oldColor = this.color;
            int oldAlpha = this.alpha;
            float oldStroke = p.getStrokeWidth();
            ColorFilter oldFilter = this.colorFilter;

            // A round badge brings its own circle: our fill and contour would only draw a second ring
            // (or a square frame on a rectangular button) next to it. The editor still shows where the
            // button is, with a thin dashed outline that does not read as part of the picture.
            boolean badge = this.roundBadge && this.iconFile != null && this.iconDrawable != null;

            // --- Fill pass (for FILLED / GLASS styles) ---
            if (!badge && (this.style == ControlElementDescription.Style.FILLED
                    || this.style == ControlElementDescription.Style.GLASS)) {
                Paint.Style oldPaintStyle = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                p.setColor(oldColor);
                p.setAlpha(this.style == ControlElementDescription.Style.GLASS
                        ? Math.round(oldAlpha / 3f) : oldAlpha);
                p.setColorFilter(oldFilter);
                this.shapeDrawable.draw(canvas);
                // restore for the outline/normal stroke passes below
                p.setStyle(oldPaintStyle);
                p.setColor(oldColor);
                p.setAlpha(oldAlpha);
                p.setColorFilter(oldFilter);
            }

            boolean toggledOn = getToggle() && ButtonControlElement.this.isToggledOn && !parentView.isEditMode;

            // --- Toggle-ON FILL: only for filled styles. OUTLINE buttons get an orange contour below
            //     (so the ON indicator matches the button's drawing style). ---
            if (!badge && toggledOn && (this.style == ControlElementDescription.Style.FILLED
                    || this.style == ControlElementDescription.Style.GLASS)) {
                Paint.Style onStyle = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                p.setColor(TOGGLE_ON_COLOR);
                p.setAlpha(200);
                p.setColorFilter(null);
                this.shapeDrawable.draw(canvas);
                p.setStyle(onStyle);
                p.setColor(oldColor);
                p.setAlpha(oldAlpha);
                p.setColorFilter(oldFilter);
            }

            if (!badge) {
                p.setColor(android.graphics.Color.rgb(40, 40, 40));
                //p.setAlpha(OUTLINE_ALPHA);
                p.setAlpha(Math.min(oldAlpha, OUTLINE_ALPHA));
                p.setStrokeWidth(oldStroke + OUTLINE_EXTRA_PX * parentView.pixelScale);
                p.setColorFilter(null);
                this.shapeDrawable.draw(canvas);
            }

            // --- Normal pass (contour). When toggled ON, draw the contour in the attention
            //     color and a bit thicker — this is the ON indicator for OUTLINE style. ---
            p.setColor(toggledOn ? TOGGLE_ON_COLOR : oldColor);
            p.setAlpha(oldAlpha);
            p.setStrokeWidth(toggledOn ? oldStroke * 1.7f : oldStroke);
            p.setColorFilter(toggledOn ? null : oldFilter);
            if (!badge || toggledOn) {
                this.shapeDrawable.draw(canvas);   // a badge keeps only the ON contour
            } else if (parentView.isEditMode) {
                p.setStrokeWidth(Math.max(1f, oldStroke / 2f));
                p.setPathEffect(new android.graphics.DashPathEffect(new float[] {
                        6f * parentView.pixelScale, 6f * parentView.pixelScale}, 0f));
                this.shapeDrawable.draw(canvas);
                p.setPathEffect(null);
            }
            // Restore the paint to the base state so nothing leaks into the next frame.
            p.setStrokeWidth(oldStroke);
            p.setColor(oldColor);
            p.setAlpha(oldAlpha);
            p.setColorFilter(oldFilter);

            if (this.iconDrawable != null) {
                if (toggledOn) {
                    // Tint the inner image/letter with the ON color too (not just the contour).
                    this.iconDrawable.setColorFilter(new android.graphics.PorterDuffColorFilter(
                            TOGGLE_ON_COLOR, android.graphics.PorterDuff.Mode.SRC_ATOP));
                    this.iconDrawable.draw(canvas);
                    this.iconDrawable.setColorFilter(this.colorFilter); // restore
                } else {
                    this.iconDrawable.draw(canvas);
                }
            } else if (this.text != null) {
                float o = TEXT_OUTLINE_PX * parentView.pixelScale;

                // сохраняем параметры (берём из полей, а не из «загрязнённой» краски — см. выше)
                int oldTextColor = this.color;
                int oldTextAlpha = this.alpha;
                ColorFilter oldTextFilter = this.colorFilter;

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

                // normal pass: вернуть как было (но при включённом тогле — оранжевым, как контур)
                this.textPaint.setColor(toggledOn ? TOGGLE_ON_COLOR : oldTextColor);
                this.textPaint.setAlpha(oldTextAlpha);
                this.textPaint.setColorFilter(toggledOn ? null : oldTextFilter);
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
            if (this.iconDrawable != null && shouldTintIcon())
                iconDrawable.setTint(this.color);
        }

        private boolean shouldTintIcon() {
            // Built-in icons are always tinted by the button color; custom images
            // are tinted unless the user asked to keep their original colors.
            return this.iconFile == null || !this.noTint;
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

        public void setStyle(ControlElementDescription.Style style) {
            this.style = (style != null) ? style : ControlElementDescription.DEFAULT_STYLE;
        }

        public ControlElementDescription.Style getStyle() {
            return this.style;
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
            // Choosing a built-in icon clears any custom image.
            this.iconFile = null;
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

        // Load a user-supplied image from controls/icons/<fileName> as the button icon.
        // Falls back to the built-in icon if the file is missing/undecodable.
        public void setCustomIcon(String fileName, boolean noTint) {
            this.noTint = noTint;
            if (fileName == null || fileName.isEmpty()) {
                this.iconFile = null;
                setIcon(this.icon);
                return;
            }
            File dir = parentView.getControlsIconsDir();
            File f = (dir != null) ? new File(dir, fileName) : null;
            Bitmap bmp = (f != null && f.isFile()) ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            if (bmp == null) {
                this.iconFile = null;
                setIcon(this.icon);
                return;
            }
            this.iconFile = fileName;
            this.roundBadge = isRoundBadge(bmp);
            BitmapDrawable bd = new BitmapDrawable(parentView.getResources(), bmp);
            if (shouldTintIcon()) {
                bd.setTint(this.color);
            } else {
                bd.setTintList(null);
            }
            bd.setAlpha(this.alpha);
            bd.setColorFilter(this.colorFilter);
            this.iconDrawable = bd;
            updateIconDrawable();
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
            RectF bounds = getIconContentBounds();

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

        /**
         * True for a picture that is itself a filled round button: opaque in the middle of each edge
         * and inside on the diagonals, transparent in the corners (images are alpha-trimmed on import, so the edges are its own).
         */
        private boolean isRoundBadge(Bitmap b) {
            int w = b.getWidth(), h = b.getHeight();
            if (w < 16 || h < 16 || Math.abs(w - h) > Math.max(w, h) / 10) return false;
            int m = Math.max(1, w / 64);   // a little inside the edge, past anti-aliasing
            boolean edges = alphaAt(b, w / 2, m) > 200 && alphaAt(b, w / 2, h - 1 - m) > 200
                    && alphaAt(b, m, h / 2) > 200 && alphaAt(b, w - 1 - m, h / 2) > 200;
            int c = Math.max(1, w / 16);   // well inside the corners a circle leaves empty
            boolean corners = alphaAt(b, c, c) < 40 && alphaAt(b, w - 1 - c, c) < 40
                    && alphaAt(b, c, h - 1 - c) < 40 && alphaAt(b, w - 1 - c, h - 1 - c) < 40;
            // Filled, not a ring or a cross: inside the circle on the diagonals too.
            int d = Math.round(w * 0.25f);   // ~0.7 of the radius along each diagonal
            boolean filled = alphaAt(b, d, d) > 200 && alphaAt(b, w - 1 - d, d) > 200
                    && alphaAt(b, d, h - 1 - d) > 200 && alphaAt(b, w - 1 - d, h - 1 - d) > 200;
            return edges && corners && filled;
        }

        private int alphaAt(Bitmap b, int x, int y) {
            return (b.getPixel(x, y) >>> 24) & 0xff;
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

        // Content area for the icon. Custom images get a larger area so they nearly fill the
        // button (built-in icons keep the smaller, padded area that suits their internal margins).
        private RectF getIconContentBounds() {
            if (this.iconFile == null) {
                return getContentBounds();
            }
            // Shape-aware icon box — fix suggested by user Willing-Run-8987: a rectangle can hold
            // a near-full image, but on a circle the icon box must stay inside the round outline —
            // a 0.92*diameter square pokes its corners past the circle. ~0.70 inscribes a full
            // square in the circle. (Imported images are alpha-trimmed on import, so this box maps
            // directly to the visible content.)
            // A round badge (an icon drawn as a whole round button, its own circle included) fills the
            // button instead, round or rectangular: inscribed in the 0.70 square it showed as a small
            // button inside the big one.
            final float contentScale = (this.roundBadge ? 1.0f
                    : (this.type == Type.BUTTON_RECT) ? 0.92f : 0.70f) * this.iconScale;
            float contentW = this.width * contentScale;
            float contentH = this.height * contentScale;
            RectF bounds = new RectF();
            bounds.set((this.width - contentW) / 2,
                    (this.height - contentH) / 2,
                    this.width - (this.width - contentW) / 2,
                    this.height - (this.height - contentH) / 2);
            bounds.offset(this.x, this.y);
            return bounds;
        }
    }
}