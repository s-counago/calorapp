package com.sejio.calorapp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/** Hand-drawn meters for the money screen. Like the rest of Pausa, they animate only when system motion is on. */
final class MoneyMeters {
    private MoneyMeters() { }

    static final int PAID_TONE = 0xFFB9CBB0, SPENT_TONE = 0xFFE89A74, OVER_TONE = 0xFFF2A07B, SAVE_TONE = 0xFF9DBDC4;

    /**
     * The payroll as a capsule of light: fixed lines already paid, fixed lines still to come (striped),
     * free spending, and what is left. A small marker shows where spending would be on pace today.
     */
    static final class PaycheckBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stripe = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path(), marker = new Path();
        private final RectF bar = new RectF();
        private long income, paid, pending, savings, spent, pace = -1;
        private float reveal = 1;
        private ValueAnimator animator;

        PaycheckBar(Context c) {
            super(c);
            track.setColor(0x24F8F5ED);
            stripe.setColor(PausaUi.SUN); stripe.setStrokeWidth(PausaUi.dp(c, 2.2f));
            mark.setColor(PausaUi.CREAM);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(long income, long paid, long pending, long savings, long spent, long pace, boolean animate) {
            this.income = Math.max(0, income); this.paid = Math.max(0, paid); this.pending = Math.max(0, pending);
            this.savings = Math.max(0, savings);
            this.spent = Math.max(0, spent); this.pace = pace;
            if (animator != null) animator.cancel();
            if (!animate || !PausaUi.motion(getContext())) { reveal = 1; invalidate(); return; }
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(1100); animator.setStartDelay(160); animator.setInterpolator(PausaUi.EASE);
            animator.addUpdateListener(a -> { reveal = (Float) a.getAnimatedValue(); invalidate(); });
            reveal = 0; animator.start();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 30));
        }

        @Override protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float top = PausaUi.dp(c, 12), height = PausaUi.dp(c, 16);
            bar.set(0, top, getWidth(), top + height);
            float radius = height / 2;
            canvas.drawRoundRect(bar, radius, radius, track);
            long total = Math.max(income, paid + pending + savings + spent);
            if (total <= 0) return;
            float width = getWidth(), unit = width / total;
            float limit = width * reveal;
            clip.reset(); clip.addRoundRect(bar, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.clipRect(0, 0, limit, getHeight());
            float x = 0;
            fill.setColor(PAID_TONE);
            canvas.drawRect(x, bar.top, x + paid * unit, bar.bottom, fill);
            x += paid * unit;
            if (pending > 0) {
                fill.setColor(0x47E5AB35);
                float end = x + pending * unit;
                canvas.drawRect(x, bar.top, end, bar.bottom, fill);
                canvas.save(); canvas.clipRect(x, bar.top, end, bar.bottom);
                float gap = PausaUi.dp(c, 6);
                for (float s = x - height; s < end; s += gap) canvas.drawLine(s, bar.bottom, s + height, bar.top, stripe);
                canvas.restore();
                x = end;
            }
            fill.setColor(SAVE_TONE);
            canvas.drawRect(x, bar.top, x + savings * unit, bar.bottom, fill);
            x += savings * unit;
            fill.setColor(paid + pending + savings + spent > income ? OVER_TONE : SPENT_TONE);
            canvas.drawRect(x, bar.top, x + spent * unit, bar.bottom, fill);
            // Hairline seams keep the segments legible without a legend.
            fill.setColor(PausaUi.NIGHT);
            float seam = PausaUi.dp(c, 1.5f);
            for (float edge : new float[]{paid * unit, (paid + pending) * unit, (paid + pending + savings) * unit, (paid + pending + savings + spent) * unit})
                if (edge > 0 && edge < width - 1) canvas.drawRect(edge - seam / 2, bar.top, edge + seam / 2, bar.bottom, fill);
            canvas.restore();
            if (total > income && income > 0 && reveal > .98f) {
                float at = income * unit;
                mark.setStrokeWidth(PausaUi.dp(c, 2));
                canvas.drawLine(at, bar.top - PausaUi.dp(c, 4), at, bar.bottom + PausaUi.dp(c, 4), mark);
            }
            if (pace >= 0 && reveal > .6f) {
                float at = Math.min(width - PausaUi.dp(c, 4), Math.max(PausaUi.dp(c, 4), (paid + pending + savings + pace) * unit));
                float size = PausaUi.dp(c, 5);
                marker.reset();
                marker.moveTo(at - size, top - PausaUi.dp(c, 9)); marker.lineTo(at + size, top - PausaUi.dp(c, 9));
                marker.lineTo(at, top - PausaUi.dp(c, 3)); marker.close();
                mark.setAlpha((int) (255 * Math.min(1, (reveal - .6f) / .4f)));
                canvas.drawPath(marker, mark);
                mark.setAlpha(255);
            }
        }
    }

    /** Free spending per day of the cycle against the daily allowance. Days can be tapped. */
    static final class DailyBars extends View {
        interface Listener { void select(int index); }
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long[] values = new long[0];
        private long allowance;
        private int today = -1, selected = -1, start;
        private float grow = 1;
        private Listener listener;
        private ValueAnimator animator;

        DailyBars(Context c) {
            super(c);
            dot.setColor(PausaUi.LINE);
            line.setColor(0xFFB9B6AC); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(PausaUi.dp(c, 1.2f));
            line.setPathEffect(new DashPathEffect(new float[]{PausaUi.dp(c, 4), PausaUi.dp(c, 4)}, 0));
            label.setTextSize(PausaUi.dp(c, 10)); label.setColor(PausaUi.MUTED);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setClickable(true);
        }

        void setListener(Listener listener) { this.listener = listener; }

        /** @param start epoch day of the first value, used for the date labels. */
        void set(long[] values, long allowance, int today, int start, boolean animate) {
            this.values = values; this.allowance = allowance; this.today = today; this.start = start; selected = -1;
            if (animator != null) animator.cancel();
            if (!animate || !PausaUi.motion(getContext())) { grow = 1; invalidate(); return; }
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(900); animator.setStartDelay(120); animator.setInterpolator(PausaUi.EASE);
            animator.addUpdateListener(a -> { grow = (Float) a.getAnimatedValue(); invalidate(); });
            grow = 0; animator.start();
        }

        void select(int index) { selected = index; invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 132));
        }

        private float slot() { return values.length == 0 ? 0 : getWidth() / (float) values.length; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (values.length == 0) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int index = Math.max(0, Math.min(values.length - 1, (int) (event.getX() / slot())));
                if (today >= 0 && index > today) return true;
                selected = selected == index ? -1 : index;
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
                if (listener != null) listener.select(selected);
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }

        @Override protected void onDraw(Canvas canvas) {
            Context c = getContext();
            int n = values.length;
            if (n == 0) return;
            float labels = PausaUi.dp(c, 18), top = PausaUi.dp(c, 16), baseline = getHeight() - labels;
            float chart = baseline - top, slot = slot(), gap = Math.max(PausaUi.dp(c, 1.5f), slot * .28f);
            float width = Math.max(PausaUi.dp(c, 2), slot - gap), radius = Math.min(width / 2, PausaUi.dp(c, 4));
            long max = Math.max(1, allowance * 3 / 2);
            for (long value : values) max = Math.max(max, value);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2;
                boolean future = today >= 0 && i > today;
                if (future) { canvas.drawCircle(cx, baseline - PausaUi.dp(c, 2), PausaUi.dp(c, 1.6f), dot); continue; }
                long value = Math.max(0, values[i]);
                float stagger = Math.max(0, Math.min(1, grow * 1.6f - i / (float) n * .6f));
                float h = value == 0 ? PausaUi.dp(c, 2) : Math.max(PausaUi.dp(c, 3), chart * value / max) * stagger;
                int color = value == 0 ? 0xFFDCD8CC : allowance > 0 && value > allowance ? PausaUi.TERRACOTTA : PausaUi.SAGE;
                if (selected >= 0 && selected != i) color = PausaUi.blend(color, PausaUi.SURFACE, .55f);
                if (selected == i) color = PausaUi.INK;
                bar.setColor(color);
                rect.set(cx - width / 2, baseline - h, cx + width / 2, baseline);
                canvas.drawRoundRect(rect, radius, radius, bar);
                if (i == today) {
                    bar.setColor(PausaUi.INK);
                    canvas.drawCircle(cx, baseline + PausaUi.dp(c, 5), PausaUi.dp(c, 2), bar);
                }
            }
            if (allowance > 0) {
                float y = baseline - chart * allowance / max;
                canvas.drawLine(0, y, getWidth(), y, line);
                String text = Budget.money(allowance, false) + "/día";
                label.setTextAlign(Paint.Align.RIGHT); label.setColor(PausaUi.MUTED);
                canvas.drawText(text, getWidth(), y - PausaUi.dp(c, 5), label);
            }
            label.setColor(PausaUi.MUTED);
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(dayLabel(0), 0, getHeight() - PausaUi.dp(c, 2), label);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(dayLabel(n - 1), getWidth(), getHeight() - PausaUi.dp(c, 2), label);
            int focus = selected >= 0 ? selected : today;
            if (focus > 2 && focus < n - 3) {
                label.setTextAlign(Paint.Align.CENTER); label.setColor(PausaUi.INK);
                canvas.drawText(selected >= 0 ? dayLabel(focus) : "hoy", slot * focus + slot / 2, getHeight() - PausaUi.dp(c, 2), label);
            }
        }

        private String dayLabel(int index) { return Budget.date(start + index); }
    }

    /** One capsule per fixed line, in the order shown: a constellation of what is already settled. */
    static final class StatusDots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int[] statuses = new int[0];
        private float reveal = 1;

        StatusDots(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int[] statuses, boolean animate) {
            this.statuses = statuses;
            if (!animate || !PausaUi.motion(getContext())) { reveal = 1; invalidate(); return; }
            ValueAnimator a = ValueAnimator.ofFloat(0, 1);
            a.setDuration(800); a.setStartDelay(200); a.setInterpolator(PausaUi.EASE);
            a.addUpdateListener(v -> { reveal = (Float) v.getAnimatedValue(); invalidate(); });
            reveal = 0; a.start();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 10));
        }

        @Override protected void onDraw(Canvas canvas) {
            int n = statuses.length;
            if (n == 0) return;
            Context c = getContext();
            float gap = PausaUi.dp(c, 4), width = (getWidth() - gap * (n - 1)) / n, h = getHeight(), r = h / 2;
            for (int i = 0; i < n; i++) {
                float x = i * (width + gap);
                float visible = Math.max(0, Math.min(1, reveal * n - i * .7f));
                rect.set(x, 0, x + width, h);
                int status = statuses[i];
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(PausaUi.NEUTRAL);
                canvas.drawRoundRect(rect, r, r, paint);
                if (status == Budget.RESERVED) {
                    paint.setColor(PausaUi.blend(PausaUi.SAGE, PausaUi.NEUTRAL, .5f));
                    rect.set(x, 0, x + width * visible, h);
                    if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, paint);
                    continue;
                }
                int color = status == Budget.PAID ? PausaUi.SAGE : status == Budget.LATE ? PausaUi.TERRACOTTA
                        : status == Budget.SOON || status == Budget.PARTIAL ? PausaUi.SUN : -1;
                if (color == -1) continue;
                float amount = status == Budget.PARTIAL || status == Budget.SOON ? .5f : 1f;
                paint.setColor(color);
                rect.set(x, 0, x + width * amount * visible, h);
                if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }

    /** Amount paid for one fixed line in recent cycles. The current cycle is the last, highlighted bar. */
    static final class HistoryBars extends View {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long[] values = new long[0];
        private String[] labels = new String[0];
        private int accent = PausaUi.SAGE;

        HistoryBars(Context c) {
            super(c);
            text.setTextSize(PausaUi.dp(c, 10)); text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void set(long[] values, String[] labels, int accent) { this.values = values; this.labels = labels; this.accent = accent; invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 116));
        }

        @Override protected void onDraw(Canvas canvas) {
            int n = values.length;
            if (n == 0) return;
            Context c = getContext();
            float bottom = getHeight() - PausaUi.dp(c, 18), top = PausaUi.dp(c, 18), slot = getWidth() / (float) n;
            float width = Math.min(PausaUi.dp(c, 28), slot * .55f);
            long max = 1;
            for (long value : values) max = Math.max(max, value);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2, h = values[i] <= 0 ? PausaUi.dp(c, 3) : Math.max(PausaUi.dp(c, 4), (bottom - top) * values[i] / max);
                boolean last = i == n - 1;
                bar.setColor(values[i] <= 0 ? PausaUi.LINE : last ? accent : PausaUi.blend(accent, PausaUi.SURFACE, .55f));
                rect.set(cx - width / 2, bottom - h, cx + width / 2, bottom);
                canvas.drawRoundRect(rect, PausaUi.dp(c, 6), PausaUi.dp(c, 6), bar);
                text.setColor(last ? PausaUi.INK : PausaUi.MUTED);
                canvas.drawText(labels[i], cx, getHeight() - PausaUi.dp(c, 3), text);
                if (values[i] > 0) canvas.drawText(Budget.money(values[i], false), cx, bottom - h - PausaUi.dp(c, 5), text);
            }
        }
    }

    /**
     * A capsule of light that drifts while a bank is being read. With a known total it fills instead;
     * without system motion it stays still at a third.
     */
    static final class Working extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float phase, fraction = -1, shown;
        private ValueAnimator drift;

        Working(Context c, int trackColor, int color) {
            super(c);
            track.setColor(trackColor); glow.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** -1 for an unknown duration, otherwise 0..1. */
        void set(float value) {
            float next = value < 0 ? -1 : Math.max(0, Math.min(1, value));
            if (next >= 0 && fraction >= 0 && PausaUi.motion(getContext())) {
                ValueAnimator a = ValueAnimator.ofFloat(shown, next);
                a.setDuration(500); a.setInterpolator(PausaUi.EASE);
                a.addUpdateListener(v -> { shown = (Float) v.getAnimatedValue(); invalidate(); });
                a.start();
            } else shown = Math.max(0, next);
            fraction = next;
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!PausaUi.motion(getContext())) return;
            drift = ValueAnimator.ofFloat(0, 1);
            drift.setDuration(1400); drift.setRepeatCount(ValueAnimator.INFINITE); drift.setInterpolator(PausaUi.EASE);
            drift.addUpdateListener(a -> { phase = (Float) a.getAnimatedValue(); if (fraction < 0) invalidate(); });
            drift.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (drift != null) drift.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 6));
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f, width = getWidth();
            rect.set(0, 0, width, getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            if (fraction >= 0) { rect.set(0, 0, Math.max(getHeight(), width * shown), getHeight()); canvas.drawRoundRect(rect, r, r, glow); return; }
            float size = width * .34f, start = drift == null ? width * .33f : -size + (width + size) * phase;
            rect.set(Math.max(0, start), 0, Math.min(width, start + size), getHeight());
            if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, glow);
        }
    }

    /** Thin share bar used by the category list. */
    static final class Share extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float share, shown;

        Share(Context c, int color, float share) {
            super(c);
            track.setColor(PausaUi.NEUTRAL); fill.setColor(color);
            this.share = Math.max(0, Math.min(1, share));
            shown = this.share;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void animateIn(long delay) {
            if (!PausaUi.motion(getContext())) return;
            ValueAnimator a = ValueAnimator.ofFloat(0, share);
            a.setDuration(700); a.setStartDelay(delay); a.setInterpolator(PausaUi.EASE);
            a.addUpdateListener(v -> { shown = (Float) v.getAnimatedValue(); invalidate(); });
            shown = 0; a.start();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 5));
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            if (shown <= 0) return;
            rect.set(0, 0, Math.max(getHeight(), getWidth() * shown), getHeight());
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }
}
