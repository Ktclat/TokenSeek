package io.tokenseek.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** A touch-tracked drawer that leaves vertical scrolling and text editing to their original views. */
final class NativeConversationDrawer extends FrameLayout {
    private final FrameLayout content;
    private final View shade;
    private final NativeDrawerPanel panel;
    private final int slop;
    private JSONObject screen = new JSONObject();
    private boolean opened;
    private boolean candidate;
    private boolean dragging;
    private float fraction;
    private float downX;
    private float downY;
    private float startFraction;
    private int drawerWidth;
    private ValueAnimator animation;
    private VelocityTracker velocity;

    NativeConversationDrawer(Context context, NativeConversation.Listener listener) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        content = new FrameLayout(context);
        addView(content, new LayoutParams(-1, -1));
        shade = new View(context);
        shade.setBackgroundColor(Color.BLACK);
        shade.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        shade.setOnClickListener(v -> close());
        shade.setFocusable(false);
        addView(shade, new LayoutParams(-1, -1));
        panel = new NativeDrawerPanel(context, (id, value) -> {
            if (id.equals("closeDrawer")) {
                close();
                return;
            }
            if (!id.equals("togglePin")) snapClosed();
            listener.action(id, value);
        });
        addView(panel, new LayoutParams(1, -1));
        move(0);
    }

    void setContent(View view) {
        content.removeAllViews();
        content.addView(view, new LayoutParams(-1, -1));
    }

    void render(JSONObject screen) {
        this.screen = screen;
        if (opened || fraction > 0) panel.render(screen);
    }

    void open() { settle(true); }

    boolean back() {
        if (!opened && fraction == 0 && !dragging) return false;
        if (!panel.closeActions()) close();
        return true;
    }

    private void close() { settle(false); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        drawerWidth = Math.max(1, Math.min(dp(328), width - dp(48)));
        panel.getLayoutParams().width = drawerWidth;
        super.onMeasure(widthSpec, heightSpec);
        panel.setTranslationX((fraction - 1f) * drawerWidth);
    }

    private void move(float value) {
        fraction = Math.max(0, Math.min(1, value));
        panel.setTranslationX((fraction - 1f) * drawerWidth);
        int visibility = fraction > 0 ? VISIBLE : INVISIBLE;
        panel.setVisibility(visibility);
        shade.setVisibility(visibility);
        shade.setAlpha(fraction * .34f);
    }

    private void setModal(boolean modal) {
        content.setImportantForAccessibility(modal ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        content.setDescendantFocusability(modal ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
    }

    private void settle(boolean open) {
        cancelAnimation();
        candidate = dragging = false;
        opened = open;
        if (open) {
            panel.render(screen);
            InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
            if (input != null) input.hideSoftInputFromWindow(getWindowToken(), 0);
            setModal(true);
        } else {
            panel.closeActions();
        }
        float end = open ? 1f : 0f;
        if (fraction == end || !isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            move(end);
            settleFocus(open);
            return;
        }
        ValueAnimator next = ValueAnimator.ofFloat(fraction, end);
        animation = next;
        next.setDuration(Math.round(110 + 100 * Math.abs(end - fraction)));
        next.setInterpolator(new DecelerateInterpolator());
        next.addUpdateListener(a -> move((float) a.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (animation != a) return;
                animation = null;
                settleFocus(open);
            }
        });
        next.start();
    }

    private void settleFocus(boolean open) {
        setModal(open);
        if (open && !isInTouchMode()) panel.requestFocus();
    }

    private void snapClosed() {
        cancelAnimation();
        opened = candidate = dragging = false;
        move(0);
        setModal(false);
        panel.closeActions();
    }

    private void cancelAnimation() {
        if (animation != null) {
            ValueAnimator previous = animation;
            animation = null;
            previous.cancel();
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            releaseTracker();
            velocity = VelocityTracker.obtain();
        }
        if (velocity != null) velocity.addMovement(event);
        boolean result = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            candidate = false;
            releaseTracker();
        }
        return result;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startFraction = fraction;
                dragging = false;
                candidate = opened || !reserved(content, downX, downY);
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                candidate = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!candidate || event.getPointerCount() != 1) return false;
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return false;
                if (Math.abs(dx) <= 1.35f * Math.abs(dy) || (opened ? dx >= 0 : dx <= 0)) {
                    candidate = false;
                    return false;
                }
                dragging = true;
                cancelAnimation();
                panel.render(screen);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                move(startFraction + dx / drawerWidth);
                return true;
            default: return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // Empty content can deliver DOWN directly here rather than to a child.
        if (!dragging && candidate && event.getActionMasked() == MotionEvent.ACTION_MOVE)
            onInterceptTouchEvent(event);
        if (!dragging) return candidate || super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                move(startFraction + (event.getX() - downX) / drawerWidth);
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                if (velocity != null) velocity.computeCurrentVelocity(1000);
                float speed = velocity == null ? 0 : velocity.getXVelocity();
                float direction = opened ? -1 : 1;
                boolean crossed = direction * dx > drawerWidth * .35f;
                boolean flick = direction * dx > dp(24) && direction * speed > dp(500);
                settle((crossed || flick) != opened);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                settle(opened);
                break;
        }
        return true;
    }

    private static boolean reserved(View view, float x, float y) {
        if (view instanceof EditText || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)) return true;
        if (view instanceof TextView && ((TextView) view).hasSelection()) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            View child = group.getChildAt(index);
            float localX = x + group.getScrollX() - child.getX();
            float localY = y + group.getScrollY() - child.getY();
            if (child.getVisibility() == VISIBLE && localX >= 0 && localX < child.getWidth()
                    && localY >= 0 && localY < child.getHeight()) {
                return reserved(child, localX, localY);
            }
        }
        return false;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (opened && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        snapClosed();
        releaseTracker();
        super.onDetachedFromWindow();
    }

    private void releaseTracker() {
        if (velocity != null) velocity.recycle();
        velocity = null;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
