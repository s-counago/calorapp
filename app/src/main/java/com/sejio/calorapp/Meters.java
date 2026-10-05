package com.sejio.calorapp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Hand-drawn meters for the Pausa dashboard. All of them animate only when system motion is on. */
final class Meters {
    private Meters() { }

    /**
     * Calories as a sunrise: the arc climbs from the left horizon over the top towards the right one.
     * A small sun rides the tip. Past the limit the arc closes and the sun turns terracotta.
     */
    static final class SunGauge extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint horizon = new Paint(Paint.ANTI_ALIAS_FLAG), sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG), sunEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float shown = -1;
        private float target;
        private boolean over;
        private ValueAnimator animator;

        SunGauge(Context c) {
            super(c);
            float stroke = PausaUi.dp(c, 14);
            track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(stroke); track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(0xFFEDE7DA);
            arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(stroke); arc.setStrokeCap(Paint.Cap.ROUND);
            horizon.setColor(PausaUi.LINE); horizon.setStrokeWidth(PausaUi.dp(c, 1));
            horizon.setPathEffect(new DashPathEffect(new float[]{PausaUi.dp(c, 3), PausaUi.dp(c, 5)}, 0));
            sun.setColor(PausaUi.SURFACE);
            sunEdge.setStyle(Paint.Style.STROKE); sunEdge.setStrokeWidth(PausaUi.dp(c, 3.5f));
            halo.setStyle(Paint.Style.FILL);
        }

        void setProgress(int value, int limit) {
            float next = limit <= 0 ? 0 : Math.min(1f, value / (float) limit);
            over = value > limit;
            target = next;
            if (animator != null) animator.cancel();
            if (shown < 0 || !PausaUi.motion(getContext())) { shown = next; invalidate(); return; }
            animator = ValueAnimator.ofFloat(shown, next);
            animator.setDuration(820);
            animator.setInterpolator(PausaUi.EASE);
            animator.addUpdateListener(a -> { shown = (Float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int diameter = Math.min(width - PausaUi.dp(getContext(), 40), PausaUi.dp(getContext(), 272));
            setMeasuredDimension(width, diameter / 2 + PausaUi.dp(getContext(), 44));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float pad = PausaUi.dp(getContext(), 20);
            float radius = Math.min(w / 2f - pad, h - PausaUi.dp(getContext(), 44));
            float cx = w / 2f, cy = h - PausaUi.dp(getContext(), 22);
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
            arc.setShader(new SweepGradient(cx, cy,
                    new int[]{PausaUi.TERRACOTTA, PausaUi.TERRACOTTA, PausaUi.SUN, 0xFFD27C33, PausaUi.TERRACOTTA},
                    new float[]{0f, .3f, .5f, .75f, 1f}));
        }

        @Override protected void onDraw(Canvas canvas) {
            float cy = oval.centerY(), r = oval.width() / 2f, cx = oval.centerX();
            canvas.drawLine(oval.left - PausaUi.dp(getContext(), 14), cy, oval.right + PausaUi.dp(getContext(), 14), cy, horizon);
            canvas.drawArc(oval, 180, 180, false, track);
            float p = Math.max(0, shown);
            if (p > 0.002f) canvas.drawArc(oval, 180, 180 * p, false, arc);
            double angle = Math.toRadians(180 + 180 * p);
            float sx = cx + (float) (Math.cos(angle) * r), sy = cy + (float) (Math.sin(angle) * r);
            int tone = over ? PausaUi.TERRACOTTA : PausaUi.SUN;
            halo.setColor((tone & 0x00FFFFFF) | 0x33000000);
            canvas.drawCircle(sx, sy, PausaUi.dp(getContext(), 17), halo);
            canvas.drawCircle(sx, sy, PausaUi.dp(getContext(), 9), sun);
            sunEdge.setColor(tone);
            canvas.drawCircle(sx, sy, PausaUi.dp(getContext(), 9), sunEdge);
        }
    }

    /**
     * Protein as a filling capsule, the horizontal sibling of the calorie arc: sage deepening towards the
     * goal, with a small sun riding the tip. Reaching the goal closes the capsule and lights the sun.
     */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float shown = -1;
        private boolean reached;
        private ValueAnimator animator;

        Bar(Context c) {
            super(c);
            track.setColor(0xFFEDE7DA);
            sun.setColor(PausaUi.SURFACE);
            edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(PausaUi.dp(c, 2.5f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setProgress(int value, int goal) {
            float next = goal <= 0 ? 0 : Math.min(1f, value / (float) goal);
            reached = goal > 0 && value >= goal;
            if (animator != null) animator.cancel();
            if (shown < 0 || !PausaUi.motion(getContext())) { shown = next; invalidate(); return; }
            animator = ValueAnimator.ofFloat(shown, next);
            animator.setDuration(720);
            animator.setInterpolator(PausaUi.EASE);
            animator.addUpdateListener(a -> { shown = (Float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 22));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fill.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xFF8FA883, PausaUi.SAGE, 0xFF3F5A3C}, null, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas canvas) {
            float knob = PausaUi.dp(getContext(), 8), height = PausaUi.dp(getContext(), 10);
            float cy = getHeight() / 2f, left = knob, right = getWidth() - knob, r = height / 2;
            rect.set(left, cy - r, right, cy + r);
            canvas.drawRoundRect(rect, r, r, track);
            float p = Math.max(0, shown), tip = left + (right - left) * p;
            if (p > 0.004f) {
                rect.set(left, cy - r, Math.max(left + height, tip), cy + r);
                canvas.drawRoundRect(rect, r, r, fill);
            }
            if (p <= 0.004f) return;
            edge.setColor(reached ? PausaUi.SUN : PausaUi.SAGE);
            canvas.drawCircle(Math.max(left + r, tip), cy, knob - edge.getStrokeWidth() / 2, sun);
            canvas.drawCircle(Math.max(left + r, tip), cy, knob - edge.getStrokeWidth() / 2, edge);
        }
    }

    /** One dot per cigarette allowed today; extra ones spill over in terracotta. */
    static final class Dots extends View {
        private final Paint filled = new Paint(Paint.ANTI_ALIAS_FLAG), empty = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint excess = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count, goal = 1;
        private float reveal = 1;

        Dots(Context c) {
            super(c);
            filled.setColor(PausaUi.STONE);
            excess.setColor(PausaUi.TERRACOTTA);
            empty.setStyle(Paint.Style.STROKE); empty.setStrokeWidth(PausaUi.dp(c, 1.4f)); empty.setColor(0xFFCBC6BA);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int count, int goal) {
            boolean grew = count > this.count;
            this.count = count; this.goal = Math.max(1, goal);
            if (grew && PausaUi.motion(getContext())) {
                ValueAnimator a = ValueAnimator.ofFloat(0, 1);
                a.setDuration(420); a.setInterpolator(PausaUi.SPRING);
                a.addUpdateListener(v -> { reveal = (Float) v.getAnimatedValue(); invalidate(); });
                a.start();
            } else { reveal = 1; invalidate(); }
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), PausaUi.dp(getContext(), 16));
        }

        @Override protected void onDraw(Canvas canvas) {
            int total = Math.max(goal, count);
            float width = getWidth(), cy = getHeight() / 2f;
            float gap = PausaUi.dp(getContext(), 5);
            float size = Math.min(PausaUi.dp(getContext(), 11), (width - gap * (total - 1)) / total);
            if (size < PausaUi.dp(getContext(), 3)) size = PausaUi.dp(getContext(), 3);
            float r = size / 2f;
            // The strip is centred under its card; when it would overflow it simply starts at the edge.
            float used = total * size + (total - 1) * gap, start = Math.max(0, (width - used) / 2f);
            for (int i = 0; i < total; i++) {
                float x = start + r + i * (size + gap);
                if (x + r > width) break;
                boolean last = i == count - 1;
                float scale = last ? reveal : 1;
                if (i < count) canvas.drawCircle(x, cy, r * scale, i < goal ? filled : excess);
                else canvas.drawCircle(x, cy, r - empty.getStrokeWidth() / 2, empty);
            }
        }
    }

    /** The seven days of this week for one habit: done days filled, today ringed, future faded. */
    static final class Week extends View {
        private static final String[] LETTERS = {"L", "M", "X", "J", "V", "S", "D"};
        private final Paint done = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean[] marked = new boolean[7];
        private int today;

        Week(Context c) {
            super(c);
            done.setColor(PausaUi.SAGE);
            base.setColor(PausaUi.NEUTRAL);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(PausaUi.dp(c, 1.6f)); ring.setColor(PausaUi.INK);
            letter.setTextAlign(Paint.Align.CENTER);
            letter.setTextSize(PausaUi.dp(c, 10));
            letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(HabitStore.Habit habit) {
            String start = HabitStore.weekStart(HabitStore.today());
            today = HabitStore.daysElapsedThisWeek() - 1;
            for (int i = 0; i < 7; i++) marked[i] = habit.completedDays.contains(HabitStore.addDays(start, i));
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            int size = PausaUi.dp(getContext(), 22), gap = PausaUi.dp(getContext(), 5);
            setMeasuredDimension(resolveSize(size * 7 + gap * 6, w), size + PausaUi.dp(getContext(), 2));
        }

        @Override protected void onDraw(Canvas canvas) {
            float size = PausaUi.dp(getContext(), 22), gap = PausaUi.dp(getContext(), 5), r = size / 2f;
            float cy = getHeight() / 2f;
            for (int i = 0; i < 7; i++) {
                float cx = r + i * (size + gap);
                boolean future = i > today;
                if (marked[i]) canvas.drawCircle(cx, cy, r, done);
                else { base.setAlpha(future ? 110 : 255); canvas.drawCircle(cx, cy, r, base); }
                if (i == today) canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2, ring);
                letter.setColor(marked[i] ? PausaUi.SURFACE : future ? 0x80626960 : PausaUi.MUTED);
                canvas.drawText(LETTERS[i], cx, cy - (letter.descent() + letter.ascent()) / 2, letter);
            }
        }
    }

    /** Progress ring for the dark hero card. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float shown = -1;
        private ValueAnimator animator;

        Ring(Context c) {
            super(c);
            float stroke = PausaUi.dp(c, 5);
            track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(stroke); track.setColor(0x26F8F5ED);
            arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(stroke); arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(PausaUi.SUN);
        }

        void set(int value, int total) {
            float next = total <= 0 ? 0 : Math.min(1f, value / (float) total);
            if (animator != null) animator.cancel();
            if (shown < 0 || !PausaUi.motion(getContext())) { shown = next; invalidate(); return; }
            animator = ValueAnimator.ofFloat(shown, next);
            animator.setDuration(700); animator.setInterpolator(PausaUi.EASE);
            animator.addUpdateListener(a -> { shown = (Float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            float inset = track.getStrokeWidth();
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawArc(oval, 0, 360, false, track);
            if (shown > 0) canvas.drawArc(oval, -90, 360 * shown, false, arc);
        }
    }
}
