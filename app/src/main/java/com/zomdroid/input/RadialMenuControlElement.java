package com.zomdroid.input;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import java.io.File;
import java.util.Arrays;

/**
 * Radial swipe button (contributed, "ZOOM"). A circular button: tap + swipe to one of 4 directions
 * (Left/Up/Right/Down) fires that sector's binding as a quick tap. Center + 4 sector labels are
 * packed into the {@code text} field as "center|left|up|right|down". One binding per sector.
 */
public class RadialMenuControlElement extends AbstractControlElement {

    private final RadialMenuDrawable drawable;
    private int pointerId = -1;
    private float startX, startY;
    private int selectedSector = -1; // -1: Center, 0: Left, 1: Up, 2: Right, 3: Down
    private float sensitivity;
    private String centerTitle = "ZOOM";
    private String[] sectorLabels = {"", "", "", ""};
    private String iconFile;          // user image filename in controls/icons, or null
    private boolean noTint;           // keep custom image original colors when true
    private Drawable iconDrawable;    // loaded center image, or null

    public RadialMenuControlElement(InputControlsView parentView, ControlElementDescription desc) {
        super(parentView, desc);
        this.inputType = desc.inputType;
        this.sensitivity = (desc.sensitivity > 0f) ? desc.sensitivity : 1.0f;

        parseCombinedText(desc.text);
        this.bindings.clear();

        boolean hasLabels = false;
        for (String s : sectorLabels) {
            if (s != null && !s.isEmpty() && !s.equals("ZOOM")) {
                hasLabels = true; break;
            }
        }

        if (desc.bindings != null && desc.bindings.length >= 4) {
            this.bindings.addAll(Arrays.asList(desc.bindings));
            if (!hasLabels) {
                sectorLabels = (this.inputType == InputType.MNK) ?
                        new String[]{"A", "W", "D", "S"} : new String[]{"A", "X", "B", "Y"};
            }
        } else {
            applyDefaultBindings(this.inputType);
        }

        this.drawable = new RadialMenuDrawable(parentView, desc);
        this.drawable.text = this.centerTitle;

        this.noTint = desc.noTint;
        if (desc.iconFile != null && !desc.iconFile.isEmpty()) {
            setCustomIcon(desc.iconFile, desc.noTint);
        }
    }

    private void parseCombinedText(String raw) {
        if (raw != null && raw.contains("|")) {
            String[] parts = raw.split("\\|", -1);
            centerTitle = parts[0];
            for (int i = 0; i < 4 && i < parts.length - 1; i++) {
                sectorLabels[i] = parts[i+1];
            }
        } else {
            centerTitle = (raw != null && !raw.isEmpty()) ? raw : "ZOOM";
        }
    }

    private void applyDefaultBindings(InputType type) {
        this.bindings.clear();

        if (type == InputType.MNK) {
            this.bindings.add(GLFWBinding.KEY_A);
            this.bindings.add(GLFWBinding.KEY_W);
            this.bindings.add(GLFWBinding.KEY_D);
            this.bindings.add(GLFWBinding.KEY_S);
            this.sectorLabels = new String[]{"A", "W", "D", "S"};
        } else {
            this.bindings.add(GLFWBinding.GAMEPAD_BUTTON_A);
            this.bindings.add(GLFWBinding.GAMEPAD_BUTTON_X);
            this.bindings.add(GLFWBinding.GAMEPAD_BUTTON_B);
            this.bindings.add(GLFWBinding.GAMEPAD_BUTTON_Y);
            this.sectorLabels = new String[]{"A", "X", "B", "Y"};
        }
    }

    @Override
    public void setInputType(InputType t) {
        if (t == null || t == this.inputType) return;
        this.inputType = t;
        applyDefaultBindings(t);
        parentView.invalidate();
    }

    @Override
    public void draw(Canvas canvas) {
        boolean swiping = pointerId != -1 && selectedSector != -1;
        if (iconDrawable != null && !swiping) {
            // Idle: show the custom image in the center (no text). While swiping we still show
            // the sector label so the user gets feedback about the chosen direction.
            drawable.setState(pointerId != -1, selectedSector, "");
            drawable.draw(canvas);
            iconDrawable.draw(canvas);
        } else {
            String display = swiping ? sectorLabels[selectedSector] : centerTitle;
            drawable.setState(pointerId != -1, selectedSector, display);
            drawable.draw(canvas);
        }
    }

