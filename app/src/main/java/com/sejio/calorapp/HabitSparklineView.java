package com.sejio.calorapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Small cumulative-score chart used at the trailing edge of each habit card. */
final class HabitSparklineView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Integer> values = new ArrayList<>();
    private final Path curve = new Path();
    private final Path area = new Path();
    private float[] xs = new float[8];
    private float[] ys = new float[8];

    HabitSparklineView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        gridPaint.setColor(PausaUi.LINE);
        gridPaint.setStrokeWidth(dp(1));
        linePaint.setColor(PausaUi.SAGE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        dotPaint.setColor(PausaUi.SAGE);
    }

    void setValues(List<Integer> points) {
        values.clear();
        values.addAll(points);
        int required = Math.max(2, values.size());
        if (required > xs.length) {
            xs = new float[required];
            ys = new float[required];
        }
        int current = values.isEmpty() ? 0 : values.get(values.size() - 1);
        setContentDescription("Evolución de puntos, " + current + " en total");
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fillPaint.setShader(new LinearGradient(0, dp(7), 0, Math.max(dp(8), height - dp(7)),
                0x40516A4F, Color.TRANSPARENT, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(5), top = dp(7), right = getWidth() - dp(5), bottom = getHeight() - dp(7);
        if (right <= left || bottom <= top) return;

        canvas.drawLine(left, bottom, right, bottom, gridPaint);
        canvas.drawLine(left, top + (bottom - top) * .5f, right, top + (bottom - top) * .5f, gridPaint);

        if (values.isEmpty()) return;
        int minimum = values.get(0), maximum = values.get(0);
        for (int value : values) {
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        int count = Math.max(2, values.size());
        for (int index = 0; index < count; index++) {
            int source = values.size() == 1 ? 0 : Math.min(index, values.size() - 1);
            int value = values.get(source);
            xs[index] = left + (right - left) * index / (count - 1f);
            if (maximum == minimum) {
                ys[index] = top + (bottom - top) * .68f;
            } else {
                float ratio = (value - minimum) / (float) (maximum - minimum);
                ys[index] = bottom - ratio * (bottom - top) * .88f;
            }
        }

        curve.reset();
        curve.moveTo(xs[0], ys[0]);
        for (int index = 1; index < count; index++) {
            float middle = (xs[index - 1] + xs[index]) * .5f;
            curve.cubicTo(middle, ys[index - 1], middle, ys[index], xs[index], ys[index]);
        }
        area.set(curve);
        area.lineTo(xs[count - 1], bottom);
        area.lineTo(xs[0], bottom);
        area.close();
        canvas.drawPath(area, fillPaint);
        canvas.drawPath(curve, linePaint);
        canvas.drawCircle(xs[count - 1], ys[count - 1], dp(3), dotPaint);
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeWidth(dp(2));
        dotPaint.setColor(0x40516A4F);
        canvas.drawCircle(xs[count - 1], ys[count - 1], dp(5), dotPaint);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(PausaUi.SAGE);
    }

    private float dp(float value) {
        return PausaUi.dp(getContext(), value);
    }
}
