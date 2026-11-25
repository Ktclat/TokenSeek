package io.tokenseek.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public class NativePlatformUsageReportTest {
    private static final String LEGACY =
            "user_id,utc_date,model,api_key_name,api_key,type,price,amount";
    private static final String MODERN =
            "start_time_iso,end_time_iso,model,name,api_key_name,api_key,type,price,amount";
    private static final String COST =
            "start_time_iso,end_time_iso,model,name,wallet_type,cost,currency";
    private static final String PERIOD = "2026-10-25T00:00:00+01:00,2026-10-26T00:00:00Z";
    private static final String DAY = "report-user,2026-10-25,";

    @Test
    public void oldDailyCsvKeepsUsageDimensionsAndExactIntegers() throws Exception {
        String secret = "sk-superSecretForUnitTest5555";
        String phone = "\"Phone, \"\"A\"\"\n日常\"";
        String common = "m1," + phone + ",sk-a1234****9876,";
        JSONObject report =
                csv(
                        "\ufeff"
                                + LEGACY
                                + "\r\n"
                                + DAY
                                + common
                                + "input_cache_hit_tokens,0.0000001,123\r\n"
                                + DAY
                                + common
                                + "input_cache_miss_tokens,1.5e-7,1.2E1\r\n"
                                + DAY
                                + common
                                + "output_tokens,0.00000097,9007199254740993\r\n"
                                + DAY
                                + "m2,Tablet,sk-a1234****9876,request_count,,3\r\n"
                                + DAY
                                + "m2,Desktop,,request_count,,2\r\n"
                                + DAY
                                + "m2,Archive,,request_count,,1\r\n"
                                + DAY
                                + "m2,,,request_count,,1\r\n"
                                + DAY
                                + "m2,"
                                + secret
                                + ","
                                + secret
                                + ",request_count,,0\r\n"
                                + DAY
                                + ",Tools,,function_call,1,4\r\n");
        assertEquals("2026-10-25T00:00:00Z", report.getString("startUtc"));
        assertEquals("2026-10-26T00:00:00Z", report.getString("endUtc"));
        assertEquals(1, report.getInt("periodCount"));
        assertEquals(9, report.getInt("recognizedRows"));
        assertEquals(0, report.getInt("skippedRows"));
        assertEquals(7, report.getJSONArray("keys").length());
        assertEquals(2, report.getJSONArray("models").length());
        JSONObject totals = report.getJSONObject("totals");
        assertEquals("123", totals.getString("cached"));
        assertEquals("135", totals.getString("input"));
        assertEquals("9007199254740993", totals.getString("output"));
        assertEquals("9007199254741128", totals.getString("tokens"));
        assertEquals("7", totals.getString("requests"));
        assertEquals("4", totals.getString("tools"));
        assertEquals(0, report.getJSONArray("costs").length());
        JSONObject first = report.getJSONArray("keys").getJSONObject(0);
        assertEquals("Phone, \"A\" 日常", first.getString("name"));
        assertEquals("sk-a1234…9876", first.getString("masked"));
        assertFalse(first.getJSONObject("totals").has("requests"));
        assertFalse(report.toString().contains("report-user"));
        assertFalse(report.toString().contains(secret));
    }

    @Test
    public void offsetBoundsAndAuthoritativeCostsStayIndependentOfCounts() throws Exception {
        String amount =
                MODERN
                        + "\n"
                        + PERIOD
                        + ",m,m,One,sk-**1234,request_count,9999,3\n"
                        + PERIOD
                        + ",,search,Two,sk-**4567,function_call,9999,6\n";
        String cost =
                COST
                        + "\n"
                        + PERIOD
                        + ",m,m,Paid,1.5000000000000000,CNY\n"
                        + PERIOD
                        + ",m,m,Granted,0.01,CNY\n"
                        + PERIOD
                        + ",,search,Paid,2.5E-5,USD\n"
                        + PERIOD
                        + ",m,m,Paid,100,ZZZ\n";
        byte[] zip = zip("report/cost-2026-10.csv", cost, "report/amount-2026-10.csv", amount);
        File cache = privateDirectory();
        try {
            JSONObject report =
                    NativePlatformUsageReport.parse(new ByteArrayInputStream(zip), cache);
            assertEquals("2026-10-24T23:00:00Z", report.getString("startUtc"));
            assertEquals("2026-10-26T00:00:00Z", report.getString("endUtc"));
            JSONArray costs = report.getJSONArray("costs");
            assertEquals("CNY", costs.getJSONObject(0).getString("currency"));
            assertEquals("1.51", costs.getJSONObject(0).getString("amount"));
            assertEquals("USD", costs.getJSONObject(1).getString("currency"));
            assertEquals("0.000025", costs.getJSONObject(1).getString("amount"));
            assertEquals(2, costs.length());
            assertEquals(1, report.getInt("skippedRows"));
            assertEquals(2, report.getInt("recognizedRows"));
            assertEquals(6, report.getInt("rowCount"));
            assertFalse(report.getJSONObject("totals").has("tokens"));
            assertEquals("3", report.getJSONObject("totals").getString("requests"));
            assertEquals("6", report.getJSONObject("totals").getString("tools"));
            assertEquals(0, cache.list().length);
            assertThrows(
                    IOException.class,
                    () ->
                            NativePlatformUsageReport.parse(
                                    new ByteArrayInputStream(Arrays.copyOf(zip, zip.length - 26)),
                                    cache));
            assertEquals(0, cache.list().length);
        } finally {
            assertTrue(cache.delete());
        }
    }

    @Test
    public void aggregateAndPerWalletCostRowsCannotDoubleCountTheSameService() throws Exception {
        String amounts = MODERN + "\n" + PERIOD + ",m,m,Any,sk-**abcd,output_tokens,0,31\n";
        String aggregate = COST + "\n" + PERIOD + ",m,m,,1.50,CNY\n";
        String future = PERIOD + ",m,m,FutureWallet,1.50,CNY\n";
        JSONObject result = parse(zip("cost.csv", aggregate + future, "amount.csv", amounts));
        assertEquals(1, result.getInt("skippedRows"));
        assertEquals("1.5", result.getJSONArray("costs").getJSONObject(0).getString("amount"));
        String detail = PERIOD + ",m,m,Paid,1.50,CNY\n";
        assertThrows(
                IOException.class,
                () -> parse(zip("cost.csv", aggregate + detail, "amount.csv", amounts)));
    }

    @Test
    public void emptyAndUnknownReportsDoNotInventMetricsOrDates() throws Exception {
        JSONObject empty = csv(LEGACY + "\r\n");
        assertEquals(0, empty.getInt("amountRows"));
        assertEquals(0, empty.getInt("periodCount"));
        assertEquals(0, empty.getJSONObject("totals").length());
        assertFalse(empty.has("startUtc"));
        assertFalse(empty.has("endUtc"));
        JSONObject unknown =
                csv(
                        LEGACY
                                + ",amount_unit\n"
                                + DAY
                                + "m,t,,novel_type,,10,calls\n"
                                + DAY
                                + "m,t,,output_tokens,,99,megabytes\n");
        assertEquals(2, unknown.getInt("skippedRows"));
        assertEquals(0, unknown.getInt("recognizedRows"));
        assertEquals(0, unknown.getJSONObject("totals").length());
        assertEquals(0, unknown.getJSONArray("keys").length());
        assertEquals(1, unknown.getInt("periodCount"));
        assertEquals("2026-10-25T00:00:00Z", unknown.getString("startUtc"));
    }

    @Test
    public void rejectsAmbiguousOverlapsDuplicateBandsAndMismatchedExports() throws Exception {
        String a = ",m,m,One,sk-**f00d,output_tokens,0.0002,10\n";
        String amount = MODERN + "\n" + PERIOD + a;
        assertThrows(IOException.class, () -> csv(amount + PERIOD + a));
        assertThrows(
                IOException.class,
                () -> csv(amount + "2026-10-25T12:00:00Z,2026-10-27T00:00:00Z" + a));
        assertThrows(
                IOException.class,
                () -> csv(MODERN + "\n2026-10-25T00:00:00Z,2026-10-24T00:00:00Z" + a));
        assertThrows(
                IOException.class, () -> csv(LEGACY + "\n" + DAY + "m,n,,request_count,,1.2\n"));
        assertThrows(
                IOException.class, () -> csv(LEGACY + "\n" + DAY + "m,n,,request_count,,1e99\n"));
        String otherCost = COST + "\n2026-10-27T00:00:00Z,2026-10-28T00:00:00Z,m,m,Paid,1,CNY\n";
        assertThrows(
                IOException.class, () -> parse(zip("cost.csv", otherCost, "amount.csv", amount)));
        assertThrows(
                IOException.class, () -> parse(zip("cost-v1.csv", COST, "amount-v2.csv", amount)));
        assertThrows(IOException.class, () -> parse(zip("../amount.csv", amount)));
        assertThrows(
                IOException.class,
                () -> parse(zip("amount.csv", amount, "copy/amount.csv", amount)));
        String twoOwners =
                LEGACY
                        + "\n"
                        + DAY
                        + "m,n,,output_tokens,0,4\n"
                        + "different-user,2026-10-26,m,n,,output_tokens,0,9\n";
        assertThrows(IOException.class, () -> csv(twoOwners));
        String nextBand = PERIOD + ",m,m,One,sk-**f00d,output_tokens,0.0004,5\n";
        assertEquals("15", csv(amount + nextBand).getJSONObject("totals").getString("output"));
    }

    @Test
    public void rejectsMalformedUtf8CsvAndOversizedInflatedData() throws Exception {
        assertThrows(IOException.class, () -> csv(LEGACY + ",amount\n"));
        assertThrows(IOException.class, () -> csv(LEGACY + "\n" + DAY + "\"unfinished"));
        assertThrows(IOException.class, () -> csv("not a usage report"));
        assertThrows(IOException.class, () -> csv(COST + "\n"));
        assertThrows(IOException.class, () -> parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(data)) {
            archive.putNextEntry(new ZipEntry("amount.csv"));
            byte[] zero = new byte[64 * 1024];
            for (int i = 0; i < 513; i++) archive.write(zero);
            archive.closeEntry();
        }
        assertThrows(IOException.class, () -> parse(data.toByteArray()));
    }

    private static JSONObject csv(String text) throws IOException {
        return parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject parse(byte[] bytes) throws IOException {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        return NativePlatformUsageReport.parse(new ByteArrayInputStream(bytes), cache);
    }

    private static File privateDirectory() {
        File parent = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File dir = new File(parent, UUID.randomUUID().toString());
        assertTrue(dir.mkdir());
        return dir;
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                archive.putNextEntry(new ZipEntry(namesAndContents[i]));
                archive.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