    /** Load a user image from controls/icons/&lt;fileName&gt; as the center icon (null clears it). */
    public void setCustomIcon(String fileName, boolean noTint) {
        this.noTint = noTint;
        if (fileName == null || fileName.isEmpty()) {
            this.iconFile = null;
            this.iconDrawable = null;
            parentView.invalidate();
            return;
        }
        File dir = parentView.getControlsIconsDir();
        File f = (dir != null) ? new File(dir, fileName) : null;
        Bitmap bmp = (f != null && f.isFile()) ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (bmp == null) {
            this.iconFile = null;
            this.iconDrawable = null;
            parentView.invalidate();
            return;
        }
        this.iconFile = fileName;
        BitmapDrawable bd = new BitmapDrawable(parentView.getResources(), bmp);
        if (!noTint) bd.setTint(drawable.color); else bd.setTintList(null);
        bd.setAlpha(drawable.alpha);
        this.iconDrawable = bd;
        updateIconBounds();
        parentView.invalidate();
    }

    public String getIconFile() { return iconFile; }
    public boolean isNoTint() { return noTint; }
    public void setNoTint(boolean noTint) {
        if (iconFile != null) setCustomIcon(iconFile, noTint);
        else this.noTint = noTint;
        parentView.invalidate();
    }

    private void updateIconBounds() {
        if (iconDrawable == null) return;
        float cx = drawable.centerX, cy = drawable.centerY, r = drawable.radius;
        // Inscribe a full square in the circle so the image corners don't poke past the round
        // outline (~0.70 of the diameter). Imported images are alpha-trimmed, so this maps to
        // the visible content directly.
        float box = r * 2f * 0.70f;
        float iw = iconDrawable.getIntrinsicWidth(), ih = iconDrawable.getIntrinsicHeight();
        float aspect = (ih == 0) ? 1f : iw / ih;
        float w, h;
        if (aspect > 1f) { w = box; h = box / aspect; } else { h = box; w = box * aspect; }
        int left = Math.round(cx - w / 2), top = Math.round(cy - h / 2);
        iconDrawable.setBounds(left, top, Math.round(left + w), Math.round(top + h));
    }

    @Override
    public ControlElementDescription describe() {
        String combined = centerTitle + "|" + sectorLabels[0] + "|" + sectorLabels[1] + "|" + sectorLabels[2] + "|" + sectorLabels[3];

        return new ControlElementDescription(drawable.centerX / parentView.getWidth(), drawable.centerY / parentView.getHeight(),
                drawable.scale, Type.RADIAL_MENU, this.bindings.toArray(new GLFWBinding[0]),
                combined, drawable.color, drawable.alpha, this.inputType, ControlElementDescription.Icon.NO_ICON, false,
                sensitivity, ControlElementDescription.DEFAULT_STYLE, iconFile, noTint);
    }

    @Override
    public void setText(String text) {
        if (text == null) text = "";
        this.centerTitle = text;
        this.drawable.text = text;
        this.parentView.invalidate();
    }

    @Override
    public String getText() {
        return this.centerTitle;
    }

    public void setSectorLabel(int i, String s) { if (i >= 0 && i < 4) sectorLabels[i] = s; parentView.invalidate(); }
    public String getSectorLabel(int i) { return sectorLabels[i]; }

    @Override public void setBindingLeft(GLFWBinding b)  { bindings.set(0, b); }
    @Override public GLFWBinding getBindingLeft()       { return bindings.get(0); }
    @Override public void setBindingUp(GLFWBinding b)    { bindings.set(1, b); }
    @Override public GLFWBinding getBindingUp()         { return bindings.get(1); }
    @Override public void setBindingRight(GLFWBinding b) { bindings.set(2, b); }
    @Override public GLFWBinding getBindingRight()      { return bindings.get(2); }
    @Override public void setBindingDown(GLFWBinding b)  { bindings.set(3, b); }
    @Override public GLFWBinding getBindingDown()       { return bindings.get(3); }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        int pid = e.getPointerId(actionIndex);
        float x = e.getX(actionIndex);
        float y = e.getY(actionIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (drawable.isPointOver(x, y) && pointerId == -1) {
                    pointerId = pid;
                    startX = x; startY = y;
                    selectedSector = -1;
                    maybeHaptic();
                    parentView.invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pointerId != -1) {
                    int idx = e.findPointerIndex(pointerId);
                    if (idx >= 0) {
                        float dx = e.getX(idx) - startX;
                        float dy = e.getY(idx) - startY;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        float threshold = (55f * parentView.pixelScale) / sensitivity;

                        if (dist > threshold) {
                            double angle = Math.toDegrees(Math.atan2(dy, dx));
                            if (angle > 135 || angle <= -135) selectedSector = 0; // Left
                            else if (angle > -135 && angle <= -45) selectedSector = 1; // Up
                            else if (angle > -45 && angle <= 45) selectedSector = 2; // Right
                            else selectedSector = 3; // Down
                        } else {
                            selectedSector = -1;
                        }
                        parentView.invalidate();
                        return true;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pid == pointerId) {
                    if (selectedSector != -1 && action != MotionEvent.ACTION_CANCEL && !parentView.isEditMode) {
                        triggerSectorKey(selectedSector);
                    }
                    pointerId = -1;
                    selectedSector = -1;
                    parentView.invalidate();
                    return true;
                }
                break;
        }
        return false;
    }

