package io.tokenseek.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** Keeps the cashier's amount and wallet actions legible when their labels outgrow one row. */
public final class CheckoutActions extends LinearLayout {
    public CheckoutActions(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int needed = 0;
        int visible = 0;
        int unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            if (button.getVisibility() == GONE) continue;
            button.measure(unconstrained, unconstrained);
            LayoutParams params = (LayoutParams) button.getLayoutParams();
            needed += button.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            visible++;
        }
        boolean stack = visible > 1 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && needed > available;
        setOrientation(stack ? VERTICAL : HORIZONTAL);
        super.onMeasure(widthSpec, heightSpec);
    }
}
