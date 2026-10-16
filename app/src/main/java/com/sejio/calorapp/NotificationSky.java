package com.sejio.calorapp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Calendar;
import java.util.Random;

/**
 * The background of the notification panel: the light of Hoy (PausaUi.skyColor) painted as a sky.
 * The sun crosses the panel from 07:00 to 21:00 and glows in the colour of the hour (sun in the
 * morning, terracotta in the evening); at night there is a moon and a few stars. The base stays deep
 * green so the cream text on top always reads.
 */
final class NotificationSky {
    private NotificationSky() { }

    /** Rounded sky of width × height pixels for the given moment. */
    static Bitmap render(int width, int height, float radius, Calendar now) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float w = bitmap.getWidth(), h = bitmap.getHeight();
        float hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f;
        int light = PausaUi.skyColor(now.get(Calendar.HOUR_OF_DAY));
        boolean night = light == PausaUi.SAGE;

        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);

        // Deep green base, lifted a little toward the light of the hour.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int top = night ? PausaUi.NIGHT_RAISED : blend(PausaUi.NIGHT_RAISED, light, light == PausaUi.SUN ? .20f : .24f);
        paint.setShader(new LinearGradient(0, 0, 0, h, top, darker(PausaUi.NIGHT, night ? .25f : .1f), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        // Where the light comes from: the sun along its arc, or the moon high on the right.
        float t = clamp((hour - 7f) / 14f);
        float sx = night ? w * .86f : w * (.06f + .88f * t);
        float sy = night ? h * .24f : h * (.95f - .78f * (float) Math.sin(Math.PI * t));
        float glow = Math.max(w, h) * (night ? .55f : .8f);
        paint.setShader(new RadialGradient(sx, sy, glow,
                new int[]{alpha(light, night ? .30f : .55f), alpha(light, night ? .10f : .20f), Color.TRANSPARENT},
                new float[]{0f, .4f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        // A warm horizon: stronger at dusk and dawn, a breath at midday, none at night.
        if (!night) {
            float low = 1f - (float) Math.sin(Math.PI * t);
            paint.setShader(new LinearGradient(0, h * .45f, 0, h,
                    Color.TRANSPARENT, alpha(light, .10f + .22f * low), Shader.TileMode.CLAMP));
            canvas.drawRect(0, h * .45f, w, h, paint);
        }
        paint.setShader(null);

        float body = h * .10f;
        if (night) {
            Random random = new Random(now.get(Calendar.YEAR) * 400L + now.get(Calendar.DAY_OF_YEAR));
            for (int i = 0; i < 26; i++) {
                float x = random.nextFloat() * w, y = random.nextFloat() * h * .8f;
                paint.setColor(alpha(PausaUi.ON_NIGHT, .18f + random.nextFloat() * .45f));
                canvas.drawCircle(x, y, Math.max(1f, h * (.004f + random.nextFloat() * .006f)), paint);
            }
            Path moon = new Path();
            moon.addCircle(sx, sy, body, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(sx + body * .55f, sy - body * .3f, body * .9f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
            paint.setColor(alpha(PausaUi.ON_NIGHT, .82f));
            canvas.drawPath(moon, paint);
        } else {
            paint.setColor(alpha(blend(light, Color.WHITE, .35f), .38f));
            canvas.drawCircle(sx, sy, body, paint);
            paint.setColor(alpha(blend(light, Color.WHITE, .5f), .16f));
            canvas.drawCircle(sx, sy, body * 1.7f, paint);
        }
        canvas.restore();
        return bitmap;
    }

    static int blend(int from, int to, float amount) {
        float a = clamp(amount);
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * a),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * a),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a));
    }

    private static int darker(int color, float amount) { return blend(color, Color.BLACK, amount); }

    private static int alpha(int color, float alpha) { return (color & 0x00FFFFFF) | (Math.round(clamp(alpha) * 255) << 24); }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
