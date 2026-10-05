package com.sejio.calorapp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating bottom navigation. A cream pill of light glides behind the selected destination.
 * Sections: 0 Hoy (diario), 1 Viajes, 2 Lista, 3 Hábitos, 4 Mañana, 5 Semana, 6 Hoy (tablero), 7 Dinero.
 */
final class PausaNavigation extends LinearLayout {
    interface Listener { void select(int section); }
    static final String[] LABELS = {"Hoy", "Tareas", "Hábitos", "Viajes", "Dinero"};
    private static final String[] SYMBOLS = {"sunrise", "list", "habit", "train", "wallet"};
    private final TextView[] tabs = new TextView[5];
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float left = -1, right = -1;
    private int selected = -1;
    private int taskSection = 2;
    private ValueAnimator animator;

    PausaNavigation(Context context, Listener listener) {
        super(context);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        int sidePadding = dp(getResources().getConfiguration().screenWidthDp < 380 ? 2 : 6);
        setPadding(sidePadding, dp(6), sidePadding, dp(6));
        setBackground(PausaUi.surface(context, PausaUi.NIGHT, 34));
        setElevation(dp(10));
        indicator.setColor(PausaUi.CREAM);
        for (int index = 0; index < tabs.length; index++) {
            final int position = index;
            TextView tab = PausaUi.text(context, LABELS[index], 11, PausaUi.ON_NIGHT_MUTED, true);
            PausaUi.capped(tab, 11, 1f);
            tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(dp(52));
            tab.setPadding(0, dp(6), 0, dp(6));
            tab.setCompoundDrawablePadding(dp(3));
            tab.setContentDescription(LABELS[index]);
            tab.setBackground(PausaUi.ripple(context, android.graphics.Color.TRANSPARENT, 28));
            tab.setOnClickListener(view -> {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                listener.select(position == 0 ? 0 : position == 1 ? taskSection : position == 2 ? 3 : position == 3 ? 1 : 7);
            });
            tab.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    info.setSelected(host.isSelected());
                }
            });
            tabs[index] = tab;
            addView(tab, new LayoutParams(0, -1, 1));
        }
        select(0, false);
    }

    static int tabFor(int section) {
        return section == 7 ? 4 : section == 1 ? 3 : section == 3 ? 2 : section == 0 ? 0 : 1;
    }

    int taskSection() { return taskSection; }

    void select(int section, boolean animate) {
        int index = tabFor(section);
        if (section == 2 || (section >= 4 && section <= 6)) taskSection = section;
        boolean changed = index != selected;
        selected = index;
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == index;
            int color = active ? PausaUi.NIGHT : PausaUi.ON_NIGHT_MUTED;
            tabs[i].setSelected(active);
            tabs[i].setTextColor(color);
            tabs[i].setCompoundDrawables(null, new PausaUi.Symbol(getContext(), SYMBOLS[i], color, 22), null, null);
        }
        if (changed && animate) PausaUi.pop(tabs[index]);
        glideTo(index, animate && changed);
    }

    private void glideTo(int index, boolean animate) {
        TextView tab = tabs[index];
        if (tab.getWidth() == 0) { left = -1; invalidate(); return; }
        float toLeft = tab.getLeft(), toRight = tab.getRight();
        if (animator != null) animator.cancel();
        if (!animate || left < 0 || !PausaUi.motion(getContext())) { left = toLeft; right = toRight; invalidate(); return; }
        float fromLeft = left, fromRight = right;
        boolean forward = toLeft > fromLeft;
        TextView target = tabs[index];
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(380);
        animator.setInterpolator(PausaUi.EASE);
        animator.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            // The leading edge travels first, the trailing edge catches up: a soft stretch.
            float lead = Math.min(1, t * 1.35f), trail = Math.max(0, (t - .15f) / .85f);
            // Aim at where the tab is now: the bar may be widening while the pill travels.
            float endLeft = target.getLeft(), endRight = target.getRight();
            left = fromLeft + (endLeft - fromLeft) * (forward ? trail : lead);
            right = fromRight + (endRight - fromRight) * (forward ? lead : trail);
            invalidate();
        });
        animator.start();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (selected >= 0 && (animator == null || !animator.isRunning())) {
            left = tabs[selected].getLeft(); right = tabs[selected].getRight();
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (selected >= 0 && left >= 0) {
            float radius = (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
            rect.set(left, getPaddingTop(), right, getHeight() - getPaddingBottom());
            canvas.drawRoundRect(rect, radius, radius, indicator);
        }
        super.dispatchDraw(canvas);
    }

    private int dp(int value) { return PausaUi.dp(getContext(), value); }
}
