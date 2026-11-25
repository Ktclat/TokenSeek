package io.tokenseek.app;

import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.text.DateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Locale;

/** Native report overview and report picker, with bounded view creation for large exports. */
final class NativePlatformUsagePage extends NativeUsagePage {
    interface Actions {
        void pick();

        void select(String id);

        void local();
    }

    private static final int PAGE_SIZE = 8;
    private final Actions actions;
    private final String account;
    private final boolean english;
    private NativePlatformUsageStore.Snapshot state;
    private boolean byModel, library, busy = true;
    private String notice = "";
    private int page;

    NativePlatformUsagePage(
            Context context,
            boolean english,
            boolean dark,
            int scale,
            String account,
            Runnable back,
            Actions actions) {
        super(context, english, dark, scale, back);
        this.english = english;
        this.account = account;
        this.actions = actions;
        draw();
    }

    void loading(String message) {
        notice = message;
        busy = true;
        draw();
    }

    void show(NativePlatformUsageStore.Snapshot snapshot, String message) {
        if (snapshot != null) {
            if (state == null || !snapshot.selected.equals(state.selected)) page = 0;
            state = snapshot;
        }
        notice = message;
        busy = false;
        draw();
    }

    boolean backToOverview() {
        if (!library) return false;
        library = false;
        page = 0;
        draw();
        return true;
    }

    private void draw() {
        reset(library ? t("已导入的报表", "Imported reports") : t("开放平台用量", "Platform usage"));
        if (library) {
            showLibrary();
            return;
        }
        JSONObject report = state == null ? null : state.report;
        LinearLayout summary = card();
        addHeading(
                summary, t("本机资料", "Device profile") + (account.isEmpty() ? "" : " · " + account));
        if (report != null) {
            metric(summary, t("记录起止时间", "Record date range"), interval(report));
            note(
                    summary,
                    t(
                            "合计仅含本次导出所选时段和密钥的数据。",
                            "Totals reflect the dates and keys included in this export."));
        } else if (!busy) {
            note(
                    summary,
                    t(
                            "从 DeepSeek 开放平台导出用量 ZIP 或用量明细 CSV，然后选择文件。报表保存在当前账户。",
                            "Choose a usage ZIP or usage-amount CSV exported from DeepSeek"
                                    + " Platform. Reports are saved in this profile."));
        }
        if (!notice.isEmpty()) note(summary, notice);
        else if (busy) note(summary, t("正在读取报表…", "Reading reports…"));
        action(
                summary,
                report == null
                        ? t("导入官方用量报表", "Import platform usage report")
                        : t("导入另一份报表", "Import another report"),
                true,
                actions::pick);
        if (state != null && state.library.length() > 1) {
            action(
                    summary,
                    t("切换报表", "Switch report"),
                    false,
                    () -> {
                        library = true;
                        page = 0;
                        draw();
                    });
        }
        if (report != null) {
            LinearLayout totals = card();
            addHeading(totals, t("本报表合计", "Report totals"));
            if (report.optInt("rowCount", -1) == 0) {
                note(
                        totals,
                        t(
                                "导出的文件只有表头，未列出用量或费用。",
                                "The export contains a header and no usage or cost records."));
            } else if (report.optInt("recognizedRows", -1) == 0) {
                note(
                        totals,
                        t("报表没有可汇总的用量明细。", "The report contains no recognized usage amounts."));
            }
            counters(totals, report.optJSONObject("totals"));
            JSONArray costs = report.optJSONArray("costs");
            for (int i = 0; costs != null && i < costs.length(); i++) {
                JSONObject amount = costs.optJSONObject(i);
                if (amount != null)
                    metric(
                            totals,
                            t("费用", "Cost"),
                            amount.optString("currency") + " " + amount.optString("amount"));
            }
            int skipped = report.optInt("skippedRows", 0);
            if (skipped > 0)
                note(
                        totals,
                        t("未纳入合计的未识别记录数", "Unrecognized rows excluded") + " · " + format(skipped));
            breakdown(report);
        }
        action(card(), t("本机对话用量", "On-device chat usage"), false, actions::local);
    }

