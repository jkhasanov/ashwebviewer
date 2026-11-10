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
