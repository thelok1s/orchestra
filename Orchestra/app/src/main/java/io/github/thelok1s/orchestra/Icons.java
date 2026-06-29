package io.github.thelok1s.orchestra;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Generates monochrome line-glyph bitmaps for device-setting controls (segmented-toggle options and
 * switch/row icons), in the spirit of the native Pixel Buds icons. Logical names come from the
 * device JSON (function/option {@code icon}); unknown names fall back to a generic "tune" glyph so a
 * control always has an icon. White stroke on transparent — Settings tints per theme.
 */
final class Icons {
    private static final int SIZE = 96;
    private static final float CX = SIZE / 2f, CY = SIZE / 2f;

    private Icons() {}

    static Bitmap forName(String name) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6f);
        p.setStrokeCap(Paint.Cap.ROUND);
        String n = name == null ? "" : name;
        switch (n) {
            case "anc":            // filled core + rings = active noise cancelling
                dot(c, p, 11f); ring(c, p, 23f); ring(c, p, 35f); break;
            case "adaptive":       // ring + sparkle = adaptive ANC
                ring(c, p, 24f); sparkle(c, p, 34f, -20f, 14f); break;
            case "transparency":   // dashed ring = let ambient through
                dashedRing(c, p, 33f, 40); dot(c, p, 12f); break;
            case "off":            // slashed ring = neutral/off
                ring(c, p, 30f); c.drawLine(CX - 20f, CY - 20f, CX + 20f, CY + 20f, p); break;
            case "dolby": case "surround": // speaker + waves
                speaker(c, p); arc(c, p, 18f, 30f); arc(c, p, 30f, 38f); break;
            case "multipoint":     // two linked rounded rects
                roundRect(c, p, CX - 34f, CY - 14f, CX - 8f, CY + 14f, 5f);
                roundRect(c, p, CX + 8f, CY - 14f, CX + 34f, CY + 14f, 5f);
                c.drawLine(CX - 8f, CY, CX + 8f, CY, p); break;
            case "ldac": case "hires": // rising bars = hi-res
                for (int i = 0; i < 4; i++) {
                    float x = CX - 27f + i * 18f, h = 10f + i * 12f;
                    c.drawLine(x, CY + 22f, x, CY + 22f - h, p);
                } break;
            case "ear":            // ear curve
                arc(c, p, 30f, 0, 300); dot(c, p, 6f); break;
            case "wind":           // wavy lines
                wave(c, p, CY - 16f); wave(c, p, CY); wave(c, p, CY + 16f); break;
            case "sidetone": case "mic": // mic capsule + stand
                roundRect(c, p, CX - 10f, CY - 28f, CX + 10f, CY + 6f, 10f);
                arc(c, p, 22f, 20, 140); c.drawLine(CX, CY + 22f, CX, CY + 32f, p); break;
            case "battery":        // battery + nub
                roundRect(c, p, CX - 30f, CY - 16f, CX + 26f, CY + 16f, 4f);
                p.setStyle(Paint.Style.FILL);
                c.drawRect(CX + 26f, CY - 7f, CX + 31f, CY + 7f, p);
                p.setStyle(Paint.Style.STROKE); break;
            case "volume":         // speaker + sound
                speaker(c, p); arc(c, p, 16f, 30f); break;
            case "gaming":         // gamepad
                roundRect(c, p, CX - 32f, CY - 14f, CX + 32f, CY + 16f, 14f);
                c.drawLine(CX - 20f, CY, CX - 8f, CY, p); c.drawLine(CX - 14f, CY - 6f, CX - 14f, CY + 6f, p);
                dot2(c, p, CX + 14f, CY - 3f, 4f); dot2(c, p, CX + 22f, CY + 5f, 4f); break;
            case "touch":          // tap
                ring(c, p, 16f); dot(c, p, 5f);
                c.drawLine(CX + 14f, CY + 14f, CX + 30f, CY + 30f, p); break;
            case "tune": default:  // sliders = generic control
                slider(c, p, CY - 18f, 0.35f); slider(c, p, CY, 0.65f); slider(c, p, CY + 18f, 0.5f); break;
        }
        return bmp;
    }

    private static void ring(Canvas c, Paint p, float r) { c.drawCircle(CX, CY, r, p); }
    private static void dot(Canvas c, Paint p, float r) {
        Paint.Style s = p.getStyle(); p.setStyle(Paint.Style.FILL);
        c.drawCircle(CX, CY, r, p); p.setStyle(s);
    }
    private static void dot2(Canvas c, Paint p, float x, float y, float r) {
        Paint.Style s = p.getStyle(); p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, p); p.setStyle(s);
    }
    private static void dashedRing(Canvas c, Paint p, float r, int step) {
        for (int a = 0; a < 360; a += step) {
            double t = Math.toRadians(a);
            c.drawLine(CX + (float) Math.cos(t) * (r - 9), CY + (float) Math.sin(t) * (r - 9),
                    CX + (float) Math.cos(t) * r, CY + (float) Math.sin(t) * r, p);
        }
    }
    private static void arc(Canvas c, Paint p, float r0, float r1) { // short wave between radii (right side)
        RectF rf = new RectF(CX - r1, CY - r1, CX + r1, CY + r1);
        c.drawArc(rf, -45, 90, false, p);
    }
    private static void arc(Canvas c, Paint p, float r, float start, float sweep) {
        RectF rf = new RectF(CX - r, CY - r, CX + r, CY + r);
        c.drawArc(rf, start, sweep, false, p);
    }
    private static void sparkle(Canvas c, Paint p, float x0, float y0, float len) {
        float x = CX + x0 * 0f + 22f, y = CY - 22f; // top-right sparkle
        c.drawLine(x - len / 2, y, x + len / 2, y, p);
        c.drawLine(x, y - len / 2, x, y + len / 2, p);
    }
    private static void speaker(Canvas c, Paint p) {
        Path path = new Path();
        path.moveTo(CX - 26f, CY - 9f); path.lineTo(CX - 14f, CY - 9f); path.lineTo(CX - 2f, CY - 20f);
        path.lineTo(CX - 2f, CY + 20f); path.lineTo(CX - 14f, CY + 9f); path.lineTo(CX - 26f, CY + 9f);
        path.close(); c.drawPath(path, p);
    }
    private static void wave(Canvas c, Paint p, float y) {
        Path path = new Path(); path.moveTo(CX - 30f, y);
        path.rQuadTo(7.5f, -8f, 15f, 0f); path.rQuadTo(7.5f, 8f, 15f, 0f);
        path.rQuadTo(7.5f, -8f, 15f, 0f); c.drawPath(path, p);
    }
    private static void roundRect(Canvas c, Paint p, float l, float t, float r, float b, float rad) {
        c.drawRoundRect(new RectF(l, t, r, b), rad, rad, p);
    }
    private static void slider(Canvas c, Paint p, float y, float pos) {
        c.drawLine(CX - 30f, y, CX + 30f, y, p);
        float kx = CX - 30f + pos * 60f;
        Paint.Style s = p.getStyle(); p.setStyle(Paint.Style.FILL);
        c.drawCircle(kx, y, 7f, p); p.setStyle(s);
    }
}
