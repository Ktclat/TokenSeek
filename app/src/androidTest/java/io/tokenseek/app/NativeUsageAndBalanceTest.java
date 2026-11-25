package io.tokenseek.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class NativeUsageAndBalanceTest {
    private static JSONObject usage(int input, int read, int output) throws JSONException {
        return new JSONObject().put("input_tokens", input).put("cache_creation_input_tokens", 2)
                .put("cache_read_input_tokens", read).put("output_tokens", output);
    }

    private static JSONObject run(String kind, JSONObject usage) throws JSONException {
        return new JSONObject().put("kind", kind).put("usage", usage);
    }

    private static JSONObject reply(String id) throws JSONException {
        return new JSONObject().put("id", id).put("role", "assistant").put("status", "done");
    }

    @Test public void separatesMissingAndLegacySnapshotsFromConfirmedRequestsAcrossBranches() throws Exception {
        JSONObject complete = reply("a").put("usage", usage(8, 4, 1)).put("usageRuns",
                new JSONArray().put(run("final", usage(8, 4, 1))).put(run("missing", null)));
        JSONObject alternate = reply("b").put("usageRuns", new JSONArray().put(run("final",
                new JSONObject().put("prompt_tokens", 50).put("completion_tokens", 3))));
        JSONObject legacy = reply("c").put("usage", usage(12, 5, 0));
        JSONObject upload = reply("d").put("error", new JSONObject().put("kind", "upload"));
        JSONObject session = new JSONObject().put("rootMessageId", "b")
                .put("messages", new JSONArray().put(complete).put(alternate).put(legacy).put(upload));
        NativeUsageRecords.Totals totals = new NativeUsageRecords.Totals();
        totals.addMessages(session.getJSONArray("messages"));
        assertEquals(2, totals.confirmed);
        assertEquals(2, totals.incomplete);
        assertEquals(1, totals.snapshots);
        assertEquals(1, totals.missingCache);
        assertEquals("68", totals.total().toString());
        assertEquals("4", totals.cached.toString());
        assertEquals("19", totals.snapshotTokens.toString());

        JSONArray branch = NativeHistoryStore.visibleMessages(session);
        NativeUsageRecords.ContextSpace context = NativeUsageRecords.context(branch, "deepseek-flash", "中a", false, false);
        assertEquals("53", context.previous.toString());
        assertEquals(Long.valueOf(1), context.draft);
        assertEquals(Long.valueOf(1048576L - 54), context.remaining);
        assertEquals(">99%", context.percent());
        assertEquals("—", NativeUsagePage.remainingLabel(session, "deepseek-flash", "中a", false, true));
        session.put("rootMessageId", "a");
        assertEquals("—", NativeUsagePage.remainingLabel(session, "deepseek-flash", "", false));
        assertNull(NativeUsageRecords.context(branch, "unknown-model", "", false, false).remaining);
    }

    @Test public void balancePreservesDistinctCurrenciesAndOfficialDecimalText() throws Exception {
        JSONObject row = new JSONObject().put("currency", "CNY").put("total_balance", "38.600000")
                .put("topped_up_balance", "31.400000").put("granted_balance", "7.200000");
        JSONObject usd = new JSONObject(row.toString()).put("currency", "USD").put("total_balance", "-0.0215");
        JSONObject body = new JSONObject().put("is_available", false).put("balance_infos", new JSONArray().put(row).put(usd));
        NativeBalanceRequest.Result result = NativeBalanceRequest.parse(stream(body));
        assertEquals("ready", result.status);
        assertEquals(2, result.balances.length());
        assertEquals("38.600000", result.balances.getJSONObject(0).getString("total"));
        assertEquals("-0.0215", result.balances.getJSONObject(1).getString("total"));
        row.put("topped_up_balance", JSONObject.NULL);
        try {
            NativeBalanceRequest.parse(stream(body));
            fail("Malformed balance response should not show a fabricated balance");
        } catch (JSONException expected) { }
    }

    private static ByteArrayInputStream stream(JSONObject data) {
        return new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8));
    }
}
