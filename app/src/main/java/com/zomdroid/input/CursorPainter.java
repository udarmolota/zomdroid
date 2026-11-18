package com.zomdroid.input;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.zomdroid.R;

/**
 * Draws our own touchpad / mouse-stick cursor (used only while the game does not draw its
 * cursor) as the hand image res/drawable-nodpi/cursor_hand.png. The image and the idea come
 * from a community patch. The image's top-left corner is placed at the cursor point.
 */
final class CursorPainter {

    private static Bitmap bitmap;
    private static final RectF dst = new RectF();
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private CursorPainter() {
    }

    /** @param height drawn height in pixels; the width follows the image's aspect ratio */
    static void draw(Canvas canvas, Context context, float x, float y, float height) {
        Bitmap bmp = bitmap;
        if (bmp == null) {
            bmp = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor_hand);
            if (bmp == null) return;
            bitmap = bmp;
        }
        float w = bmp.getWidth() * height / bmp.getHeight();
        dst.set(x, y, x + w, y + height);
        canvas.drawBitmap(bmp, null, dst, paint);
    }
}
