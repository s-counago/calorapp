package com.sejio.calorapp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Pausa · Amanecer design system. Shared tokens, motion, sheets, snackbars and native controls.
 * Every icon-only action keeps a spoken label.
 */
final class PausaUi {
    static final int INK = 0xFF183C30, MUTED = 0xFF626960, GREEN = 0xFF214E3B;
    static final int SAGE = 0xFF50684C, STONE = 0xFF656960, TERRACOTTA = 0xFFAA4C30;
    static final int CREAM = 0xFFF8F5ED, SURFACE = 0xFFFFFCF6, NEUTRAL = 0xFFEEEAE1;
    static final int SAGE_SOFT = 0xFFE6EBDD, PEACH_SOFT = 0xFFFAE5D9, LINE = 0xFFE2DED3;
    static final int SUN = 0xFFE5AB35, SUN_SOFT = 0xFFFBEFD3;
    static final int NIGHT = 0xFF14342A, NIGHT_RAISED = 0xFF1E4537, CREAM_DEEP = 0xFFF2EDE0;
    static final int ON_NIGHT = 0xFFF8F5ED, ON_NIGHT_MUTED = 0xA6F8F5ED;

    static final String SCROLL_TAG = "pausa-scroll";
    static final String SNACK_HOST = "pausa-snack-host";
    static final Locale SPANISH = new Locale("es", "ES");

