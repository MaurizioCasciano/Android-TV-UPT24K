package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Three quiet cards. Drawn only when a real sample arrives; owns no timer or service. */
public final class ResourceSummaryView extends View {
    static final class Reading {
        final int cpu;
        final long ramUsedMb, ramTotalMb, diskUsedBytes, diskTotalBytes;
        final String description;

        Reading(int cpu, long ramUsedMb, long ramTotalMb,
                long diskUsedBytes, long diskTotalBytes, String description) {
            this.cpu = cpu;
            this.ramUsedMb = ramUsedMb;
            this.ramTotalMb = ramTotalMb;
            this.diskUsedBytes = diskUsedBytes;
            this.diskTotalBytes = diskTotalBytes;
            this.description = description;
        }
    }

    private static final int[] ACCENTS = {0xFF66D9EF, 0xFFB697FF, 0xFF64E0BD};
    private static final int HISTORY = 12;
    private final float[] cpuHistory = new float[HISTORY];
    private int historySize;
    private Reading reading;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float density;
    private final float textScale;
    private final Typeface medium;
    private final Typeface light;

    public ResourceSummaryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity / density;
        medium = Typeface.create(getResources().getString(R.string.font_medium), Typeface.NORMAL);
        light = Typeface.create(getResources().getString(R.string.font_light), Typeface.NORMAL);
        setFocusable(false);
    }

    void resetHistory() {
        historySize = 0;
    }

    void setReading(Reading next) {
        reading = next;
        if (next.cpu >= 0) {
            if (historySize == HISTORY) {
                System.arraycopy(cpuHistory, 1, cpuHistory, 0, HISTORY - 1);
                historySize--;
            }
            cpuHistory[historySize++] = Math.min(100, next.cpu);
        }
        setContentDescription(next.description);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(density, density);
        float width = getWidth() / density;
        float height = getHeight() / density;
        float cardWidth = (width - 16) / 3;
        for (int card = 0; card < 3; card++) {
            float x = card * (cardWidth + 8);
            int accent = ACCENTS[card];
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(x, 0, x + cardWidth, height,
                    0xFF202B3B, 0xFF101620, Shader.TileMode.CLAMP));
            rect.set(x + .5f, .5f, x + cardWidth - .5f, height - .5f);
            canvas.drawRoundRect(rect, 14, 14, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.7f);
            paint.setColor(alpha(accent, 55));
            canvas.drawRoundRect(rect, 14, 14, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(accent, 24));
            rect.set(x + 10, 10, x + 34, 34);
            canvas.drawRoundRect(rect, 7, 7, paint);
            drawIcon(canvas, card, x + 14, 14, accent);
            text(canvas, getResources().getString(card == 0 ? R.string.resource_cpu
                    : card == 1 ? R.string.resource_ram : R.string.resource_disk),
                    x + 41, 26, 10, 0xFFCAD4E3, medium, cardWidth - 51);

            double fraction = -1;
            if (reading != null) {
                if (card == 0 && reading.cpu >= 0) fraction = reading.cpu / 100.0;
                if (card == 1 && reading.ramTotalMb > 0 && reading.ramUsedMb >= 0)
                    fraction = reading.ramUsedMb / (double) reading.ramTotalMb;
                if (card == 2 && reading.diskTotalBytes > 0)
                    fraction = reading.diskUsedBytes / (double) reading.diskTotalBytes;
            }
            fraction = fraction < 0 ? -1 : Math.max(0, Math.min(1, fraction));
            String value = fraction < 0 ? "—" : Math.round(fraction * 100) + "%";
            text(canvas, value, x + 12, 65, 28, 0xFFF1F6FF, light, cardWidth - 24);
            String detail = getResources().getString(R.string.resource_recent);
            if (card > 0) {
                detail = "—";
                if (fraction >= 0) {
                    double divisor = card == 1 ? 1024.0 : 1024.0 * 1024 * 1024;
                    double used = card == 1 ? reading.ramUsedMb : reading.diskUsedBytes;
                    double total = card == 1 ? reading.ramTotalMb : reading.diskTotalBytes;
                    detail = getResources().getString(R.string.resource_usage_detail,
                            used / divisor, total / divisor);
                }
            }
            text(canvas, detail, x + 12, 82, 9.5f, 0xFF8F9FB4, medium, cardWidth - 24);
            if (card == 0) drawHistory(canvas, x + 12, 90, cardWidth - 24, height - 100, accent);
            else drawMeter(canvas, x + 12, height - 17, cardWidth - 24, fraction, accent);
        }
        canvas.restore();
    }

    private void drawHistory(Canvas canvas, float x, float y, float width, float height, int accent) {
        paint.setColor(0xFF283444);
        paint.setStrokeWidth(.6f);
        canvas.drawLine(x, y + height, x + width, y + height, paint);
        canvas.drawLine(x, y + height / 2, x + width, y + height / 2, paint);
        if (historySize == 0) return;
        float endX = x;
        float endY = y + height * (1 - cpuHistory[0] / 100);
        path.reset();
        path.moveTo(endX, endY);
        for (int i = 1; i < historySize; i++) {
            endX = x + width * i / (historySize - 1);
            endY = y + height * (1 - cpuHistory[i] / 100);
            path.lineTo(endX, endY);
        }
        if (historySize > 1) {
            path.lineTo(endX, y + height);
            path.lineTo(x, y + height);
            path.close();
            paint.setShader(new LinearGradient(0, y, 0, y + height,
                    alpha(accent, 100), alpha(accent, 5), Shader.TileMode.CLAMP));
            canvas.drawPath(path, paint);
            paint.setShader(null);
            path.reset();
            for (int i = 0; i < historySize; i++) {
                float px = x + width * i / (historySize - 1);
                float py = y + height * (1 - cpuHistory[i] / 100);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            paint.setColor(accent);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(accent);
        canvas.drawCircle(endX, endY, 2, paint);
    }

    private void drawMeter(Canvas canvas, float x, float y, float width, double fraction, int accent) {
        float segment = (width - 18) / 10;
        for (int i = 0; i < 10; i++) {
            rect.set(x + i * (segment + 2), y, x + i * (segment + 2) + segment, y + 6);
            paint.setColor(0xFF293444);
            canvas.drawRoundRect(rect, 2, 2, paint);
            float filled = (float) Math.max(0, Math.min(1, fraction * 10 - i));
            if (filled > 0) {
                rect.right = rect.left + segment * filled;
                paint.setColor(accent);
                canvas.drawRoundRect(rect, 2, 2, paint);
            }
        }
    }

    private void drawIcon(Canvas canvas, int kind, float x, float y, int color) {
        canvas.save();
        canvas.translate(x, y);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        if (kind < 2) {
            rect.set(2, kind == 0 ? 2 : 4, 14, kind == 0 ? 14 : 12);
            canvas.drawRoundRect(rect, 2, 2, paint);
            for (int i = 4; i <= 12; i += 4) {
                canvas.drawLine(i, kind == 0 ? 0 : 12, i, kind == 0 ? 2 : 15, paint);
                if (kind == 0) {
                    canvas.drawLine(i, 14, i, 16, paint);
                    canvas.drawLine(0, i, 2, i, paint);
                    canvas.drawLine(14, i, 16, i, paint);
                }
            }
            rect.set(5, 6, 11, 10);
            canvas.drawRect(rect, paint);
        } else {
            rect.set(2, 2, 14, 7);
            canvas.drawOval(rect, paint);
            canvas.drawLine(2, 4.5f, 2, 12, paint);
            canvas.drawLine(14, 4.5f, 14, 12, paint);
            rect.set(2, 7, 14, 12);
            canvas.drawArc(rect, 0, 180, false, paint);
            rect.set(2, 10, 14, 15);
            canvas.drawArc(rect, 0, 180, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void text(Canvas canvas, String value, float x, float y, float sp, int color,
                      Typeface face, float maxWidth) {
        paint.setColor(color);
        paint.setTypeface(face);
        paint.setTextSize(sp * textScale);
        float measured = paint.measureText(value);
        if (measured > maxWidth) paint.setTextSize(paint.getTextSize() * maxWidth / measured);
        canvas.drawText(value, x, y, paint);
    }

    private static int alpha(int color, int opacity) {
        return Color.argb(opacity, Color.red(color), Color.green(color), Color.blue(color));
    }
}
