package io.tokenseek.app;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class NativePlatformUsageStoreTest {
    private static ByteArrayInputStream report(String day, String amount) {
        String csv =
                "utc_date,model,api_key_name,api_key,type,price,amount\n"
                        + day
                        + ",deepseek-chat,Work,sk-****f00d,output_tokens,0.000002,"
                        + amount
                        + "\n";
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void importsStayIndependentAcrossPeriodsAndAccountsEvenWhenAnotherReportFails()
            throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile = UUID.randomUUID().toString();
        NativePlatformUsageStore store = new NativePlatformUsageStore(ctx, profile);
        NativePlatformUsageStore other =
                new NativePlatformUsageStore(ctx, UUID.randomUUID().toString());
        String first = store.importReport(report("2026-10-03", "1943"));
        String second = store.importReport(report("2026-11-13", "37"));
        assertNotEquals(first, second);
        assertEquals(second, store.importReport(report("2026-11-13", "37")));
        assertEquals(2, store.read().library.length());
        assertEquals("37", store.read().report.getJSONObject("totals").getString("tokens"));
        assertTrue(other.read().report == null);
        assertThrows(IOException.class, () -> other.select(first));
        store.select(first);
        assertThrows(IOException.class, () -> store.importReport(report("bad date", "271")));
        NativePlatformUsageStore.Snapshot reopened =
                new NativePlatformUsageStore(ctx, profile).read();
        assertEquals(first, reopened.selected);
        assertEquals("1943", reopened.report.getJSONObject("totals").getString("tokens"));
        String overlap = store.importReport(report("2026-10-03", "212"));
        assertNotEquals(first, overlap);
        assertEquals(3, store.read().library.length());
        assertEquals("212", store.read().report.getJSONObject("totals").getString("tokens"));
    }
}
