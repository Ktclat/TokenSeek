package io.tokenseek.app;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class CheckoutActionsTest {
    @Test public void longAmountDoesNotSqueezeWalletActionOnNarrowScreens() {
        var device = InstrumentationRegistry.getInstrumentation();
        device.runOnMainSync(() -> {
            Context base = device.getTargetContext();
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(Locale.US);
            config.fontScale = 1.3f;
            Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.TokenSeekTheme);
            View screen = LayoutInflater.from(context).inflate(R.layout.activity_official_platform, null);
            Button fill = screen.findViewById(R.id.platform_fill_amount);
            Button wallet = screen.findViewById(R.id.platform_qr);
            fill.setText(context.getString(R.string.funding_fill_amount, "999999999999.99"));
            fill.setVisibility(View.VISIBLE);
            wallet.setVisibility(View.VISIBLE);
            ViewGroup row = (ViewGroup) wallet.getParent();
            for (int widthDp : new int[]{320, 600, 320}) {
                int width = Math.round(widthDp * context.getResources().getDisplayMetrics().density);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, width, row.getMeasuredHeight());
                assertEquals(1, wallet.getLineCount());
                assertEquals(1, fill.getLineCount());
                if (widthDp == 320) assertTrue(wallet.getTop() >= fill.getBottom());
                else assertEquals(fill.getTop(), wallet.getTop());
            }
        });
    }
}