    // Light haptic tick on press, only when the user enabled it (default off).
    private void maybeHaptic() {
        com.zomdroid.LauncherPreferences p = com.zomdroid.LauncherPreferences.getSingleton();
        if (p != null && p.isVibrateOnTouch()) {
            this.parentView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    }

    private void triggerSectorKey(int idx) {
        if (idx >= 0 && idx < bindings.size()) {
            GLFWBinding b = bindings.get(idx);
            if (b != null && b.code >= -111) {
                if (this.inputType == InputType.MNK) {
                    InputNativeInterface.sendKeyboard(b.code, true);
                    parentView.postDelayed(() -> InputNativeInterface.sendKeyboard(b.code, false), 50);
                } else {
                    InputNativeInterface.sendJoystickButton(b.code, true);
                    parentView.postDelayed(() -> InputNativeInterface.sendJoystickButton(b.code, false), 50);
                }
            }
        }
    }

    @Override public boolean isPointOver(float x, float y) { return drawable.isPointOver(x, y); }
    @Override public void setAlpha(int a) { drawable.setAlpha(a); if (iconDrawable != null) iconDrawable.setAlpha(a); parentView.invalidate(); }
    @Override public int getAlpha() { return drawable.alpha; }
    @Override public void setScale(float v) { drawable.setScale(Math.clamp(v, MIN_SCALE, MAX_SCALE)); updateIconBounds(); parentView.invalidate(); }
    @Override public float getScale() { return drawable.scale; }
    @Override public void setCenterPosition(float x, float y) { drawable.setCenterPosition(x, y); updateIconBounds(); parentView.invalidate(); }
    @Override public void moveCenterPosition(float dx, float dy) { drawable.moveCenterPosition(dx, dy); updateIconBounds(); parentView.invalidate(); }
    @Override public float getCenterX() { return drawable.centerX; }
    public void setSensitivity(float s) { this.sensitivity = s; }
    public float getSensitivity() { return sensitivity; }
    @Override public void setHighlighted(boolean h) { drawable.setColorFilter(h ? HIGHLIGHT_COLOR_FILTER : null); parentView.invalidate(); }
    @Override public void setIcon(ControlElementDescription.Icon i) {}
    @Override public ControlElementDescription.Icon getIcon() { return null; }

    static class RadialMenuDrawable {
        int color, alpha; float scale, centerX, centerY, radius;
        private boolean isTouching = false;
        private int selectedSector = -1;
        private String text = "";
        private String lastCalculatedText = "";
        private float lastCalculatedRadius = -1f;
        private float lastCalculatedTextSize = 5f;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thornPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final InputControlsView parentView; private final RectF rect = new RectF();

        RadialMenuDrawable(InputControlsView parentView, ControlElementDescription desc) {
            this.parentView = parentView;
            fillPaint.setStyle(Paint.Style.FILL); strokePaint.setStyle(Paint.Style.STROKE);
            thornPaint.setStyle(Paint.Style.FILL); glowPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            setColor(desc.color); setAlpha(desc.alpha); setScale(desc.scale);
            setCenterPosition(desc.centerXRelative * parentView.getWidth(), desc.centerYRelative * parentView.getHeight());
        }

        void setState(boolean touching, int sector, String t) { this.isTouching = touching; this.selectedSector = sector; this.text = t; }
        void setColor(int c) { color = c; fillPaint.setColor(c); strokePaint.setColor(c); thornPaint.setColor(c); textPaint.setColor(c); }
        void setAlpha(int a) { alpha = a; fillPaint.setAlpha(a / 4); strokePaint.setAlpha(a); textPaint.setAlpha(a); }
        void setColorFilter(ColorFilter cf) { fillPaint.setColorFilter(cf); strokePaint.setColorFilter(cf); thornPaint.setColorFilter(cf); textPaint.setColorFilter(cf); }
        void setScale(float s) { scale = s; radius = 80f * parentView.pixelScale * scale; updateBounds(); }
        void setCenterPosition(float x, float y) { centerX = x; centerY = y; updateBounds(); }
        void moveCenterPosition(float dx, float dy) { setCenterPosition(centerX + dx, centerY + dy); }
        void updateBounds() { rect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius); }
        boolean isPointOver(float x, float y) { return rect.contains(x, y); }

        void draw(Canvas canvas) {
            float s = parentView.pixelScale;
            boolean feedbackOn = parentView.showPressFeedback && !parentView.isEditMode;
            int feedbackColor = Color.parseColor("#FF8800");

            strokePaint.setColor(Color.rgb(30, 30, 30)); strokePaint.setAlpha(Math.min(alpha, 80));
            strokePaint.setStrokeWidth(6f * s); canvas.drawCircle(centerX, centerY, radius, strokePaint);

            fillPaint.setAlpha(alpha / 4); canvas.drawCircle(centerX, centerY, radius, fillPaint);

            int centerColor = (isTouching && selectedSector == -1 && feedbackOn) ? feedbackColor : color;
            strokePaint.setColor(centerColor); strokePaint.setAlpha(alpha); strokePaint.setStrokeWidth(3f * s); canvas.drawCircle(centerX, centerY, radius, strokePaint);

            if (text != null && !text.isEmpty()) {
                if (!text.equals(lastCalculatedText) || radius != lastCalculatedRadius) {
                    float contentW = (radius * 2 * 0.8f) / 1.414f;
                    float textSize = 5f;
                    textPaint.setTextSize(textSize);
                    android.graphics.Rect textBounds = new android.graphics.Rect();
                    textPaint.getTextBounds(text, 0, text.length(), textBounds);

                    while (textBounds.width() <= contentW && textBounds.height() <= contentW) {
                        textSize += 1f;
                        textPaint.setTextSize(textSize);
                        textPaint.getTextBounds(text, 0, text.length(), textBounds);
                    }
                    textSize -= 1f;
                    lastCalculatedTextSize = textSize;
                    lastCalculatedText = text;
                    lastCalculatedRadius = radius;
                }

                textPaint.setTextSize(lastCalculatedTextSize);
                android.graphics.Rect tb = new android.graphics.Rect();
                textPaint.getTextBounds(text, 0, text.length(), tb);
                float textY = centerY - tb.exactCenterY();

                float o = 2.5f * s;
                textPaint.setColor(android.graphics.Color.rgb(40, 40, 40));
                textPaint.setAlpha(Math.min(alpha, 70));
                canvas.drawText(text, centerX - o, textY, textPaint);
                canvas.drawText(text, centerX + o, textY, textPaint);
                canvas.drawText(text, centerX, textY - o, textPaint);
                canvas.drawText(text, centerX, textY + o, textPaint);

                textPaint.setColor((isTouching && feedbackOn) ? feedbackColor : color);
                textPaint.setAlpha(alpha);
                canvas.drawText(text, centerX, textY, textPaint);
            }

            for (int i = 0; i < 4; i++) {
                boolean isSelected = (isTouching && selectedSector == i && feedbackOn);
                if (isSelected) {
                    glowPaint.setColor(feedbackColor); glowPaint.setAlpha(120);
                    float gx = centerX, gy = centerY; float dist = radius * 2.0f;
                    if (i == 0) gx -= dist; else if (i == 1) gy -= dist; else if (i == 2) gx += dist; else if (i == 3) gy += dist;
                    canvas.drawCircle(gx, gy, 50f * s * scale, glowPaint);
                    drawThorn(canvas, i, feedbackColor, 255, s, 25f * s * scale);
                } else {
                    int thornAlpha = (isTouching && !parentView.isEditMode) ? 0 : (int) (alpha * 0.85f);
                    if (thornAlpha > 0) {
                        drawThorn(canvas, i, color, thornAlpha, s, 18f * s * scale);
                    }
                }
            }
        }

        private void drawThorn(Canvas canvas, int dir, int color, int alpha, float s, float size) {
            thornPaint.setColor(color); thornPaint.setAlpha(alpha);
            float offset = radius + (2f * s); Path p = new Path();
            if (dir == 1) { // Up
                p.moveTo(centerX, centerY - offset - size); p.lineTo(centerX - size/1.5f, centerY - offset); p.lineTo(centerX + size/1.5f, centerY - offset);
            } else if (dir == 2) { // Right
                p.moveTo(centerX + offset + size, centerY); p.lineTo(centerX + offset, centerY - size/1.5f); p.lineTo(centerX + offset, centerY + size/1.5f);
            } else if (dir == 3) { // Down
                p.moveTo(centerX, centerY + offset + size); p.lineTo(centerX - size/1.5f, centerY + offset); p.lineTo(centerX + size/1.5f, centerY + offset);
            } else if (dir == 0) { // Left
                p.moveTo(centerX - offset - size, centerY); p.lineTo(centerX - offset, centerY - size/1.5f); p.lineTo(centerX - offset, centerY + size/1.5f);
            }
            p.close(); canvas.drawPath(p, thornPaint);
        }
    }
}
