package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

/** Focusable, native card. Its controller owns all work; this view owns no timers. */
public final class SpeedTestCardView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density, textScale;
    private final Typeface regular, light;
    private double down = Double.NaN, up = Double.NaN, ping = Double.NaN, jitter = Double.NaN;
    private float progress;
    private String status;

    public SpeedTestCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity / density;
        regular = Typeface.create(getResources().getString(R.string.font_medium), Typeface.NORMAL);
        light = Typeface.create(getResources().getString(R.string.font_light), Typeface.NORMAL);
        status = getResources().getString(R.string.speed_ready);
        setFocusable(true);
        setClickable(true);
    }

    void show(double download, double upload, double latency, double variation, float fraction, String nextStatus) {
        down = download; up = upload; ping = latency; jitter = variation;
        progress = Math.max(0, Math.min(1, fraction));
        status = nextStatus;
        setContentDescription(getResources().getString(R.string.speed_description,
                number(down), number(up), number(ping), number(jitter), status));
        invalidate();
    }

    private String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.getDefault(), "%.1f", value) : "—";
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(density, density);
        float width = getWidth() / density, height = getHeight() / density;
        float column = (width - 24) / 2;
        transfer(canvas, 8, column, false, down, 0xFF66D9EF);
        transfer(canvas, 16 + column, column, true, up, 0xFF64E0BD);
        text(canvas, getResources().getString(R.string.speed_ping, number(ping)), 10, 99, 8.5f, 0xFFCAD4E3, regular, column - 4);
        text(canvas, getResources().getString(R.string.speed_jitter, number(jitter)), 18 + column, 99, 8.5f, 0xFFCAD4E3, regular, column - 4);
        paint.setColor(0xFF293444);
        rect.set(12, height - 18, width - 12, height - 16);
        canvas.drawRoundRect(rect, 2, 2, paint);
        rect.right = rect.left + (width - 24) * progress;
        paint.setColor(0xFF66D9EF);
        canvas.drawRoundRect(rect, 2, 2, paint);
        text(canvas, status, 12, height - 6, 8, 0xFF8F9FB4, regular, width - 24);
        canvas.restore();
    }

    /** Separate tinted panels, explicit labels and large vector transfer icons. */
    private void transfer(Canvas canvas, float x, float width, boolean upload, double value, int accent) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((accent & 0x00FFFFFF) | 0x14000000);
        rect.set(x, 6, x + width, 88);
        canvas.drawRoundRect(rect, 9, 9, paint);
        float center = x + width / 2;
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.3f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(center, 11, center, 26, paint);
        float tip = upload ? 11 : 26;
        float wing = upload ? 17 : 20;
        canvas.drawLine(center - 6, wing, center, tip, paint);
        canvas.drawLine(center + 6, wing, center, tip, paint);
        canvas.drawLine(center - 9, 28, center - 9, 32, paint);
        canvas.drawLine(center - 9, 32, center + 9, 32, paint);
        canvas.drawLine(center + 9, 32, center + 9, 28, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        text(canvas, getResources().getString(upload ? R.string.speed_upload_label : R.string.speed_download_label),
                center, 43, 9.2f, accent, regular, width - 10);
        text(canvas, number(value), center, 69, 23, 0xFFF1F6FF, light, width - 10);
        text(canvas, "Mbps", center, 83, 8.5f, 0xFFB0BED0, regular, width - 10);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color, Typeface face, float max) {
        paint.setColor(color);
        paint.setTypeface(face);
        paint.setTextSize(size * textScale);
        if (paint.measureText(value) > max) paint.setTextSize(paint.getTextSize() * max / paint.measureText(value));
        canvas.drawText(value, x, y, paint);
    }
}