    private void breakdown(JSONObject report) {
        LinearLayout details = card();
        addHeading(details, t("用量明细", "Usage breakdown"));
        LinearLayout tabs = new LinearLayout(getContext());
        details.addView(tabs, gap(8));
        selectTab(tabs, t("按密钥", "By key"), false);
        selectTab(tabs, t("按模型", "By model"), true);
        JSONArray rows = report.optJSONArray(byModel ? "models" : "keys");
        if (rows == null || rows.length() == 0) {
            note(
                    details,
                    byModel
                            ? t("该报表未提供模型明细。", "This report has no model breakdown.")
                            : t("该报表未提供密钥明细。", "This report has no key breakdown."));
            return;
        }
        note(
                details,
                byModel
                        ? t("报表中的模型", "Models in report") + " · " + format(rows.length())
                        : t("报表中的密钥", "Keys in report") + " · " + format(rows.length()));
        int start = first(rows.length());
        for (int i = start; i < Math.min(start + PAGE_SIZE, rows.length()); i++) {
            JSONObject entry = rows.optJSONObject(i);
            if (entry == null) continue;
            LinearLayout item = card();
            String name = entry.optString("name", "");
            addHeading(
                    item,
                    !name.isEmpty()
                            ? name
                            : byModel
                                    ? t("未标明模型", "Unspecified model")
                                    : t("未命名密钥", "Unnamed key"));
            String masked = entry.optString("masked", "");
            if (!masked.isEmpty()) note(item, masked);
            counters(item, entry.optJSONObject("totals"));
        }
        paginate(rows.length());
    }

    private void counters(LinearLayout target, JSONObject totals) {
        if (totals == null) return;
        count(target, totals, "tokens", t("总词元", "Total tokens"));
        count(target, totals, "requests", t("请求次数", "Requests"));
        count(target, totals, "input", t("输入词元", "Input tokens"));
        count(target, totals, "cached", t("缓存命中词元", "Cached input tokens"));
        count(target, totals, "output", t("输出词元", "Output tokens"));
        count(target, totals, "tools", t("工具调用次数", "Tool calls"));
    }

    private void count(LinearLayout target, JSONObject totals, String key, String name) {
        if (totals.isNull(key)) return;
        metric(target, name, format(new BigInteger(totals.optString(key))));
    }

    private void selectTab(LinearLayout tabs, String text, boolean model) {
        Button tab =
                button(
                        text,
                        byModel == model,
                        v -> {
                            byModel = model;
                            page = 0;
                            draw();
                        });
        tab.setSelected(byModel == model);
        tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void showLibrary() {
        if (state == null) return;
        LinearLayout rows = card();
        int count = state.library.length(), start = first(count);
        for (int i = start; i < Math.min(start + PAGE_SIZE, count); i++) {
            JSONObject record = state.library.optJSONObject(i);
            if (record == null) continue;
            String id = record.optString("id");
            String label = interval(record);
            DateFormat date =
                    DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT,
                            english ? Locale.US : Locale.SIMPLIFIED_CHINESE);
            label +=
                    "\n"
                            + t("导入于 ", "Imported ")
                            + date.format(new Date(record.optLong("importedAt")))
                            + " · "
                            + id.substring(0, 8);
            boolean selected = id.equals(state.selected);
            if (selected) label += t(" · 当前报表", " · Selected");
            action(
                    rows,
                    label,
                    selected,
                    () -> {
                        library = false;
                        page = 0;
                        actions.select(id);
                    });
        }
        paginate(count);
    }

    private int first(int count) {
        page = Math.max(0, Math.min(page, (count - 1) / PAGE_SIZE));
        return page * PAGE_SIZE;
    }

    private void paginate(int count) {
        if (count <= PAGE_SIZE) return;
        LinearLayout controls = card();
        metric(
                controls,
                t("页码", "Page"),
                (page + 1) + " / " + ((count + PAGE_SIZE - 1) / PAGE_SIZE));
        if (page > 0)
            action(
                    controls,
                    t("上一页", "Previous page"),
                    false,
                    () -> {
                        page--;
                        draw();
                        scrollTop();
                    });
        if ((page + 1) * PAGE_SIZE < count)
            action(
                    controls,
                    t("下一页", "Next page"),
                    false,
                    () -> {
                        page++;
                        draw();
                        scrollTop();
                    });
    }

    private void action(LinearLayout parent, String text, boolean emphasized, Runnable listener) {
        Button action = button(text, emphasized, v -> listener.run());
        action.setEnabled(!busy);
        if (busy) action.setAlpha(0.55f);
        parent.addView(action, gap(8));
    }

    private String interval(JSONObject record) {
        String start = record.optString("startUtc"), end = record.optString("endUtc");
        if (start.isEmpty() || end.isEmpty()) return t("报表未提供日期", "Dates unavailable");
        try {
            Instant a = Instant.parse(start), b = Instant.parse(end);
            if (a.getEpochSecond() % 86_400L == 0
                    && a.getNano() == 0
                    && b.getEpochSecond() % 86_400L == 0
                    && b.getNano() == 0
                    && b.isAfter(a)) {
                String first = a.atOffset(ZoneOffset.UTC).toLocalDate().toString();
                String last = b.minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate().toString();
                return (first.equals(last) ? first : first + " – " + last) + " UTC";
            }
        } catch (DateTimeException invalid) {
            /* Keep the ISO values supplied by the parser. */
        }
        return start + " – " + end + t("（不含结束时刻）", " (end exclusive)");
    }
}
