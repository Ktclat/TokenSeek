package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One exported platform report. Amounts are counts; only cost.csv contributes monetary totals. */
final class NativePlatformUsageReport {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;

    private static final Pattern DECIMAL =
            Pattern.compile(
                    "[+]?(?:[0-9]{1,40}(?:\\.[0-9]{0,40})?|\\.[0-9]{1,40})(?:[eE][-+]?[0-9]{1,2})?");
    private static final Pattern SECRET = Pattern.compile("(?i)sk-[a-z0-9_.*…-]+");
    private static final Pattern OFFICIAL_MASK =
            Pattern.compile("(?i)sk-([a-z0-9]{0,5})[*…]+([a-z0-9]{0,4})");
    private static final Pattern AMOUNT_FILE =
            Pattern.compile("amount(?:-[a-z0-9_-]+)?\\.csv", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST_FILE =
            Pattern.compile("cost(?:-[a-z0-9_-]+)?\\.csv", Pattern.CASE_INSENSITIVE);

    private NativePlatformUsageReport() {}

    /** Caller owns the stream. No key, account ID or source file is saved by this parser. */
    static JSONObject parse(InputStream source) throws IOException {
        return parse(source, null);
    }

    static JSONObject parse(InputStream source, File cacheDir) throws IOException {
        if (source == null) throw invalid();
        BufferedInputStream input = new BufferedInputStream(source);
        input.mark(4);
        int first = input.read(), second = input.read();
        input.reset();
        Data data = new Data();
        try {
            if (first == 'P' && second == 'K') readZip(input, data, cacheDir);
            else readCsv(new Limited(input, MAX_BYTES), false, data);
            return data.finish();
        } catch (JSONException | DateTimeException | NumberFormatException malformed) {
            throw new IOException("Invalid usage report", malformed);
        }
    }

    private static void readZip(InputStream input, Data data, File cacheDir) throws IOException {
        // ZipFile uses the central directory, including ZIPs with stored entries and data
        // descriptors.
        File scratch = File.createTempFile("usage-report-", ".zip", cacheDir);
        try {
            try (OutputStream destination = new FileOutputStream(scratch)) {
                InputStream bounded = new Limited(input, MAX_BYTES);
                byte[] bytes = new byte[32 * 1024];
                int n;
                while ((n = bounded.read(bytes)) != -1) destination.write(bytes, 0, n);
            }
            try (ZipFile zip = new ZipFile(scratch)) {
                if (zip.size() > 32) throw invalid();
                ZipEntry amount = null, cost = null;
                long size = 0;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String path = entry.getName();
                    if (path.startsWith("/")
                            || path.contains("\\")
                            || Arrays.asList(path.split("/")).contains("..")) throw invalid();
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    if (path.startsWith("__MACOSX/") || ".DS_Store".equals(name)) continue;
                    if (entry.getSize() < 0 || entry.getSize() > MAX_EXPANDED - size)
                        throw invalid();
                    size += entry.getSize();
                    if (AMOUNT_FILE.matcher(name).matches() && amount == null) amount = entry;
                    else if (COST_FILE.matcher(name).matches() && cost == null) cost = entry;
                    else throw invalid();
                }
                if (amount == null) throw invalid();
                if (cost != null && !suffix(amount, "amount").equals(suffix(cost, "cost")))
                    throw invalid();
                csvEntry(zip, amount, false, data);
                if (cost != null) csvEntry(zip, cost, true, data);
            }
        } finally {
            if (!scratch.delete()) scratch.deleteOnExit();
        }
    }