    /** Emphasized deceleration: quick to respond, soft to land. */
    static final TimeInterpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);
    static final TimeInterpolator SPRING = new OvershootInterpolator(1.6f);

    private static Runnable snackHide;

    private PausaUi() { }

    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }

    static boolean motion(Context c) {
        return Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1) != 0;
    }

    static String number(long value) { return NumberFormat.getIntegerInstance(SPANISH).format(value); }

    static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Franja of the day that matches DayPlanStore's migration thresholds. */
    static int currentFranja() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < 12 ? 0 : hour < 20 ? 1 : 2;
    }

    static String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < 6 ? "Buenas noches" : hour < 13 ? "Buenos días" : hour < 21 ? "Buenas tardes" : "Buenas noches";
    }

    /** The light of the hour: sun in the morning, terracotta in the evening, sage at night. */
    static int skyColor() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 6 && hour < 14 ? SUN : hour >= 14 && hour < 21 ? TERRACOTTA : SAGE;
    }

    // ---------------------------------------------------------------- surfaces

    static GradientDrawable surface(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(c, radius)); return d;
    }

    static GradientDrawable outline(Context c, int fill, int stroke, int radius) {
        GradientDrawable d = surface(c, fill, radius);
        d.setStroke(dp(c, 1), stroke); return d;
    }

    static GradientDrawable dashed(Context c, int stroke, int radius) {
        GradientDrawable d = surface(c, Color.TRANSPARENT, radius);
        d.setStroke(dp(c, 1.5f), stroke, dp(c, 5), dp(c, 4)); return d;
    }

    static Drawable ripple(Context c, int color, int radius) {
        boolean dark = Color.alpha(color) > 0 && luminance(color) < .35f;
        int wave = dark ? 0x33FFFFFF : 0x1A214E3B;
        return new RippleDrawable(ColorStateList.valueOf(wave), surface(c, color, radius), surface(c, Color.WHITE, radius));
    }

    static GradientDrawable card(Context c) {
        GradientDrawable background = surface(c, SURFACE, 24);
        background.setStroke(dp(c, 1), LINE);
        return background;
    }

    static Drawable cardRipple(Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x14214E3B), card(c), surface(c, Color.WHITE, 24));
    }

    static Drawable atmosphere() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{CREAM, CREAM, CREAM_DEEP});
    }

    /** Vertical fade to a colour, eased so content dissolves instead of being cut. */
    static Drawable fade(int color) {
        return new Drawable() {
            private final Paint paint = new Paint();
            @Override protected void onBoundsChange(Rect bounds) {
                int c = color & 0x00FFFFFF;
                paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                        new int[]{c, c | 0x73000000, c | 0xE0000000, c | 0xFF000000},
                        new float[]{0f, .38f, .7f, 1f}, Shader.TileMode.CLAMP));
            }
            @Override public void draw(Canvas canvas) { canvas.drawRect(getBounds(), paint); }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    // ---------------------------------------------------------------- type

    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("tnum"); v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }

    /** Navigation labels scale with the system font only up to a point; their icons and descriptions carry the rest. */
    static void capped(TextView view, float sp, float maxScale) {
        float scale = view.getResources().getConfiguration().fontScale;
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sp * Math.min(scale, maxScale));
    }

    static TextView editorial(Context c, String value, int size) {
        TextView view = text(c, value, size, INK, false);
        view.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        view.setFontFeatureSettings("lnum");
        return view;
    }

    static TextView eyebrow(Context c, String value, int color) {
        TextView view = text(c, value.toUpperCase(SPANISH), 11, color, true);
        view.setLetterSpacing(.12f);
        return view;
    }

    static void header(LinearLayout root, String title, String subtitle) {
        Context c = root.getContext();
        TextView heading = editorial(c, title, 30);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle == null) return;
        TextView detail = text(c, subtitle, 13, MUTED, false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, 6); p.bottomMargin = dp(c, 18); root.addView(detail, p);
    }

    // ---------------------------------------------------------------- controls

    static Button action(Context c, String label, boolean primary, Runnable action) {
        Button b = new Button(c); b.setText(label); b.setTextSize(15); b.setAllCaps(false);
        b.setTextColor(primary ? SURFACE : GREEN);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(ripple(c, primary ? GREEN : NEUTRAL, 18));
        b.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 6));
        b.setMinimumWidth(dp(c, 48)); b.setMinWidth(0); b.setMinimumHeight(dp(c, 48)); b.setMinHeight(dp(c, 48));
        b.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(c, R.animator.button_press));
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action.run(); });
        return b;
    }

    /** Quiet text action for secondary or destructive choices. */
    static Button quiet(Context c, String label, int color, Runnable action) {
        Button b = action(c, label, false, action);
        b.setTextColor(color);
        b.setBackground(ripple(c, Color.TRANSPARENT, 18));
        return b;
    }

    static ImageButton iconButton(Context c, String icon, String label, int color, Runnable action) {
        ImageButton b = new ImageButton(c);
        b.setImageDrawable(new Symbol(c, icon, color)); b.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 26) b.setTooltipText(label);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        b.setBackground(ripple(c, Color.TRANSPARENT, 24));
        b.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(c, R.animator.button_press));
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action.run(); });
        return b;
    }

    /** A pill-shaped choice. Selected chips are filled; others rest on the neutral tone. */
    static TextView chip(Context c, String label, boolean selected, Runnable action) {
        TextView chip = text(c, label, 14, INK, true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(c, 44)); chip.setMinWidth(dp(c, 48));
        chip.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        chip.setClickable(true); chip.setFocusable(true);
        chip.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(c, R.animator.button_press));
        setChip(chip, selected);
        chip.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); if (action != null) action.run(); });
        chip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
        return chip;
    }

    static void setChip(TextView chip, boolean selected) {
        Context c = chip.getContext();
        chip.setSelected(selected);
        chip.setTextColor(selected ? SURFACE : INK);
        chip.setBackground(ripple(c, selected ? GREEN : NEUTRAL, 22));
    }

    /** Tinted pill used for quick-add amounts. */
    static TextView amountChip(Context c, String label, int tint, int fill, String description, Runnable action) {
        TextView chip = chip(c, label, false, action);
        chip.setTextColor(tint);
        chip.setBackground(ripple(c, fill, 16));
        chip.setPadding(dp(c, 6), dp(c, 8), dp(c, 6), dp(c, 8));
        chip.setContentDescription(description);
        return chip;
    }

    static void input(EditText v) {
        Context c = v.getContext();
        v.setTextSize(16); v.setTextColor(INK); v.setHintTextColor(MUTED);
        v.setBackground(ripple(c, NEUTRAL, 18));
        v.setPadding(dp(c, 18), dp(c, 12), dp(c, 14), dp(c, 12));
        v.setMinimumHeight(dp(c, 52));
        v.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    static boolean isSubmit(int actionId, KeyEvent event) {
        return actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_SEND
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
    }

    static View hairline(Context c) {
        View line = new View(c); line.setBackgroundColor(LINE); return line;
    }

    // ---------------------------------------------------------------- motion

    /** Fade-and-rise entrance, the app's signature: content arrives like early light. */
    static void rise(View view, long delay) {
        if (!motion(view.getContext())) { view.setAlpha(1); view.setTranslationY(0); return; }
        view.animate().cancel();
        view.setAlpha(0f); view.setTranslationY(dp(view.getContext(), 14));
        view.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(360).setInterpolator(EASE).start();
    }

    static void stagger(ViewGroup group, int max) {
        int shown = 0;
        for (int i = 0; i < group.getChildCount() && shown < max; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            rise(child, 40L * shown++);
        }
    }

    static void pop(View view) {
        if (!motion(view.getContext())) return;
        view.animate().cancel();
        view.setScaleX(.86f); view.setScaleY(.86f);
        view.animate().scaleX(1).scaleY(1).setDuration(380).setInterpolator(SPRING).start();
    }

    /** Counts a number up or down instead of snapping. */
    static void countTo(TextView view, int from, int to, String suffix) {
        if (!motion(view.getContext()) || from == to) { view.setText(number(to) + suffix); return; }
        Object running = view.getTag(R.id.pausa_counter);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(Math.min(700, 260 + Math.abs(to - from) / 4));
        animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> view.setText(number((Integer) a.getAnimatedValue()) + suffix));
        view.setTag(R.id.pausa_counter, animator);
        animator.start();
    }

    // ---------------------------------------------------------------- snackbar

    static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Floating message above the navigation, with an optional single action (usually Deshacer). */
    static void snack(Context context, String message, String actionLabel, Runnable action) {
        Activity activity = activity(context);
        FrameLayout host = activity == null ? null
                : (FrameLayout) activity.getWindow().getDecorView().findViewWithTag(SNACK_HOST);
        if (host == null) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); return; }
        Context c = host.getContext();
        if (snackHide != null) host.removeCallbacks(snackHide);
        host.removeAllViews();
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(surface(c, NIGHT, 20));
        bar.setElevation(dp(c, 10));
        bar.setPadding(dp(c, 18), dp(c, 4), dp(c, 6), dp(c, 4));
        bar.setMinimumHeight(dp(c, 56));
        bar.setClickable(true);
        TextView label = text(c, message, 14, ON_NIGHT, false);
        label.setLineSpacing(0, 1.1f);
        label.setPadding(0, dp(c, 10), dp(c, 8), dp(c, 10));
        bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Runnable hide = () -> {
            if (bar.getParent() == null) return;
            if (!motion(c)) { host.removeView(bar); return; }
            bar.animate().alpha(0).translationY(dp(c, 16)).setDuration(180).setInterpolator(EASE)
                    .withEndAction(() -> host.removeView(bar)).start();
        };
        if (actionLabel != null) {
            TextView button = text(c, actionLabel, 14, SUN, true);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(c, 48)); button.setMinWidth(dp(c, 48));
            button.setPadding(dp(c, 14), 0, dp(c, 14), 0);
            button.setBackground(ripple(c, Color.TRANSPARENT, 16));
            button.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (action != null) action.run();
                host.removeCallbacks(hide);
                hide.run();
            });
            bar.addView(button, new LinearLayout.LayoutParams(-2, -2));
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.leftMargin = dp(c, 16); params.rightMargin = dp(c, 16);
        host.addView(bar, params);
        bar.announceForAccessibility(message);
        if (motion(c)) {
            bar.setAlpha(0); bar.setTranslationY(dp(c, 24));
            bar.animate().alpha(1).translationY(0).setDuration(300).setInterpolator(EASE).start();
        }
        snackHide = hide;
        host.postDelayed(hide, actionLabel == null ? 3200 : 5500);
    }

    // ---------------------------------------------------------------- sheets

    /** Bottom sheet with a drag handle, an editorial title and an optional trailing action. */
    static final class Sheet {
        final Dialog dialog;
        final LinearLayout panel;
        final LinearLayout body;
        final LinearLayout heading;
        final TextView title;
        private boolean tall;
        private final ScrollView scroller;

        Sheet(Context c, String titleText) {
            dialog = new Dialog(c);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            panel = new LinearLayout(c);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(c, 22), 0, dp(c, 22), dp(c, 18));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(SURFACE);
            float r = dp(c, 30);
            shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            panel.setBackground(shape);
            sheetHandle(dialog, panel);
            heading = new LinearLayout(c);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            title = editorial(c, titleText, 24);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.bottomMargin = dp(c, 14);
            panel.addView(heading, hp);
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            scroller = new ScrollView(c);
            scroller.setVerticalScrollBarEnabled(false);
            scroller.addView(body, new ScrollView.LayoutParams(-1, -2));
            panel.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
            dialog.setContentView(panel);
        }

        Sheet subtitle(String value) {
            Context c = panel.getContext();
            TextView detail = text(c, value, 13, MUTED, false);
            detail.setLineSpacing(0, 1.12f);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = -dp(c, 8); p.bottomMargin = dp(c, 16);
            panel.addView(detail, 2, p);
            return this;
        }

        Sheet trailing(View view) {
            heading.addView(view, new LinearLayout.LayoutParams(-2, -2));
            return this;
        }

        /** Fixed tall sheet. The body stops scrolling as a whole; callers add their own weighted list. */
        Sheet tall() {
            tall = true;
            panel.removeView(scroller);
            scroller.removeView(body);
            panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
            return this;
        }

        void add(View view, int bottomMargin) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(view.getContext(), bottomMargin);
            body.addView(view, p);
        }

        /** Two-button footer: quiet on the left, primary on the right. */
        LinearLayout footer(View secondary, View primary) {
            Context c = panel.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(c, 10), 0, 0);
            if (secondary != null) row.addView(secondary, new LinearLayout.LayoutParams(-2, dp(c, 52)));
            View spacer = new View(c);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            if (primary != null) {
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, dp(c, 52));
                pp.leftMargin = dp(c, 8);
                row.addView(primary, pp);
            }
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
            return row;
        }

        void show() {
            Context c = panel.getContext();
            Window window = dialog.getWindow();
            if (window != null) {
                if (motion(c)) window.setWindowAnimations(R.style.PausaSheetMotion);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int state = window.getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | state);
                window.setGravity(Gravity.BOTTOM);
                window.setDimAmount(.32f);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setNavigationBarColor(SURFACE);
            }
            dialog.show();
            if (window != null) {
                int height = tall ? (int) (c.getResources().getDisplayMetrics().heightPixels * .82f)
                        : WindowManager.LayoutParams.WRAP_CONTENT;
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
            }
        }

        void dismiss() { dialog.dismiss(); }
    }

    static void sheetHandle(Dialog dialog, LinearLayout panel) {
        Context c = panel.getContext();
        FrameLayout touch = new FrameLayout(c);
        View handle = new View(c);
        handle.setBackground(surface(c, LINE, 4));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(c, 40), dp(c, 5), Gravity.CENTER);
        touch.addView(handle, hp);
        panel.addView(touch, new LinearLayout.LayoutParams(-1, dp(c, 30)));
        touch.setContentDescription("Desliza hacia abajo para cerrar");
        touch.setOnTouchListener(new View.OnTouchListener() {
            float down, offset;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        panel.animate().cancel(); down = e.getRawY(); offset = panel.getTranslationY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (motion(c)) panel.setTranslationY(Math.max(0, offset + e.getRawY() - down)); return true;
                    case MotionEvent.ACTION_UP:
                        if (e.getRawY() - down > dp(c, 80)) { dialog.dismiss(); return true; }
                        view.performClick();
                        // Fall through: interrupted/short drags always return to their original position.
                    case MotionEvent.ACTION_CANCEL:
                        panel.animate().translationY(0).setDuration(motion(c) ? 220 : 0).setInterpolator(EASE).start(); return true;
                    default: return true;
                }
            }
        });
    }

    interface IntResult { void accept(int value); }

    /** Numeric editor sheet used for limits and goals. */
    static void numberSheet(Context c, String title, String subtitle, int value, int minimum, String error, IntResult onSave) {
        Sheet sheet = new Sheet(c, title);
        if (subtitle != null) sheet.subtitle(subtitle);
        EditText input = new EditText(c);
        input(input);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(28);
        input.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        input.setSingleLine(true);
        input.setText(value < 0 ? "" : String.valueOf(value));
        input.selectAll();
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        sheet.add(input, 4);
        Runnable save = () -> {
            try {
                int parsed = Integer.parseInt(input.getText().toString().trim());
                if (parsed < minimum) throw new NumberFormatException();
                onSave.accept(parsed);
                sheet.dismiss();
            } catch (NumberFormatException e) { input.setError(error); }
        };
        input.setOnEditorActionListener((v, id, event) -> { if (isSubmit(id, event)) { save.run(); return true; } return false; });
        sheet.footer(quiet(c, "Cancelar", MUTED, sheet::dismiss), action(c, "Guardar", true, save));
        Window window = sheet.dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        sheet.show();
        input.requestFocus();
    }

    // ---------------------------------------------------------------- custom views

    /** Round, animated check. Reads as a native checkbox to accessibility services. */
    static final class Check extends View implements Checkable {
        interface Listener { void changed(boolean checked); }
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path(), partial = new Path();
        private final PathMeasure measure = new PathMeasure();
        private boolean checked;
        private float progress;
        private int accent;
        private float diameter;
        private Listener listener;
        private ValueAnimator animator;

        Check(Context c, int accent) {
            super(c);
            this.accent = accent;
            diameter = dp(c, 24);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(c, 1.8f));
            mark.setStyle(Paint.Style.STROKE); mark.setStrokeWidth(dp(c, 2.2f)); mark.setColor(SURFACE);
            mark.setStrokeCap(Paint.Cap.ROUND); mark.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(accent);
            setClickable(true); setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setBackground(ripple(c, Color.TRANSPARENT, 24));
        }

        void setDiameter(int dpValue) { diameter = dp(getContext(), dpValue); invalidate(); }
        void setListener(Listener listener) { this.listener = listener; }
        void setAccent(int color) { accent = color; fill.setColor(color); invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            int size = dp(getContext(), 48);
            setMeasuredDimension(resolveSize(size, w), resolveSize(size, h));
        }

        @Override public boolean performClick() {
            setChecked(!checked, true);
            performHapticFeedback(checked ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.changed(checked);
            return super.performClick();
        }

        @Override public void setChecked(boolean value) { setChecked(value, false); }

        void setChecked(boolean value, boolean animate) {
            checked = value;
            if (animator != null) animator.cancel();
            float target = value ? 1f : 0f;
            if (!animate || !motion(getContext())) { progress = target; invalidate(); return; }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(value ? 380 : 200);
            animator.setInterpolator(value ? SPRING : EASE);
            animator.addUpdateListener(a -> { progress = (Float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        @Override public boolean isChecked() { return checked; }
        @Override public void toggle() { setChecked(!checked, true); }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = diameter / 2f;
            float p = Math.max(0, Math.min(1.15f, progress));
            ring.setColor(blend(0xFFB9B6AC, accent, Math.min(1, p)));
            canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2, ring);
            if (p > 0) canvas.drawCircle(cx, cy, r * Math.min(1f, p) * (p > 1 ? 1 + (p - 1) * .4f : 1), fill);
            if (p > .35f) {
                tick.reset();
                tick.moveTo(cx - r * .42f, cy + r * .02f);
                tick.lineTo(cx - r * .1f, cy + r * .34f);
                tick.lineTo(cx + r * .45f, cy - r * .3f);
                measure.setPath(tick, false);
                partial.reset();
                measure.getSegment(0, measure.getLength() * Math.min(1, (p - .35f) / .55f), partial, true);
                canvas.drawPath(partial, mark);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(CheckBox.class.getName());
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    static int blend(int from, int to, float t) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    /** Segmented control whose surface glides between options. */
    static final class Segmented extends LinearLayout {
        interface Listener { void select(int index); }
        private final TextView[] items;
        private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint indicatorEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float left = -1, right = -1;
        private int selected = -1;
        private ValueAnimator animator;

        Segmented(Context c, String[] labels, Listener listener) {
            super(c);
            setWillNotDraw(false);
            setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            setBackground(surface(c, NEUTRAL, 22));
            indicator.setColor(SURFACE);
            indicatorEdge.setColor(LINE); indicatorEdge.setStyle(Paint.Style.STROKE); indicatorEdge.setStrokeWidth(dp(c, 1));
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                TextView item = text(c, labels[i], 14, MUTED, true);
                capped(item, 14, 1.05f);
                item.setGravity(Gravity.CENTER);
                item.setMinHeight(dp(c, 44));
                item.setPadding(dp(c, 2), 0, dp(c, 2), 0);
                item.setSingleLine(true);
                item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.setContentDescription(labels[i]);
                item.setBackground(ripple(c, Color.TRANSPARENT, 18));
                item.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    listener.select(index);
                });
                item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                        info.setSelected(host.isSelected());
                    }
                });
                items[i] = item;
                addView(item, new LayoutParams(0, -2, 1));
            }
        }

        View item(int index) { return items[index]; }

        void select(int index, boolean animate) {
            if (index < 0 || index >= items.length) return;
            boolean changed = index != selected;
            selected = index;
            for (int i = 0; i < items.length; i++) {
                items[i].setSelected(i == index);
                items[i].setTextColor(i == index ? INK : MUTED);
            }
            if (getWidth() == 0 || items[index].getWidth() == 0) { left = -1; invalidate(); return; }
            float toLeft = items[index].getLeft(), toRight = items[index].getRight();
            if (!changed && left >= 0) return;
            if (animator != null) animator.cancel();
            if (!animate || left < 0 || !motion(getContext())) { left = toLeft; right = toRight; invalidate(); return; }
            float fromLeft = left, fromRight = right;
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(300);
            animator.setInterpolator(EASE);
            animator.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                left = fromLeft + (toLeft - fromLeft) * t;
                right = fromRight + (toRight - fromRight) * t;
                invalidate();
            });
            animator.start();
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (selected >= 0 && (animator == null || !animator.isRunning())) {
                left = items[selected].getLeft(); right = items[selected].getRight();
            }
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            if (selected >= 0 && left >= 0) {
                float radius = dp(getContext(), 18);
                rect.set(left, getPaddingTop(), right, getHeight() - getPaddingBottom());
                canvas.drawRoundRect(rect, radius, radius, indicator);
                canvas.drawRoundRect(rect, radius, radius, indicatorEdge);
            }
            super.dispatchDraw(canvas);
        }
    }

    /** Soft radial light drawn behind headers; its colour follows the time of day. */
    static final class Glow extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        Glow(int color) { this.color = color; }
        @Override protected void onBoundsChange(Rect bounds) {
            float radius = bounds.width() * 1.05f;
            if (radius <= 0) return;
            paint.setShader(new RadialGradient(bounds.left + bounds.width() * .9f, bounds.top + bounds.width() * .04f,
                    radius, new int[]{(color & 0x00FFFFFF) | 0x38000000, (color & 0x00FFFFFF) | 0x10000000, 0x00000000},
                    new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas canvas) { canvas.drawRect(getBounds(), paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** One rounded, 24-unit stroke family; vectors stay sharp at every density. */
    static final class Symbol extends Drawable {
        final Paint p = new Paint(3); final String name; final int size;
        Symbol(Context c, String name, int color) { this(c, name, color, 24); }
        Symbol(Context c, String name, int color, int sizeDp) {
            this.name = name; size = dp(c, sizeDp); p.setColor(color); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            setBounds(0, 0, size, size);
        }
        Symbol stroke(float width) { p.setStrokeWidth(width); return this; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        void line(Canvas c, float... points) {
            Path path = new Path(); path.moveTo(points[0], points[1]);
            for (int i=2; i<points.length; i+=2) path.lineTo(points[i], points[i+1]); c.drawPath(path, p);
        }
        @Override public void draw(Canvas c) {
            c.save(); c.translate(getBounds().left, getBounds().top); c.scale(getBounds().width()/24f, getBounds().height()/24f);
            switch(name) {
                case "sunrise":
                    p.setStyle(Paint.Style.FILL);
                    c.drawArc(3,10,21,28,180,180,true,p);
                    p.setStyle(Paint.Style.STROKE);
                    line(c,6,6,4,2); line(c,17,5,19,1); break;
                case "home":
                    line(c,3,11,12,3,21,11); line(c,6,10,6,21,10,21,10,15,14,15,14,21,18,21,18,10); break;
                case "tasks":
                    c.drawCircle(5, 6, 2, p); c.drawCircle(5, 17, 2, p);
                    line(c,11,6,21,6); line(c,11,17,21,17); break;
                case "list":
                    c.drawRoundRect(3,3,21,21,5,5,p); line(c,7,9,9,11,12,7); line(c,14,9,17,9); line(c,7,15,17,15); break;
                case "sun":
                    c.drawCircle(12,12,4,p);
                    for (int i=0;i<8;i++) { c.save(); c.rotate(i*45,12,12); line(c,12,2,12,4); c.restore(); } break;
                case "sunset":
                    c.drawArc(6,10,18,22,180,180,false,p); line(c,3,16,21,16); line(c,7,20,17,20);
                    line(c,12,3,12,6); line(c,5,8,6.5f,9.5f); line(c,19,8,17.5f,9.5f); break;
                case "moon":
                    Path moon = new Path(); moon.moveTo(20,15); moon.cubicTo(12,20,4,11,10,3);
                    moon.cubicTo(-1,7,3,22,14,21); moon.cubicTo(17,21,20,18,20,15); c.drawPath(moon,p); break;
                case "calendar":
                    c.drawRoundRect(3,5,21,21,4,4,p); line(c,7,3,7,7); line(c,17,3,17,7); line(c,3,10,21,10);
                    line(c,7,14,9,14); line(c,15,14,17,14); line(c,7,18,9,18); break;
                case "habit":
                    c.drawArc(4,4,20,20,-35,285,false,p); line(c,18,3,20,6,16,7);
                    line(c,8,12,11,15,17,9); break;
                case "train":
                    c.drawRoundRect(5,3,19,18,4,4,p); c.drawRoundRect(8,6,16,11,1,1,p);
                    line(c,8,21,10,18); line(c,14,18,16,21); c.drawCircle(8.5f,14.5f,.5f,p); c.drawCircle(15.5f,14.5f,.5f,p); break;
                case "ticket":
                    Path ticket = new Path(); ticket.moveTo(3,7); ticket.lineTo(21,7); ticket.lineTo(21,10);
                    ticket.cubicTo(19,10,19,14,21,14); ticket.lineTo(21,17); ticket.lineTo(3,17); ticket.lineTo(3,14);
                    ticket.cubicTo(5,14,5,10,3,10); ticket.close(); c.drawPath(ticket,p);
                    line(c,15,8.5f,15,9.5f); line(c,15,11.5f,15,12.5f); line(c,15,14.5f,15,15.5f); break;
                case "pulse":
                    line(c,2,13,6,13,9,5,14,20,17,11,22,11); break;
                case "flame":
                    Path f = new Path(); f.moveTo(12,2); f.cubicTo(14,8,20,9,19,15); f.cubicTo(18,24,4,23,5,14);
                    f.cubicTo(5,10,9,8,9,6); f.lineTo(11,12); f.cubicTo(14,9,13,5,12,2); c.drawPath(f,p); break;
                case "protein":
                    line(c,7,7,17,17); line(c,3,7,7,3); line(c,2,10,10,2);
                    line(c,14,22,22,14); line(c,17,21,21,17); break;
                case "cigarette":
                    c.drawRoundRect(3,14,21,19,1,1,p); line(c,16,14,16,19);
                    Path smoke = new Path(); smoke.moveTo(7,10); smoke.cubicTo(11,7,3,6,7,3); c.drawPath(smoke,p); break;
                case "tune":
                    line(c,4,7,20,7); line(c,4,17,20,17); p.setStyle(Paint.Style.FILL); int fill=p.getColor();
                    p.setColor(SURFACE); c.drawCircle(9,7,2.6f,p); c.drawCircle(15,17,2.6f,p); p.setColor(fill);
                    p.setStyle(Paint.Style.STROKE); c.drawCircle(9,7,2.6f,p); c.drawCircle(15,17,2.6f,p); break;
                case "edit": line(c,4,20,5,14,17,2,22,7,10,19,4,20); line(c,14,5,19,10); break;
                case "trash":
                    line(c,4,6,20,6); line(c,9,3,15,3); line(c,6,6,7,21,17,21,18,6);
                    line(c,10,10,10,17); line(c,14,10,14,17); break;
                case "reset":
                    c.drawArc(4,4,21,21,-80,295,false,p); line(c,3,4,3,10,9,10); break;
                case "grip":
                    for (int x=9;x<=15;x+=6) for(int y=6;y<=18;y+=6) c.drawCircle(x,y,.6f,p); break;
                case "chevron": line(c,9,5,16,12,9,19); break;
                case "back": line(c,15,5,8,12,15,19); break;
                case "down": line(c,5,9,12,16,19,9); break;
                case "plus": line(c,12,5,12,19); line(c,5,12,19,12); break;
                case "minus": line(c,5,12,19,12); break;
                case "check": line(c,4,12,9,17,20,6); break;
                case "close": line(c,6,6,18,18); line(c,18,6,6,18); break;
                case "arrow": line(c,5,12,19,12); line(c,13,6,19,12,13,18); break;
                case "swap": line(c,4,8,18,8); line(c,14,4,18,8,14,12); line(c,20,16,6,16); line(c,10,12,6,16,10,20); break;
                case "info": c.drawCircle(12,12,9,p); line(c,12,11,12,16); c.drawCircle(12,8,.4f,p); break;
                case "coffee":
                    c.drawRoundRect(3,7,17,19,3,3,p); c.drawArc(14,8,22,16,-90,180,false,p);
                    line(c,6,3,6,4); line(c,12,3,12,4); break;
                case "bottle":
                    c.drawRoundRect(8,2,16,6,1,1,p); line(c,8,6,5,10,5,21,19,21,19,10,16,6);
                    line(c,5,12,19,12); break;
                default: c.drawCircle(12,12,8,p);
            }
            c.restore();
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Scroll container that treats its bottom padding as covered (the floating navigation lives there).
     * Focus, keyboard and accessibility scrolling therefore stop with the target above the bar.
     */
    static class Scroll extends ScrollView {
        Scroll(Context c) {
            super(c);
            setTag(SCROLL_TAG);
            setClipToPadding(false);
            setVerticalScrollBarEnabled(false);
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (getChildCount() == 0) return 0;
            int visible = getHeight() - getPaddingTop() - getPaddingBottom();
            int top = getScrollY(), bottom = top + visible;
            int range = Math.max(0, getChildAt(0).getHeight() - visible);
            int delta = 0;
            if (rect.bottom > bottom && rect.top > top) {
                delta = rect.height() > visible ? rect.top - top : rect.bottom - bottom;
                delta = Math.min(delta, range - getScrollY());
            } else if (rect.top < top && rect.bottom < bottom) {
                delta = rect.height() > visible ? rect.bottom - bottom : rect.top - top;
                delta = Math.max(delta, -getScrollY());
            }
            return delta;
        }
    }

    /** A row that can be swiped to the left to delete. Taps and vertical scrolling pass through. */
    static final class SwipeRow extends FrameLayout {
        interface Listener { void dismissed(); }
        final View front;
        private final View back;
        private final int slop;
        private Listener listener;
        private float downX, downY;
        private boolean dragging;

        SwipeRow(Context c, View front) {
            super(c);
            this.front = front;
            LinearLayout reveal = new LinearLayout(c);
            reveal.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            reveal.setBackgroundColor(TERRACOTTA);
            reveal.setPadding(0, 0, dp(c, 22), 0);
            TextView label = text(c, "Eliminar", 13, SURFACE, true);
            label.setCompoundDrawables(new Symbol(c, "trash", SURFACE, 20), null, null, null);
            label.setCompoundDrawablePadding(dp(c, 8));
            reveal.addView(label);
            reveal.setAlpha(0);
            reveal.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            back = reveal;
            addView(back, new LayoutParams(-1, -1));
            addView(front, new LayoutParams(-1, -2));
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        void setListener(Listener listener) { this.listener = listener; }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); dragging = false; return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (!dragging && dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return dragging;
                default: return dragging;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    float tx = Math.min(0, e.getX() - downX);
                    front.setTranslationX(tx);
                    back.setAlpha(Math.min(1f, -tx / (getWidth() * .2f)));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return true;
                    dragging = false;
                    if (front.getTranslationX() < -getWidth() * .33f && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        front.animate().translationX(-getWidth()).setDuration(motion(getContext()) ? 180 : 0)
                                .setInterpolator(EASE).withEndAction(() -> { if (listener != null) listener.dismissed(); }).start();
                    } else {
                        front.animate().translationX(0).setDuration(motion(getContext()) ? 240 : 0).setInterpolator(EASE)
                                .withEndAction(() -> back.setAlpha(0)).start();
                    }
                    return true;
                default: return true;
            }
        }
    }

    static void fadeOutAndRun(View view, Runnable after) {
        if (!motion(view.getContext())) { after.run(); return; }
        view.animate().alpha(0).setDuration(140).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                view.animate().setListener(null); after.run();
            }
        }).start();
    }
}
