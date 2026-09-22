package com.zomdroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.View;

import java.util.Locale;

/**
 * The in-game performance bar: one line at the top, a dark rounded pill, each metric's name in its
 * own colour, thin separators, and a small FPS graph at the end — the layout testers know from
 * desktop overlays, so a screenshot reads at a glance. Ported from ValDroid 2026-09-22. Drawn directly rather than built from
 * TextViews so the width can follow the text without a layout pass per segment.
 *
 * Not clickable: touches go straight through to the on-screen controls underneath.
 */
final class PerfOverlayView extends View {
    private static final int C_BG     = 0xD0101216;
    private static final int C_VALUE  = 0xFFEDEDED;
    private static final int C_SEP    = 0xFF5A5F66;
    private static final int C_API    = 0xFFFF4F9A;
    private static final int C_GPU    = 0xFF7CFC5A;
    private static final int C_CPU    = 0xFFFFC34A;
    private static final int C_RAM    = 0xFF58B7FF;
    private static final int C_PWR    = 0xFFC58BFF;
    private static final int C_TMP    = 0xFFFF5A7A;
    private static final int C_FPS    = 0xFFFFE14A;
    private static final int C_GRAPH  = 0xFF37D67A;
    private static final int C_WARN   = 0xFFFFA23C;
    private static final int C_HOT    = 0xFFFF3B3B;

    private static final int HISTORY = 40;   // seconds of FPS in the graph

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float dp, padX, padY, gap, graphW;

    private final String api;
    private PerfSampler.Stats stats = new PerfSampler.Stats();
    private final int[] fpsHistory = new int[HISTORY];
    private int historyLen = 0;

    // Segments of the current frame: label, label colour, value, value colour.
    private String[] labels = new String[0], values = new String[0];
    private int[] labelColors = new int[0], valueColors = new int[0];

    PerfOverlayView(Context ctx, String apiLabel) {
        super(ctx);
        api = apiLabel;
        dp = ctx.getResources().getDisplayMetrics().density;
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                ctx.getResources().getDisplayMetrics()));
        bg.setColor(C_BG);
        graph.setColor(C_GRAPH);
        graph.setStyle(Paint.Style.STROKE);
        graph.setStrokeWidth(1.5f * dp);
        graph.setStrokeJoin(Paint.Join.ROUND);
        padX = 8 * dp; padY = 4 * dp; gap = 7 * dp; graphW = 64 * dp;
        setClickable(false);
        setFocusable(false);
        rebuild();
    }

    /** Called on the UI thread about once a second. */
    void setStats(PerfSampler.Stats s) {
        stats = s;
        if (s.fps >= 0) {
            if (historyLen == HISTORY) System.arraycopy(fpsHistory, 1, fpsHistory, 0, HISTORY - 1);
            else historyLen++;
            fpsHistory[historyLen - 1] = s.fps;
        }
        rebuild();
        requestLayout();   // the width follows the text
        invalidate();
    }

    private void rebuild() {
        PerfSampler.Stats s = stats;
        int tmpColor = s.thermal >= PowerManager.THERMAL_STATUS_MODERATE ? C_HOT
                     : s.thermal >= PowerManager.THERMAL_STATUS_LIGHT    ? C_WARN : C_VALUE;
        labels      = new String[]{ api,    "GPU",      "CPU",      "RAM",         "PWR",          "TMP",         "FPS" };
        labelColors = new int[]   { C_API,   C_GPU,      C_CPU,      C_RAM,         C_PWR,          C_TMP,         C_FPS };
        values      = new String[]{ "",      pct(s.gpu), pct(s.cpu), pct(s.ramPct), watts(s.watts), temp(s.tempC), s.fps >= 0 ? String.valueOf(s.fps) : "—" };
        valueColors = new int[]   { C_VALUE, C_VALUE,    C_VALUE,    C_VALUE,       C_VALUE,        tmpColor,      C_VALUE };
    }

    private static String pct(int v)     { return v >= 0 ? v + "%" : "—"; }
    private static String watts(float w) { return Float.isNaN(w) ? "—" : String.format(Locale.US, "%.1fW", w); }
    private static String temp(float t)  { return Float.isNaN(t) ? "—" : String.format(Locale.US, "%.1f°C", t); }

    private float segmentWidth(int i) {
        float w = text.measureText(labels[i]);
        if (!values[i].isEmpty()) w += text.measureText(" ") + text.measureText(values[i]);
        return w;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float w = padX * 2;
        for (int i = 0; i < labels.length; i++) w += segmentWidth(i) + gap * 2 + dp;   // text + separator
        w += graphW;
        Paint.FontMetrics fm = text.getFontMetrics();
        float h = (fm.descent - fm.ascent) + padY * 2;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        rect.set(0, 0, getWidth(), h);
        c.drawRoundRect(rect, 7 * dp, 7 * dp, bg);

        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = (h - (fm.descent - fm.ascent)) / 2 - fm.ascent;
        float x = padX;
        for (int i = 0; i < labels.length; i++) {
            text.setColor(labelColors[i]);
            c.drawText(labels[i], x, baseline, text);
            x += text.measureText(labels[i]);
            if (!values[i].isEmpty()) {
                x += text.measureText(" ");
                text.setColor(valueColors[i]);
                c.drawText(values[i], x, baseline, text);
                x += text.measureText(values[i]);
            }
            x += gap;
            text.setColor(C_SEP);
            c.drawRect(x, padY + 2 * dp, x + dp, h - padY - 2 * dp, text);   // thin separator
            x += dp + gap;
        }

        // FPS graph: the last HISTORY seconds, scaled to at least 60 so a steady 30 sits mid-height.
        if (historyLen >= 2) {
            int max = 60;
            for (int i = 0; i < historyLen; i++) max = Math.max(max, fpsHistory[i]);
            float top = padY + dp, bottom = h - padY - dp, left = x, width = graphW - padX;
            path.reset();
            for (int i = 0; i < historyLen; i++) {
                float px = left + width * i / (HISTORY - 1);
                float py = bottom - (bottom - top) * fpsHistory[i] / max;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            c.drawPath(path, graph);
        }
    }
}
