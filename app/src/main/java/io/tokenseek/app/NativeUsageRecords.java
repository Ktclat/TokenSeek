package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Usage recorded on assistant requests. Initial and older snapshots never contribute to final totals. */
final class NativeUsageRecords {
    private NativeUsageRecords() {}

    static Totals accountTotals(JSONArray sessions) {
        if (sessions == null) return null;
        Totals totals = new Totals();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null) totals.addMessages(session.optJSONArray("messages"));
        }
        return totals;
    }

    static final class Totals {
        int confirmed;
        int incomplete;
        int snapshots;
        int missingCache;
        BigInteger input = BigInteger.ZERO;
        BigInteger output = BigInteger.ZERO;
        BigInteger cached = BigInteger.ZERO;
        BigInteger snapshotTokens = BigInteger.ZERO;

        BigInteger total() { return input.add(output); }

        void addMessages(JSONArray messages) {
            if (messages == null) return;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null || !"assistant".equals(message.optString("role"))) continue;
                JSONArray runs = message.optJSONArray("usageRuns");
                if (runs != null) {
                    for (int j = 0; j < runs.length(); j++) {
                        JSONObject run = runs.optJSONObject(j);
                        add(run == null ? "missing" : run.optString("kind"), run == null ? null : run.optJSONObject("usage"));
                    }
                } else {
                    JSONObject error = message.optJSONObject("error");
                    if (error == null || !"upload".equals(error.optString("kind")))
                        add("legacy", message.optJSONObject("usage"));
                }
            }
        }

        private void add(String kind, JSONObject usage) {
            Counts counts = counts(usage);
            if ("final".equals(kind) && counts != null) {
                confirmed++;
                input = input.add(counts.input);
                output = output.add(counts.output);
                if (counts.cached != null) cached = cached.add(counts.cached);
                else missingCache++;
            } else {
                incomplete++;
                if (counts != null && ("initial".equals(kind) || "legacy".equals(kind))) {
                    snapshots++;
                    snapshotTokens = snapshotTokens.add(counts.input).add(counts.output);
                }
            }
        }
    }

    static final class ContextSpace {
        final int limit;
        BigInteger previous;
        Long draft;
        Long remaining;
        String reason;

        ContextSpace(int limit) { this.limit = limit; }

        String percent() {
            if (remaining == null) return "—";
            if (remaining > 0 && remaining * 100 < limit) return "<1%";
            if (remaining < limit && remaining * 100 > limit * 99L) return ">99%";
            return Math.round(remaining * 100.0 / limit) + "%";
        }
    }

    // These two model families share the documented 1M-token context window.
    static ContextSpace context(JSONArray branch, String model, String text, boolean streaming, boolean hasAttachment) {
        int limit = ("deepseek-flash".equals(model) || "deepseek-v4-flash".equals(model) || "deepseek-v4-pro".equals(model))
                ? 1_048_576 : 0;
        ContextSpace result = new ContextSpace(limit);
        result.previous = previous(branch);
        result.draft = draft(text);
        if (streaming) result.reason = "streaming";
        else if (hasAttachment) result.reason = "attachment";
        else if (limit == 0) result.reason = "model";
        else if (result.previous == null) result.reason = "history";
        else if (result.draft == null) result.reason = "draft";
        else result.remaining = BigInteger.valueOf(limit).subtract(result.previous).subtract(BigInteger.valueOf(result.draft))
                    .max(BigInteger.ZERO).longValue();
        return result;
    }

    private static BigInteger previous(JSONArray branch) {
        if (branch.length() == 0) return BigInteger.ZERO;
        JSONObject last = branch.optJSONObject(branch.length() - 1);
        if (last == null || !"assistant".equals(last.optString("role"))) return null;
        String status = last.optString("status");
        if (!"done".equals(status) && !"stopped".equals(status)) return null;
        JSONArray runs = last.optJSONArray("usageRuns");
        if (runs == null || runs.length() == 0) return null;
        JSONObject run = runs.optJSONObject(runs.length() - 1);
        if (run == null || !"final".equals(run.optString("kind"))) return null;
        Counts value = counts(run.optJSONObject("usage"));
        return value == null ? null : value.input.add(value.output);
    }

    /** Approximate Chinese and English token ratios from the provider's Token Usage guide. */
    private static Long draft(String text) {
        if (text == null) return 0L;
        String content = text.trim();
        long tenths = 0;
        for (int i = 0; i < content.length();) {
            int c = content.codePointAt(i);
            i += Character.charCount(c);
            if (c < 128) tenths += 3;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || (c >= 0x3000 && c <= 0x303f)
                    || (c >= 0xff01 && c <= 0xff60)) tenths += 6;
            else return null;
        }
        return (tenths + 9) / 10;
    }

    private static final class Counts {
        final BigInteger input, output, cached;
        Counts(BigInteger input, BigInteger output, BigInteger cached) {
            this.input = input;
            this.output = output;
            this.cached = cached;
        }
    }

    private static Counts counts(JSONObject usage) {
        if (usage == null) return null;
        if (usage.has("prompt_tokens")) {
            BigInteger input = integer(usage.opt("prompt_tokens"));
            BigInteger output = integer(usage.opt("completion_tokens"));
            BigInteger cached = integer(usage.opt("prompt_cache_hit_tokens"));
            if (input == null || output == null) return null;
            return new Counts(input, output, cached != null && cached.compareTo(input) <= 0 ? cached : null);
        }
        BigInteger input = integer(usage.opt("input_tokens"));
        BigInteger output = integer(usage.opt("output_tokens"));
        BigInteger read = integer(usage.opt("cache_read_input_tokens"));
        BigInteger write = integer(usage.opt("cache_creation_input_tokens"));
        return input == null || output == null || read == null || write == null ? null
                : new Counts(input.add(read).add(write), output, read);
    }

    private static BigInteger integer(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            BigDecimal decimal = new BigDecimal(value.toString());
            return decimal.signum() < 0 || decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                    ? null : decimal.toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }
}
