package com.ashweb.collector;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads v$active_session_history incrementally (Oracle 11.2 or newer, Diagnostic Pack licence required).
 * Oracle samples active sessions once a second by itself, so polling only has to fetch what is new.
 */
public class OracleAshSource implements AshSource {

    private static final String ASH_SQL = """
            SELECT sample_time, session_id, session_serial#, user_id, program, sql_id, sql_opname,
                   session_state, wait_class, event,
                   module, action, machine, session_type, sql_plan_hash_value, sql_child_number,
                   blocking_session, current_obj#
              FROM v$active_session_history
             WHERE sample_time > ?
             ORDER BY sample_time""";

    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(30);

    private final long intervalMs;
    private final int initialLoadMinutes;
    private final int queryTimeoutSeconds;

    /** Differences smaller than this are network delay and rounding, not a wrong clock. */
    private static final Duration SKEW_TOLERANCE = Duration.ofSeconds(5);

    private Map<Long, String> userNames = Map.of();
    /** Database clock minus this server's clock. Null until the first poll has measured it. */
    private Duration skew;
    /** Newest sample_time fetched so far, exactly as the database wrote it (its local time). */
    private LocalDateTime dbWatermark;
    private Instant userNamesLoadedAt = Instant.EPOCH;

    public OracleAshSource(long intervalMs, int initialLoadMinutes, int queryTimeoutSeconds) {
        this.intervalMs = intervalMs;
        this.initialLoadMinutes = initialLoadMinutes;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public long intervalMs() {
        return intervalMs;
    }

    @Override
    public Duration clockSkew() {
        return skew == null ? Duration.ZERO : skew;
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM v$active_session_history WHERE rownum = 1";
    }

    @Override
    public List<Sample> poll(Connection connection, Instant watermark) throws SQLException {
        // sample_time is a plain TIMESTAMP in the database server's local time. Read the server's current
        // offset and clock so samples can be converted to instants whatever time zone this collector runs in.
        ZoneOffset offset;
        Instant dbNow;
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery("""
                    SELECT TO_CHAR(SYSTIMESTAMP, 'TZH:TZM'),
                           CAST(SYSTIMESTAMP AT TIME ZONE 'UTC' AS TIMESTAMP)
                      FROM dual""")) {
                rs.next();
                offset = ZoneOffset.of(rs.getString(1).trim());
                dbNow = rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC);
            }
        }
        refreshUserNames(connection);

        // The database's clock may disagree with this server's, sometimes by hours. Samples are moved onto
        // this server's timeline so "the last hour" means the same thing for every database. The measured
        // difference is kept steady and only re-based when it changes noticeably.
        Duration measured = Duration.between(Instant.now(), dbNow);
        if (skew == null || measured.minus(skew).abs().compareTo(SKEW_TOLERANCE) > 0) {
            skew = measured.abs().compareTo(SKEW_TOLERANCE) > 0 ? measured : Duration.ZERO;
        }
        final Duration appliedSkew = skew;

        // Fetching works in the database's own local time; conversion happens per row
        LocalDateTime fromLocal = LocalDateTime.ofInstant(dbNow.minus(Duration.ofMinutes(initialLoadMinutes)), offset);
        if (dbWatermark == null && watermark != null) {
            dbWatermark = LocalDateTime.ofInstant(watermark.plus(appliedSkew), offset);
        }
        if (dbWatermark != null && dbWatermark.isAfter(fromLocal)) {
            fromLocal = dbWatermark;
        }

        List<Sample> samples = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(ASH_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setFetchSize(5000);
            ps.setObject(1, fromLocal);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDateTime sampleLocal = rs.getObject(1, LocalDateTime.class);
                    if (dbWatermark == null || sampleLocal.isAfter(dbWatermark)) {
                        dbWatermark = sampleLocal;
                    }
                    Instant sampleTime = sampleLocal.toInstant(offset).minus(appliedSkew);
                    long userId = rs.getLong(4);
                    String sqlId = rs.getString(6);
                    boolean onCpu = "ON CPU".equals(rs.getString(8));
                    String waitClass = onCpu ? Sample.CPU : orElse(rs.getString(9), "Other");
                    String event = onCpu ? Sample.CPU : orElse(rs.getString(10), "unknown");

                    StringBuilder extra = new StringBuilder();
                    AshSource.jsonField(extra, "module", rs.getString(11));
                    AshSource.jsonField(extra, "action", rs.getString(12));
                    AshSource.jsonField(extra, "machine", rs.getString(13));
                    AshSource.jsonField(extra, "sessionType", rs.getString(14));
                    AshSource.jsonField(extra, "planHashValue", rs.getObject(15, BigDecimal.class));
                    AshSource.jsonField(extra, "childNumber", rs.getObject(16, BigDecimal.class));
                    AshSource.jsonField(extra, "blockingSession", rs.getObject(17, BigDecimal.class));
                    AshSource.jsonField(extra, "currentObj", rs.getObject(18, BigDecimal.class));

                    samples.add(new Sample(sampleTime, rs.getLong(2), rs.getLong(3),
                            userNames.getOrDefault(userId, String.valueOf(userId)), rs.getString(5),
                            sqlId == null ? "" : sqlId, rs.getString(7), waitClass, event, null,
                            AshSource.jsonEnd(extra)));
                }
            }
        }
        return samples;
    }

    @Override
    public Map<String, String> fetchSqlTexts(Connection connection, Collection<String> sqlIds) throws SQLException {
        Map<String, String> texts = new HashMap<>();
        if (sqlIds.isEmpty()) {
            return texts;
        }
        String placeholders = String.join(",", sqlIds.stream().map(id -> "?").toList());
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sql_id, sql_fulltext FROM v$sqlstats WHERE sql_id IN (" + placeholders + ")")) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            int i = 1;
            for (String id : sqlIds) {
                ps.setString(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String text = rs.getString(2);
                    if (text != null) {
                        texts.put(rs.getString(1), text);
                    }
                }
            }
        }
        return texts;
    }

    /** The report can take much longer to write than the collector's statements may run. */
    private static final int REPORT_TIMEOUT_SECONDS = 180;

    @Override
    public Report activityReport(Connection connection, Instant from, Instant to, Long sessionId, String sqlId,
                                 String waitClass) throws SQLException {
        // The period is handed over as "so many seconds ago", which the database turns into its own
        // clock and time zone. That keeps the report on the same stretch of time as the charts even
        // when the two clocks disagree.
        Instant now = Instant.now();
        double fromSecondsAgo = Math.max(0, Duration.between(from, now).toMillis() / 1000.0);
        double toSecondsAgo = Math.max(0, Duration.between(to, now).toMillis() / 1000.0);
        // CPU is not a wait class to Oracle, so the report cannot be narrowed to it
        String reportWaitClass = Sample.CPU.equals(waitClass) || waitClass == null || waitClass.isBlank()
                ? null : waitClass;
        StringBuilder html = new StringBuilder();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT output FROM TABLE(DBMS_WORKLOAD_REPOSITORY.ASH_REPORT_HTML(
                    TO_NUMBER(SYS_CONTEXT('USERENV', 'DBID')), TO_NUMBER(SYS_CONTEXT('USERENV', 'INSTANCE')),
                    SYSDATE - ? / 86400, SYSDATE - ? / 86400, 0, 0, ?, ?, ?))""")) {
            ps.setQueryTimeout(REPORT_TIMEOUT_SECONDS);
            ps.setDouble(1, fromSecondsAgo);
            ps.setDouble(2, toSecondsAgo);
            if (sessionId == null) {
                ps.setNull(3, java.sql.Types.NUMERIC);
            } else {
                ps.setLong(3, sessionId);
            }
            ps.setString(4, sqlId == null || sqlId.isBlank() ? null : sqlId);
            ps.setString(5, reportWaitClass);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) {
                        html.append(line);
                    }
                    html.append('\n');
                }
            }
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().strip();
            // ORA-00904: the package is not visible to this account
            if (e.getErrorCode() == 904 || e.getErrorCode() == 1031 || e.getErrorCode() == 6550) {
                return new Report(null, "The monitoring account may not run the report. A DBA can allow it with: "
                        + "grant execute on dbms_workload_repository to " + connection.getMetaData().getUserName()
                        + ". (" + message.lines().findFirst().orElse("") + ")");
            }
            return new Report(null, "The database could not write the report: " + message);
        }
        if (html.toString().isBlank()) {
            return new Report(null, "The database returned an empty report for this period.");
        }
        return new Report(html.toString(), null);
    }

    @Override
    public SqlLiveDetail fetchSqlDetail(Connection connection, String sqlId) {
        List<String> notes = new ArrayList<>();
        List<SqlLiveDetail.StatGroup> stats = new ArrayList<>();
        List<SqlLiveDetail.Plan> plans = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT plan_hash_value, executions, elapsed_time, cpu_time, user_io_wait_time,
                       concurrency_wait_time, application_wait_time, buffer_gets, disk_reads, direct_writes,
                       rows_processed, fetches, parse_calls, sorts, loads, invalidations, version_count
                  FROM v$sqlstats WHERE sql_id = ? ORDER BY elapsed_time DESC""")) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, sqlId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<SqlLiveDetail.Stat> s = new ArrayList<>();
                    s.add(SqlLiveDetail.Stat.count("Executions", rs.getDouble(2)));
                    s.add(SqlLiveDetail.Stat.micros("Elapsed time", rs.getDouble(3)));
                    s.add(SqlLiveDetail.Stat.micros("CPU time", rs.getDouble(4)));
                    s.add(SqlLiveDetail.Stat.micros("User I/O wait time", rs.getDouble(5)));
                    s.add(SqlLiveDetail.Stat.micros("Concurrency wait time", rs.getDouble(6)));
                    s.add(SqlLiveDetail.Stat.micros("Application wait time", rs.getDouble(7)));
                    s.add(SqlLiveDetail.Stat.count("Buffer gets", rs.getDouble(8)));
                    s.add(SqlLiveDetail.Stat.count("Disk reads", rs.getDouble(9)));
                    s.add(SqlLiveDetail.Stat.count("Direct writes", rs.getDouble(10)));
                    s.add(SqlLiveDetail.Stat.count("Rows processed", rs.getDouble(11)));
                    s.add(SqlLiveDetail.Stat.count("Fetches", rs.getDouble(12)));
                    s.add(SqlLiveDetail.Stat.count("Parse calls", rs.getDouble(13)));
                    s.add(SqlLiveDetail.Stat.count("Sorts", rs.getDouble(14)));
                    s.add(SqlLiveDetail.Stat.count("Loads", rs.getDouble(15)));
                    s.add(SqlLiveDetail.Stat.count("Invalidations", rs.getDouble(16)));
                    s.add(SqlLiveDetail.Stat.count("Child cursors", rs.getDouble(17)));
                    // Some of these counters overflow inside Oracle and come back negative; leave those out
                    s.removeIf(stat -> stat.number() != null && stat.number() < 0);
                    stats.add(new SqlLiveDetail.StatGroup("Plan " + rs.getBigDecimal(1).toPlainString(),
                            rs.getLong(2), s));
                }
            }
            if (stats.isEmpty()) {
                notes.add("The database no longer has statistics for this statement (it aged out of the shared pool).");
            }
        } catch (SQLException e) {
            notes.add("Statistics could not be read (the account needs SELECT on v_$sqlstats): " + firstLine(e));
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT plan_hash_value, child_number, id, parent_id, depth, operation, options, object_owner,
                       object_name, cardinality, bytes, cost, time, access_predicates, filter_predicates
                  FROM v$sql_plan WHERE sql_id = ? ORDER BY plan_hash_value, child_number, id""")) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, sqlId);
            try (ResultSet rs = ps.executeQuery()) {
                String currentPlan = null;
                long keptChild = -1;
                List<SqlLiveDetail.PlanRow> rows = null;
                while (rs.next()) {
                    String planHash = rs.getBigDecimal(1).toPlainString();
                    long child = rs.getLong(2);
                    if (!planHash.equals(currentPlan)) {
                        currentPlan = planHash;
                        keptChild = child;
                        rows = new ArrayList<>();
                        plans.add(new SqlLiveDetail.Plan(planHash, child, rows));
                    } else if (child != keptChild) {
                        // Child cursors sharing a plan hash have the same plan; show it once
                        continue;
                    }
                    String options = rs.getString(7);
                    String owner = rs.getString(8);
                    String object = rs.getString(9);
                    rows.add(new SqlLiveDetail.PlanRow(rs.getInt(3), nullableInt(rs, 4), rs.getInt(5),
                            options == null ? rs.getString(6) : rs.getString(6) + " " + options,
                            object == null ? null : owner == null ? object : owner + "." + object,
                            nullableLong(rs, 10), nullableLong(rs, 11), nullableLong(rs, 12), nullableLong(rs, 13),
                            rs.getString(14), rs.getString(15)));
                }
            }
            if (plans.isEmpty()) {
                notes.add("The database no longer has an execution plan for this statement in memory.");
            }
        } catch (SQLException e) {
            notes.add("Execution plans could not be read (the account needs SELECT on v_$sql_plan): " + firstLine(e));
        }
        return new SqlLiveDetail(plans, stats, notes);
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.longValue();
    }

    private static String firstLine(SQLException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.strip().lines().findFirst().orElse("");
    }

    private void refreshUserNames(Connection connection) {
        if (Duration.between(userNamesLoadedAt, Instant.now()).compareTo(USER_CACHE_TTL) < 0) {
            return;
        }
        Map<Long, String> names = new HashMap<>();
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery("SELECT user_id, username FROM all_users")) {
                while (rs.next()) {
                    names.put(rs.getLong(1), rs.getString(2));
                }
            }
            userNames = names;
        } catch (SQLException e) {
            // Not fatal: sessions are shown with the numeric user id instead
        }
        userNamesLoadedAt = Instant.now();
    }

    private static String orElse(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
