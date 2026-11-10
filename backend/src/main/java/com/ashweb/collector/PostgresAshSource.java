package com.ashweb.collector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PostgreSQL keeps no session history, so this samples pg_stat_activity itself: every poll is one sample
 * of the sessions that are active at that moment.
 */
public class PostgresAshSource implements AshSource {

    private static final String SQL_TEMPLATE = """
            SELECT now(), pid, extract(epoch from backend_start)::bigint, coalesce(usename, 'unknown'),
                   application_name, backend_type, coalesce(client_hostname, host(client_addr), 'local'),
                   wait_event_type, wait_event, query, %s, datname, query_start
              FROM pg_stat_activity
             WHERE state = 'active' AND pid <> pg_backend_pid()""";

    private final long intervalMs;
    private final int queryTimeoutSeconds;
    private String sql;

    public PostgresAshSource(long intervalMs, int queryTimeoutSeconds) {
        this.intervalMs = intervalMs;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public long intervalMs() {
        return intervalMs;
    }

    @Override
    public String probeSql() {
        return "SELECT count(*) FROM pg_stat_activity";
    }

    @Override
    public List<Sample> poll(Connection connection, Instant watermark) throws SQLException {
        if (sql == null) {
            // query_id (PostgreSQL 14+, needs compute_query_id) groups statements that differ only in
            // literals. Fall back to a hash of the text when it is not available.
            String textHash = "left(md5(query), 13)";
            String sqlId = connection.getMetaData().getDatabaseMajorVersion() >= 14
                    ? "coalesce(to_hex(query_id), " + textHash + ")"
                    : textHash;
            sql = SQL_TEMPLATE.formatted(sqlId);
        }
        List<Sample> samples = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant sampleTime = rs.getObject(1, OffsetDateTime.class).toInstant();
                    String application = rs.getString(5);
                    String backendType = rs.getString(6);
                    String waitType = rs.getString(8);
                    String waitEvent = rs.getString(9);
                    String query = rs.getString(10);
                    boolean hasQuery = query != null && !query.isBlank();
                    OffsetDateTime queryStart = rs.getObject(13, OffsetDateTime.class);

                    StringBuilder extra = new StringBuilder();
                    AshSource.jsonField(extra, "database", rs.getString(12));
                    AshSource.jsonField(extra, "client", rs.getString(7));
                    AshSource.jsonField(extra, "backendType", backendType);
                    AshSource.jsonField(extra, "queryStart", queryStart);

                    samples.add(new Sample(sampleTime, rs.getLong(2), rs.getLong(3), rs.getString(4),
                            application == null || application.isBlank() ? backendType : application,
                            hasQuery ? rs.getString(11) : "",
                            hasQuery ? firstWord(query) : null,
                            // An active backend that is not waiting is running on (or queued for) a CPU
                            waitType == null ? Sample.CPU : waitType,
                            waitEvent == null ? Sample.CPU : waitEvent,
                            hasQuery ? query : null,
                            AshSource.jsonEnd(extra)));
                }
            }
        }
        return samples;
    }

    @Override
    public SqlLiveDetail fetchSqlDetail(Connection connection, String sqlId) {
        List<String> notes = new ArrayList<>();
        List<SqlLiveDetail.StatGroup> stats = new ArrayList<>();
        notes.add("PostgreSQL does not keep execution plans of past statements, so no plan is shown.");

        long queryId;
        try {
            queryId = Long.parseUnsignedLong(sqlId, 16);
        } catch (NumberFormatException e) {
            notes.add("Statistics are not available for this statement.");
            return new SqlLiveDetail(List.of(), stats, notes);
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT sum(calls), sum(total_exec_time), sum(total_plan_time), sum(rows),
                       sum(shared_blks_hit), sum(shared_blks_read), sum(shared_blks_dirtied),
                       sum(shared_blks_written), sum(temp_blks_read), sum(temp_blks_written), count(*)
                  FROM pg_stat_statements WHERE queryid = ?""")) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, queryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(11) > 0) {
                    List<SqlLiveDetail.Stat> s = new ArrayList<>();
                    s.add(SqlLiveDetail.Stat.count("Executions", rs.getDouble(1)));
                    s.add(SqlLiveDetail.Stat.millis("Execution time", rs.getDouble(2)));
                    s.add(SqlLiveDetail.Stat.millis("Planning time", rs.getDouble(3)));
                    s.add(SqlLiveDetail.Stat.count("Rows", rs.getDouble(4)));
                    s.add(SqlLiveDetail.Stat.count("Shared blocks hit", rs.getDouble(5)));
                    s.add(SqlLiveDetail.Stat.count("Shared blocks read", rs.getDouble(6)));
                    s.add(SqlLiveDetail.Stat.count("Shared blocks dirtied", rs.getDouble(7)));
                    s.add(SqlLiveDetail.Stat.count("Shared blocks written", rs.getDouble(8)));
                    s.add(SqlLiveDetail.Stat.count("Temp blocks read", rs.getDouble(9)));
                    s.add(SqlLiveDetail.Stat.count("Temp blocks written", rs.getDouble(10)));
                    stats.add(new SqlLiveDetail.StatGroup("pg_stat_statements", rs.getLong(1), s));
                } else {
                    // Without compute_query_id the id shown is a hash of the text, which pg_stat_statements
                    // does not know
                    notes.add("pg_stat_statements has no entry for this statement. "
                            + "Statistics need compute_query_id = on so both use the same statement id.");
                }
            }
        } catch (SQLException e) {
            notes.add("Statistics need the pg_stat_statements extension in the database the collector connects to.");
        }
        return new SqlLiveDetail(List.of(), stats, notes);
    }

    private static String firstWord(String query) {
        String trimmed = query.stripLeading();
        int end = 0;
        while (end < trimmed.length() && end < 20 && Character.isLetter(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? null : trimmed.substring(0, end).toUpperCase(Locale.ROOT);
    }
}
