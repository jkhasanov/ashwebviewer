package com.ashweb.activity;

import com.ashweb.storage.PartitionManager;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Answers the questions the screens ask. Activity is always expressed as average active sessions:
 * samples are taken once a second, so (samples in a period) / (seconds in the period) is the average
 * number of sessions that were active during it.
 */
@Service
public class ActivityService {

    /** Chart resolution: aim for about this many points whatever the range. */
    private static final int TARGET_POINTS = 240;
    private static final long[] BUCKET_SECONDS = {1, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200,
            14400, 21600, 43200, 86400};
    /** Ranges longer than this are answered from the per-minute rollup. */
    private static final Duration RAW_MAX_RANGE = Duration.ofHours(3);
    /** The newest seconds are still being collected; charts stop short of them to avoid a false dip. */
    private static final Duration LIVE_LAG = Duration.ofSeconds(10);

    public record Series(String name, double[] values) {
    }

    public record TimeSeries(Instant from, Instant to, long bucketSeconds, String groupedBy, List<Instant> times,
                             List<Series> series) {
    }

    public record TopRow(String sqlId, String sqlOpname, String sqlText, Long sessionId, Long sessionSerial,
                         String userName, String program, long samples, double percent,
                         double avgActiveSessions, Map<String, Long> byWaitClass) {
    }

    public record TopResult(Instant from, Instant to, long totalSamples, List<TopRow> rows) {
    }

    public record OverviewRow(long targetId, double avgActiveSessions, Map<String, Double> byWaitClass,
                              double[] spark) {
    }

    public record SessionInfo(long sessionId, long sessionSerial, String userName, String program,
                              Instant firstSeen, Instant lastSeen, long samples, Map<String, String> attributes) {
    }

    /** Narrows a question to one wait class, statement or session. Null members do not narrow. */
    public record Filter(String waitClass, String sqlId, Long sessionId, Long sessionSerial) {

        public static final Filter NONE = new Filter(null, null, null, null);

        public static Filter waitClass(String waitClass) {
            return new Filter(waitClass, null, null, null);
        }

        public static Filter sql(String sqlId) {
            return new Filter(null, sqlId, null, null);
        }

        public static Filter session(long sessionId, long sessionSerial) {
            return new Filter(null, null, sessionId, sessionSerial);
        }

        boolean hasWaitClass() {
            return waitClass != null && !waitClass.isBlank();
        }