    private static String suffix(ZipEntry entry, String prefix) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        return name.substring(name.lastIndexOf('/') + 1 + prefix.length());
    }

    private static void csvEntry(ZipFile zip, ZipEntry entry, boolean cost, Data data)
            throws IOException {
        try (InputStream stream = zip.getInputStream(entry)) {
            CheckedInputStream checked = new CheckedInputStream(stream, new CRC32());
            Limited bounded = new Limited(checked, entry.getSize());
            readCsv(bounded, cost, data);
            if (bounded.remaining != 0 || checked.getChecksum().getValue() != entry.getCrc())
                throw invalid();
        }
    }

    private static final class Limited extends FilterInputStream {
        long remaining;

        Limited(InputStream source, long remaining) {
            super(source);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result >= 0 && --remaining < 0) throw invalid();
            return result;
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            int count = super.read(data, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw invalid();
            return count;
        }
    }

    /** Strict CSV rows, including quoted commas, doubled quotes and quoted newlines. */
    private static final class Csv {
        final PushbackReader reader;

        Csv(InputStream input) throws IOException {
            reader =
                    new PushbackReader(
                            new BufferedReader(
                                    new InputStreamReader(
                                            input,
                                            StandardCharsets.UTF_8
                                                    .newDecoder()
                                                    .onMalformedInput(CodingErrorAction.REPORT)
                                                    .onUnmappableCharacter(
                                                            CodingErrorAction.REPORT))));
            int first = reader.read();
            if (first >= 0 && first != 0xfeff) reader.unread(first);
        }

        List<String> next() throws IOException {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false, closed = false, started = false;
            for (; ; ) {
                int c = reader.read();
                if (c < 0 && quoted) throw invalid();
                if (c < 0 && !started) return null;
                started = true;
                if (quoted) {
                    if (c == '"') {
                        int look = reader.read();
                        if (look == '"') cell.append('"');
                        else {
                            quoted = false;
                            closed = true;
                            if (look >= 0) reader.unread(look);
                        }
                    } else cell.append((char) c);
                } else if (c == ',' || c == '\r' || c == '\n' || c < 0) {
                    cells.add(cell.toString().trim());
                    if (cells.size() > 64) throw invalid();
                    if (c != ',') {
                        if (c == '\r') {
                            int look = reader.read();
                            if (look >= 0 && look != '\n') reader.unread(look);
                        }
                        return cells;
                    }
                    cell.setLength(0);
                    closed = false;
                } else if (c == '"' && cell.length() == 0 && !closed) quoted = true;
                else if (closed || c == '"' || c == 0) throw invalid();
                else cell.append((char) c);
                if (cell.length() > 4096) throw invalid();
            }
        }
    }

    private static void readCsv(InputStream input, boolean cost, Data data) throws IOException {
        Csv csv = new Csv(input);
        List<String> header = csv.next();
        if (header == null) throw invalid();
        Map<String, Integer> fields = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String field = header.get(i);
            if (field.isEmpty() || fields.put(field, i) != null) throw invalid();
        }
        if (!fields.keySet()
                .containsAll(
                        cost
                                ? Arrays.asList("model", "cost", "currency")
                                : Arrays.asList(
                                        "model",
                                        "api_key",
                                        "api_key_name",
                                        "type",
                                        "price",
                                        "amount"))) throw invalid();
        boolean modern = fields.containsKey("start_time_iso") || fields.containsKey("end_time_iso");
        if (modern
                ? !fields.containsKey("start_time_iso") || !fields.containsKey("end_time_iso")
                : !fields.containsKey("utc_date")) throw invalid();
        for (List<String> line; (line = csv.next()) != null; ) {
            if (line.size() == 1 && line.get(0).isEmpty()) continue;
            if (line.size() != header.size() || ++data.rows > MAX_ROWS) throw invalid();
            Row row = new Row(fields, line);
            String owner = row.get("user_id");
            if (!owner.isEmpty()) {
                if (data.owner != null && !data.owner.equals(owner)) throw invalid();
                data.owner = owner;
            }
            Instant start, end;
            if (modern) {
                start = OffsetDateTime.parse(row.get("start_time_iso")).toInstant();
                end = OffsetDateTime.parse(row.get("end_time_iso")).toInstant();
            } else {
                LocalDate date = LocalDate.parse(row.get("utc_date"));
                start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
                end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (!end.isAfter(start)) throw invalid();
            if (cost) {
                if (data.amountRows == 0) data.addPeriod(start, end);
                else if (!end.equals(data.periods.get(start))) throw invalid();
                data.cost(row, start);
            } else {
                data.amountRows++;
                data.addPeriod(start, end);
                data.amount(row, start);
            }
        }
    }

    private static final class Row {
        final Map<String, Integer> fields;
        final List<String> line;

        Row(Map<String, Integer> fields, List<String> line) {
            this.fields = fields;
            this.line = line;
        }

        String get(String key) {
            Integer at = fields.get(key);
            return at == null ? "" : line.get(at);
        }

        boolean supportedUnit(String key, String... permitted) {
            String unit = get(key).toLowerCase(Locale.ROOT);
            return unit.isEmpty() || Arrays.asList(permitted).contains(unit);
        }
    }

    private static final class Data {
        final TreeMap<Instant, Instant> periods = new TreeMap<>();
        final Set<List<String>> counted = new HashSet<>();
        final Map<List<String>, Set<String>> costWallets = new HashMap<>();
        final Map<List<String>, KeyTotals> keys = new LinkedHashMap<>();
        final Map<String, Counts> models = new TreeMap<>();
        final Map<String, BigDecimal> costs = new TreeMap<>();
        final Counts totals = new Counts();
        int rows, amountRows, skippedRows, accepted;
        String owner;

        void addPeriod(Instant start, Instant end) throws IOException {
            Map.Entry<Instant, Instant> previous = periods.floorEntry(start);
            if (previous != null
                    && previous.getKey().equals(start)
                    && previous.getValue().equals(end)) return;
            Map.Entry<Instant, Instant> next = periods.ceilingEntry(start);
            if (previous != null && previous.getValue().isAfter(start)
                    || next != null && next.getKey().isBefore(end)) throw invalid();
            periods.put(start, end);
        }

        void amount(Row row, Instant start) throws IOException {
            String type = row.get("type"), model = row.get("model"), name = row.get("name");
            boolean token =
                    type.equals("output_tokens")
                            || type.equals("input_cache_hit_tokens")
                            || type.equals("input_cache_miss_tokens");
            boolean request = type.equals("request_count"), tool = type.equals("function_call");
            if (!token && !request && !tool) {
                skippedRows++;
                return;
            }
            String[] units =
                    token
                            ? new String[] {"token", "tokens"}
                            : tool
                                    ? new String[] {"call", "calls", "request", "requests"}
                                    : new String[] {"request", "requests"};
            if (!row.supportedUnit("unit", units)
                    || !row.supportedUnit("amount_unit", units)
                    || model.isEmpty() && !tool) {
                skippedRows++;
                return;
            }
            String raw = row.get("amount"), price = row.get("price");
            BigDecimal count = number(raw).stripTrailingZeros();
            if (count.scale() > 0 || count.precision() - count.scale() > 30) throw invalid();
            String key = row.get("api_key"), label = row.get("api_key_name");
            if (label.length() > 256
                    || model.length() > 256
                    || key.length() > 256
                    || name.length() > 256) throw invalid();
            // The same dimension may have separate price bands. Reject a repeated band or interval.
            String band = price.isEmpty() ? "" : number(price).stripTrailingZeros().toPlainString();
            if (!counted.add(
                    Arrays.asList(
                            "amount",
                            start.toString(),
                            model,
                            tool ? name : "",
                            key,
                            label,
                            type,
                            band))) throw invalid();
            BigInteger n = count.toBigIntegerExact();
            accepted++;
            totals.add(type, n);
            List<String> identity = Arrays.asList(key, label);
            KeyTotals keyTotal = keys.get(identity);
            if (keyTotal == null) {
                if (keys.size() >= 4096) throw invalid();
                keyTotal = new KeyTotals(label.equals(key) ? "" : display(label), masked(key));
                keys.put(identity, keyTotal);
            }
            keyTotal.totals.add(type, n);
            if (!model.isEmpty()) {
                if (!models.containsKey(model) && models.size() >= 1024) throw invalid();
                models.computeIfAbsent(model, ignored -> new Counts()).add(type, n);
            }
        }

        void cost(Row row, Instant start) throws IOException {
            String currency = row.get("currency"), raw = row.get("cost");
            try {
                Currency.getInstance(currency);
            } catch (IllegalArgumentException unknown) {
                skippedRows++;
                return;
            }
            String wallet = row.get("wallet_type");
            if (!wallet.isEmpty() && !wallet.equals("Paid") && !wallet.equals("Granted")
                    || !currency.matches("[A-Z]{3}")
                    || "XXX".equals(currency)
                    || "XTS".equals(currency)
                    || !row.supportedUnit("unit", currency.toLowerCase(Locale.ROOT))
                    || !row.supportedUnit("cost_unit", currency.toLowerCase(Locale.ROOT))) {
                skippedRows++;
                return;
            }
            BigDecimal amount = number(raw);
            List<String> dimension =
                    Arrays.asList(start.toString(), row.get("model"), row.get("name"), currency);
            Set<String> wallets =
                    costWallets.computeIfAbsent(dimension, ignored -> new HashSet<>());
            // A row without a wallet breakdown and rows split by wallet could represent the same
            // cost.
            if (!wallets.add(wallet) || wallets.size() > 1 && wallets.contains("")) throw invalid();
            costs.merge(currency, amount, BigDecimal::add);
        }

        JSONObject finish() throws JSONException {
            JSONArray keyRows = new JSONArray(),
                    modelRows = new JSONArray(),
                    money = new JSONArray();
            for (KeyTotals key : keys.values())
                keyRows.put(
                        new JSONObject()
                                .put("name", key.name)
                                .put("masked", key.masked)
                                .put("totals", key.totals.json()));
            for (Map.Entry<String, Counts> model : models.entrySet())
                modelRows.put(
                        new JSONObject()
                                .put("name", display(model.getKey()))
                                .put("totals", model.getValue().json()));
            for (Map.Entry<String, BigDecimal> cost : costs.entrySet())
                money.put(
                        new JSONObject()
                                .put("currency", cost.getKey())
                                .put(
                                        "amount",
                                        cost.getValue().stripTrailingZeros().toPlainString()));
            JSONObject report =
                    new JSONObject()
                            .put("version", 1)
                            .put("rowCount", rows)
                            .put("amountRows", amountRows)
                            .put("recognizedRows", accepted)
                            .put("periodCount", periods.size())
                            .put("totals", totals.json())
                            .put("keys", keyRows)
                            .put("models", modelRows)
                            .put("costs", money)
                            .put("skippedRows", skippedRows);
            if (!periods.isEmpty())
                report.put("startUtc", periods.firstKey().toString())
                        .put("endUtc", periods.lastEntry().getValue().toString());
            return report;
        }
    }

    private static final class KeyTotals {
        final String name, masked;
        final Counts totals = new Counts();

        KeyTotals(String name, String masked) {
            this.name = name;
            this.masked = masked;
        }
    }

    private static final class Counts {
        final Map<String, BigInteger> values = new HashMap<>();

        void add(String type, BigInteger value) {
            switch (type) {
                case "request_count":
                    increment("requests", value);
                    break;
                case "function_call":
                    increment("tools", value);
                    break;
                case "output_tokens":
                    increment("output", value);
                    increment("tokens", value);
                    break;
                case "input_cache_hit_tokens":
                    increment("cached", value);
                    increment("input", value);
                    increment("tokens", value);
                    break;
                case "input_cache_miss_tokens":
                    increment("input", value);
                    increment("tokens", value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown usage type");
            }
        }

        private void increment(String name, BigInteger n) {
            values.merge(name, n, BigInteger::add);
        }

        JSONObject json() throws JSONException {
            JSONObject result = new JSONObject();
            for (Map.Entry<String, BigInteger> entry : values.entrySet())
                result.put(entry.getKey(), entry.getValue().toString());
            return result;
        }
    }

    private static BigDecimal number(String raw) throws IOException {
        if (!DECIMAL.matcher(raw).matches()) throw invalid();
        BigDecimal value = new BigDecimal(raw);
        if (value.scale() > 80 || value.precision() - value.scale() > 40) throw invalid();
        return value;
    }

    private static String display(String label) {
        return SECRET.matcher(label.replaceAll("[\\p{Cc}\\p{Cf}]", " ")).replaceAll("••••");
    }

    private static String masked(String value) {
        if (value.isEmpty()) return "";
        java.util.regex.Matcher official = OFFICIAL_MASK.matcher(value);
        if (official.matches()) return "sk-" + official.group(1) + "…" + official.group(2);
        if (value.length() < 8 || !value.matches("[A-Za-z0-9._-]+")) return "••••";
        return "•••• " + value.substring(value.length() - 4);
    }

    private static IOException invalid() {
        return new IOException("Invalid or unsupported platform usage report");
    }
}
