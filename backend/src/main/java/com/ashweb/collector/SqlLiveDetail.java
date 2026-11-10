package com.ashweb.collector;

import java.util.List;

/**
 * What a monitored database itself knows about one statement right now: execution plans and cumulative
 * statistics. Read on demand, not stored.
 *
 * @param notes plain sentences explaining anything that could not be shown (missing grants, aged-out cursor...)
 */
public record SqlLiveDetail(List<Plan> plans, List<StatGroup> stats, List<String> notes) {

    public record Plan(String planHashValue, long childNumber, List<PlanRow> rows) {
    }

    /** One plan step. Estimates are the optimizer's, not measurements. */
    public record PlanRow(int id, Integer parentId, int depth, String operation, String object, Long rows,
                          Long bytes, Long cost, Long timeSeconds, String accessPredicates,
                          String filterPredicates) {
    }

    /** Cumulative statistics, e.g. for one plan of the statement. */
    public record StatGroup(String label, Long executions, List<Stat> stats) {
    }

    /**
     * @param unit "count", "micros" (microseconds), "millis", "text" or "time" (ISO instant in {@code text})
     */
    public record Stat(String name, String unit, Double number, String text) {
        public static Stat count(String name, double value) {
            return new Stat(name, "count", value, null);
        }

        public static Stat micros(String name, double value) {
            return new Stat(name, "micros", value, null);
        }

        public static Stat millis(String name, double value) {
            return new Stat(name, "millis", value, null);
        }

        public static Stat text(String name, String value) {
            return new Stat(name, "text", null, value);
        }
    }
}