        /** The conditions to append to a where clause; their values are added to params. */
        String conditions(MapSqlParameterSource params) {
            StringBuilder sql = new StringBuilder();
            if (hasWaitClass()) {
                sql.append(" and wait_class = :waitClass");
                params.addValue("waitClass", waitClass);
            }
            if (sqlId != null && !sqlId.isBlank()) {
                sql.append(" and sql_id = :sqlId");
                params.addValue("sqlId", sqlId);
            }
            if (sessionId != null) {
                sql.append(" and session_id = :sessionId");
                params.addValue("sessionId", sessionId);
            }
            if (sessionSerial != null) {
                sql.append(" and session_serial = :sessionSerial");
                params.addValue("sessionSerial", sessionSerial);
            }
            return sql.toString();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PartitionManager partitions;

    public ActivityService(NamedParameterJdbcTemplate jdbc, PartitionManager partitions) {
        this.jdbc = jdbc;
        this.partitions = partitions;
    }

    /** The table and column names to read for a range: raw samples when recent and short, else the rollup. */
    private record Source(String table, String timeColumn, String countExpr, boolean raw) {
        static final Source RAW = new Source("ash_sample", "sample_time", "count(*)", true);
        static final Source ROLLUP = new Source("ash_rollup_1m", "bucket", "sum(samples)", false);
    }

    private Source sourceFor(long targetId, Instant from, Instant to) {
        boolean raw = Duration.between(from, to).compareTo(RAW_MAX_RANGE) <= 0
                && !from.isBefore(partitions.rawCutoff(targetId));
        return raw ? Source.RAW : Source.ROLLUP;
    }

    private static Instant floor(Instant t, long seconds) {
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), seconds) * seconds);
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }

    /**
     * Average active sessions over time, stacked by wait class, or by wait event within one wait class.
     */
    public TimeSeries timeSeries(long targetId, Instant from, Instant to, Filter filter) {
        Instant liveEdge = Instant.now().minus(LIVE_LAG);
        if (to.isAfter(liveEdge)) {
            to = liveEdge;
        }
        if (!from.isBefore(to)) {
            from = to.minusSeconds(60);
        }
        Source source = sourceFor(targetId, from, to);
        long wanted = Math.max(1, Duration.between(from, to).getSeconds() / TARGET_POINTS);
        long bucket = BUCKET_SECONDS[BUCKET_SECONDS.length - 1];
        for (long candidate : BUCKET_SECONDS) {
            if (candidate >= wanted) {
                bucket = candidate;
                break;
            }
        }
        if (!source.raw()) {
            bucket = Math.max(bucket, 60);
        }
        from = floor(from, bucket);
        to = floor(to, bucket);
        int points = (int) Math.max(0, Duration.between(from, to).getSeconds() / bucket);

        boolean byEvent = filter.hasWaitClass();
        String groupColumn = byEvent ? "event" : "wait_class";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", targetId)
                .addValue("from", utc(from))
                .addValue("to", utc(to))
                .addValue("bucket", (double) bucket);
        String conditions = filter.conditions(params);
        String sql = """
                select date_bin(make_interval(secs => :bucket), %2$s, timestamptz '1970-01-01 00:00:00+00') as t,
                       %3$s as k, %4$s as c
                  from %1$s
                 where target_id = :target and %2$s >= :from and %2$s < :to%5$s
                 group by 1, 2""".formatted(source.table(), source.timeColumn(), groupColumn, source.countExpr(),
                conditions);

        Map<String, double[]> byName = new HashMap<>();
        Map<String, Double> totals = new HashMap<>();
        final Instant start = from;
        final long bucketSeconds = bucket;
        jdbc.query(sql, params, rs -> {
            int index = (int) (Duration.between(start, rs.getObject("t", OffsetDateTime.class).toInstant())
                    .getSeconds() / bucketSeconds);
            if (index < 0 || index >= points) {
                return;
            }
            String name = rs.getString("k");
            double value = rs.getLong("c") / (double) bucketSeconds;
            byName.computeIfAbsent(name, n -> new double[points])[index] = value;
            totals.merge(name, value, Double::sum);
        });

        List<Instant> times = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            times.add(from.plusSeconds(i * bucket));
        }
        // Busiest first, so the client can keep the biggest contributors when there are many events
        List<Series> series = byName.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -totals.get(e.getKey())))
                .map(e -> new Series(e.getKey(), e.getValue()))
                .toList();
        return new TimeSeries(from, to, bucket, groupColumn, times, series);
    }

    public enum Dimension { SQL, SESSION }

    /** The statements or sessions responsible for most of the activity in a period. */
    public TopResult top(long targetId, Instant from, Instant to, Dimension dimension, Filter filter, int limit) {
        Source source = sourceFor(targetId, from, to);
        if (!source.raw()) {
            // The rollup has one-minute resolution
            from = floor(from, 60);
            to = floor(to.plusSeconds(59), 60);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", targetId)
                .addValue("from", utc(from))
                .addValue("to", utc(to));
        String conditions = filter.conditions(params);
        String keys = dimension == Dimension.SQL ? "sql_id" : "session_id, session_serial";
        String attributes = dimension == Dimension.SQL
                ? "max(sql_opname) as sql_opname"
                : "max(user_name) as user_name, max(program) as program";
        String sql = """
                select %1$s, wait_class, %2$s, %3$s as c
                  from %4$s
                 where target_id = :target and %5$s >= :from and %5$s < :to%6$s
                 group by %1$s, wait_class""".formatted(keys, attributes, source.countExpr(), source.table(),
                source.timeColumn(), conditions);

        final class Acc {
            String sqlId;
            String sqlOpname;
            Long sessionId;
            Long sessionSerial;
            String userName;
            String program;
            long samples;
            final Map<String, Long> byWaitClass = new TreeMap<>();
        }
        Map<String, Acc> accs = new LinkedHashMap<>();
        long[] total = {0};
        jdbc.query(sql, params, rs -> {
            long count = rs.getLong("c");
            total[0] += count;
            Acc acc;
            if (dimension == Dimension.SQL) {
                String rowSqlId = rs.getString("sql_id");
                acc = accs.computeIfAbsent(rowSqlId, k -> new Acc());
                acc.sqlId = rowSqlId;
                if (rs.getString("sql_opname") != null) {
                    acc.sqlOpname = rs.getString("sql_opname");
                }
            } else {
                long sessionId = rs.getLong("session_id");
                long serial = rs.getLong("session_serial");
                acc = accs.computeIfAbsent(sessionId + ":" + serial, k -> new Acc());
                acc.sessionId = sessionId;
                acc.sessionSerial = serial;
                if (rs.getString("user_name") != null) {
                    acc.userName = rs.getString("user_name");
                }
                if (rs.getString("program") != null) {
                    acc.program = rs.getString("program");
                }
            }
            acc.samples += count;
            acc.byWaitClass.merge(rs.getString("wait_class"), count, Long::sum);
        });

        List<Acc> top = accs.values().stream()
                .sorted(Comparator.comparingLong((Acc a) -> -a.samples))
                .limit(limit)
                .toList();
        Map<String, String> texts = dimension == Dimension.SQL
                ? sqlTextSnippets(targetId, top.stream().map(a -> a.sqlId).filter(id -> !id.isEmpty()).toList())
                : Map.of();
        double seconds = Math.max(1, Duration.between(from, to).getSeconds());
        List<TopRow> rows = top.stream()
                .map(a -> new TopRow(a.sqlId, a.sqlOpname, a.sqlId == null ? null : texts.get(a.sqlId),
                        a.sessionId, a.sessionSerial, a.userName, a.program, a.samples,
                        total[0] == 0 ? 0 : 100.0 * a.samples / total[0], a.samples / seconds, a.byWaitClass))
                .toList();
        return new TopResult(from, to, total[0], rows);
    }

    private Map<String, String> sqlTextSnippets(long targetId, Collection<String> sqlIds) {
        Map<String, String> texts = new HashMap<>();
        if (sqlIds.isEmpty()) {
            return texts;
        }
        jdbc.query("select sql_id, left(sql_text, 300) as t from sql_text where target_id = :target and sql_id in (:ids)",
                new MapSqlParameterSource().addValue("target", targetId).addValue("ids", sqlIds),
                rs -> {
                    texts.put(rs.getString("sql_id"), rs.getString("t"));
                });
        return texts;
    }

    /**
     * Who a session was and when it was active, or null when nothing is stored about it. The attributes
     * (machine, module, client address...) are those of its newest sample that is still kept.
     */
    public SessionInfo sessionInfo(long targetId, long sessionId, long sessionSerial) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", targetId)
                .addValue("sessionId", sessionId)
                .addValue("sessionSerial", sessionSerial);
        SessionInfo summary = jdbc.query("""
                select max(user_name) as user_name, max(program) as program, min(bucket) as first_seen,
                       max(bucket) as last_seen, sum(samples) as samples
                  from ash_rollup_1m
                 where target_id = :target and session_id = :sessionId and session_serial = :sessionSerial""",
                params, rs -> {
                    if (!rs.next() || rs.getObject("first_seen") == null) {
                        return null;
                    }
                    return new SessionInfo(sessionId, sessionSerial, rs.getString("user_name"),
                            rs.getString("program"), rs.getObject("first_seen", OffsetDateTime.class).toInstant(),
                            rs.getObject("last_seen", OffsetDateTime.class).toInstant(), rs.getLong("samples"),
                            new LinkedHashMap<>());
                });
        if (summary == null) {
            return null;
        }
        // Only the last active minute is searched, so this stays quick however old the session is
        params.addValue("from", utc(summary.lastSeen())).addValue("to", utc(summary.lastSeen().plusSeconds(60)));
        jdbc.query("""
                select a.key, a.value
                  from (select extra from ash_sample
                         where target_id = :target and sample_time >= :from and sample_time < :to
                           and session_id = :sessionId and session_serial = :sessionSerial and extra is not null
                         order by sample_time desc limit 1) s,
                       jsonb_each_text(s.extra) a""",
                params, rs -> {
                    summary.attributes().put(rs.getString(1), rs.getString(2));
                });
        return summary;
    }

    /** One stored sample: one session that was active at one moment. */
    public record SampleRow(Instant time, long sessionId, long sessionSerial, String userName, String program,
                            String sqlId, String sqlOpname, String waitClass, String event,
                            Map<String, String> details) {
    }

    /**
     * Hands the stored samples of a period to the consumer, in time order. detailKeys names the members of
     * "extra" to deliver with each row; they must be trusted names, they become part of the statement.
     */
    public void forEachSample(long targetId, Instant from, Instant to, Filter filter, boolean ascending,
                              int limit, int offset, List<String> detailKeys, Consumer<SampleRow> consumer) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", targetId)
                .addValue("from", utc(from))
                .addValue("to", utc(to))
                .addValue("limit", limit)
                .addValue("offset", offset);
        StringBuilder details = new StringBuilder();
        for (String key : detailKeys) {
            if (!key.matches("[A-Za-z]+")) {
                throw new IllegalArgumentException(key);
            }
            details.append(", extra->>'").append(key).append('\'');
        }
        String direction = ascending ? "asc" : "desc";
        String sql = """
                select sample_time, session_id, session_serial, user_name, program, sql_id, sql_opname,
                       wait_class, event%1$s
                  from ash_sample
                 where target_id = :target and sample_time >= :from and sample_time < :to%2$s
                 order by sample_time %3$s, session_id, session_serial
                 limit :limit offset :offset""".formatted(details, filter.conditions(params), direction);
        jdbc.query(sql, params, rs -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < detailKeys.size(); i++) {
                String value = rs.getString(10 + i);
                if (value != null) {
                    values.put(detailKeys.get(i), value);
                }
            }
            consumer.accept(new SampleRow(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getLong(2),
                    rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8), rs.getString(9), values));
        });
    }

    /** How many samples a period holds, counting no further than cap + 1. */
    public long countSamples(long targetId, Instant from, Instant to, Filter filter, int cap) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", targetId)
                .addValue("from", utc(from))
                .addValue("to", utc(to))
                .addValue("cap", cap + 1);
        String sql = """
                select count(*) from (
                    select 1 from ash_sample
                     where target_id = :target and sample_time >= :from and sample_time < :to%s
                     limit :cap) capped""".formatted(filter.conditions(params));
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** Samples of this database older than this have been removed; only per-minute totals remain. */
    public Instant samplesKeptSince(long targetId) {
        return partitions.rawCutoff(targetId);
    }

    /** Statement type (SELECT, UPDATE...) as last seen in the samples, or null. */
    public String sqlOpname(long targetId, String sqlId) {
        List<String> found = jdbc.queryForList("""
                select sql_opname from ash_rollup_1m
                 where target_id = :target and sql_id = :id and sql_opname is not null
                 order by bucket desc limit 1""",
                new MapSqlParameterSource().addValue("target", targetId).addValue("id", sqlId), String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    public String sqlText(long targetId, String sqlId) {
        List<String> found = jdbc.queryForList(
                "select sql_text from sql_text where target_id = :target and sql_id = :id",
                new MapSqlParameterSource().addValue("target", targetId).addValue("id", sqlId), String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Load of every database over the last hour (per minute), and its average over the last five minutes. */
    public Map<Long, OverviewRow> overview() {
        final int minutes = 60;
        final int recent = 5;
        // The current minute is incomplete, so the window ends at the start of it
        Instant to = floor(Instant.now(), 60);
        Instant from = to.minusSeconds(minutes * 60L);
        Instant recentFrom = to.minusSeconds(recent * 60L);

        Map<Long, double[]> sparks = new HashMap<>();
        Map<Long, Map<String, Double>> recentByClass = new HashMap<>();
        jdbc.query("""
                select target_id, bucket, wait_class, sum(samples) as c
                  from ash_rollup_1m
                 where bucket >= :from and bucket < :to
                 group by target_id, bucket, wait_class""",
                new MapSqlParameterSource().addValue("from", utc(from)).addValue("to", utc(to)),
                rs -> {
                    long targetId = rs.getLong("target_id");
                    Instant bucket = rs.getObject("bucket", OffsetDateTime.class).toInstant();
                    long count = rs.getLong("c");
                    int index = (int) (Duration.between(from, bucket).getSeconds() / 60);
                    if (index >= 0 && index < minutes) {
                        sparks.computeIfAbsent(targetId, k -> new double[minutes])[index] += count / 60.0;
                    }
                    if (!bucket.isBefore(recentFrom)) {
                        recentByClass.computeIfAbsent(targetId, k -> new TreeMap<>())
                                .merge(rs.getString("wait_class"), count / (recent * 60.0), Double::sum);
                    }
                });

        Map<Long, OverviewRow> rows = new HashMap<>();
        for (Map.Entry<Long, double[]> e : sparks.entrySet()) {
            Map<String, Double> byClass = recentByClass.getOrDefault(e.getKey(), Map.of());
            double aas = byClass.values().stream().mapToDouble(Double::doubleValue).sum();
            rows.put(e.getKey(), new OverviewRow(e.getKey(), aas, byClass, e.getValue()));
        }
        return rows;
    }
}
