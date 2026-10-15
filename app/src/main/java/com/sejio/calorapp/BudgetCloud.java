package com.sejio.calorapp;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * With the budget_cloud feature on, keeps one `budget_cycles` row per payday cycle (id = its first
 * day) so the web and agents can see the budget. Only totals leave the phone: income, fixed lines and
 * caps with what they expected and paid, free money and spending per category. Never movements,
 * merchants, accounts or credentials. Amounts are cents.
 */
final class BudgetCloud {
    /** Budget.LATE … Budget.MISSED, in order, as the backend names them. */
    private static final String[] STATUS = {"late", "soon", "partial", "upcoming", "reserved", "paid", "skipped", "missed"};
    /** The open cycle and the two before it: enough for the web, and closed cycles rarely change. */
    private static final int CYCLES = 3;

    private BudgetCloud() {}

    /** Writes the cycles whose summary changed. Called after Dinero recomputes its snapshot. */
    static void upload(Context context, Budget.Snapshot snapshot) {
        if (snapshot == null || snapshot.periods.isEmpty() || !Features.isOn(context, Features.BUDGET_CLOUD)) return;
        int written = 0;
        try {
            for (int index = Math.max(snapshot.first(), snapshot.last() - CYCLES + 1); index <= snapshot.last(); index++) {
                if (store(context, summary(Budget.cycle(snapshot, index)))) written++;
            }
            if (written > 0) PausaLog.i("budget", "Resumen de dinero subido: " + written + (written == 1 ? " ciclo" : " ciclos"));
        } catch (Exception error) {
            PausaLog.w("budget", "No se pudo preparar el resumen de dinero: " + error.getClass().getSimpleName());
        }
    }

    static Map<String, Object> summary(Budget.Cycle cycle) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("start", Ledger.iso(cycle.period.start));
        data.put("end", Ledger.iso(cycle.period.end - 1));
        data.put("current", cycle.current);
        data.put("income", cycle.income());
        data.put("fixed", cycle.fixed());
        data.put("saved", cycle.saved);
        data.put("savings_target", cycle.savingsTarget);
        data.put("free_budget", cycle.freeBudget());
        data.put("free_spent", cycle.freeSpent);
        data.put("available", cycle.available());
        data.put("daily_allowance", cycle.dailyAllowance());
        data.put("projection", cycle.projection());
        data.put("savings_at_close", cycle.savingsAtClose());
        data.put("unexpected", cycle.unexpected);
        data.put("covered", cycle.covered);
        data.put("cushion_left", cycle.cushionLeft() < 0 ? null : cycle.cushionLeft());
        JSONArray lines = new JSONArray();
        for (Budget.Line line : cycle.lines) {
            lines.put(new JSONObject()
                    .put("id", line.rule.id)
                    .put("name", line.rule.name)
                    .put("cap", line.rule.cap)
                    .put("expected", line.expected)
                    .put("paid", line.paid)
                    .put("logged", line.logged)
                    .put("status", line.status >= 0 && line.status < STATUS.length ? STATUS[line.status] : "upcoming")
                    .put("due", line.due > 0 ? Ledger.iso(line.due) : JSONObject.NULL)
                    .put("frequency", line.rule.frequency));
        }
        data.put("lines", lines.toString());
        JSONArray categories = new JSONArray();
        for (Budget.Slice slice : cycle.slices) {
            if (slice.cents <= 0) continue;
            categories.put(new JSONObject()
                    .put("key", slice.category == null ? "otros" : slice.category.id)
                    .put("label", slice.category == null ? "Otros" : slice.category.label)
                    .put("cents", slice.cents));
        }
        data.put("categories", categories.toString());
        return data;
    }

    /** Writes the row only when a value changed, so opening Dinero many times does not flood the sync. */
    private static boolean store(Context context, Map<String, Object> data) {
        String id = (String) data.get("start");
        Map<String, Object> current = PausaDb.get(context).row("budget_cycles", id);
        if (current != null && !Long.valueOf(1).equals(current.get("deleted"))) {
            boolean same = true;
            for (Map.Entry<String, Object> field : data.entrySet()) {
                if (!Objects.equals(normal(field.getValue()), normal(current.get(field.getKey())))) { same = false; break; }
            }
            if (same) return false;
        }
        data.put("computed_at", System.currentTimeMillis());
        PausaDb.get(context).put("budget_cycles", id, data, false);
        return true;
    }

    private static Object normal(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? 1L : 0L;
        return value instanceof Number ? (Object) ((Number) value).longValue() : value;
    }
}
