package com.ashweb.storage;

import com.ashweb.config.AshWebProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the daily (UTC) partitions of the sample tables on demand and enforces retention.
 *
 * <p>Retention is set for the whole server and can be overridden per database. Partitions older than the
 * longest retention in use are dropped whole, which is instant. A database that keeps less than that has
 * its older rows deleted instead, which is slower but only touches the day that has just aged out.
 */
@Component
public class PartitionManager {

    public static final String RAW = "ash_sample";
    public static final String ROLLUP = "ash_rollup_1m";

    /** How many days of samples and of per-minute totals are kept. */
    public record Retention(int rawDays, int rollupDays) {
    }

    private static final Logger log = LoggerFactory.getLogger(PartitionManager.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final AshWebProperties properties;
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    /** What each database keeps, read from the target table. Null until first needed. */
    private volatile Map<Long, Retention> byTarget;

    public PartitionManager(JdbcTemplate jdbc, AshWebProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** What a database keeps unless it has its own setting. */
    public Retention defaults() {
        return new Retention(Math.max(1, properties.retention().rawDays()),
                Math.max(1, properties.retention().rollupDays()));
    }

    /** What this database keeps: its own setting where it has one, otherwise the server's. */
    public Retention retention(long targetId) {
        Map<Long, Retention> current = byTarget;
        if (current == null) {
            current = reload();
        }
        return current.getOrDefault(targetId, defaults());
    }

    /** Reads the per-database settings again. Call after a database was added, changed or removed. */
    public synchronized Map<Long, Retention> reload() {
        Retention defaults = defaults();
        Map<Long, Retention> loaded = new HashMap<>();
        jdbc.query("select id, raw_days, rollup_days from target", rs -> {
            int raw = rs.getInt("raw_days");
            raw = rs.wasNull() ? defaults.rawDays() : raw;
            int rollup = rs.getInt("rollup_days");
            rollup = rs.wasNull() ? defaults.rollupDays() : rollup;
            loaded.put(rs.getLong("id"), new Retention(raw, rollup));
        });
        byTarget = loaded;
        return loaded;
    }

    /** Samples of this database older than this are no longer kept. */
    public Instant rawCutoff(long targetId) {
        return start(cutoffDay(retention(targetId).rawDays()));
    }

    private static LocalDate cutoffDay(int retentionDays) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(1, retentionDays));
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public void ensure(String table, LocalDate day) {
        String name = table + "_p" + SUFFIX.format(day);
        if (known.contains(name)) {
            return;
        }
        synchronized (this) {
            // Table and partition names are built from constants and a date, never from user input
            jdbc.execute("create table if not exists " + name + " partition of " + table
                    + " for values from ('" + day + " 00:00:00+00') to ('" + day.plusDays(1) + " 00:00:00+00')");
            known.add(name);
        }
    }

    /** Keeps tomorrow's partitions ready and removes what is past retention. */
    @Scheduled(initialDelay = 5_000, fixedDelay = 3_600_000)
    public void maintain() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (String table : List.of(RAW, ROLLUP)) {
            ensure(table, today);
            ensure(table, today.plusDays(1));
        }
        Map<Long, Retention> targets = reload();
        Retention defaults = defaults();
        int longestRaw = defaults.rawDays();
        int longestRollup = defaults.rollupDays();
        for (Retention r : targets.values()) {
            longestRaw = Math.max(longestRaw, r.rawDays());
            longestRollup = Math.max(longestRollup, r.rollupDays());
        }
        dropOlderThan(RAW, cutoffDay(longestRaw));
        dropOlderThan(ROLLUP, cutoffDay(longestRollup));
        for (Map.Entry<Long, Retention> e : targets.entrySet()) {
            if (e.getValue().rawDays() < longestRaw) {
                deleteOlderThan(RAW, "sample_time", e.getKey(), cutoffDay(e.getValue().rawDays()));
            }
            if (e.getValue().rollupDays() < longestRollup) {
                deleteOlderThan(ROLLUP, "bucket", e.getKey(), cutoffDay(e.getValue().rollupDays()));
            }
        }
    }

    private void deleteOlderThan(String table, String timeColumn, long targetId, LocalDate cutoff) {
        int deleted = jdbc.update("delete from " + table + " where target_id = ? and " + timeColumn + " < ?",
                targetId, start(cutoff).atOffset(ZoneOffset.UTC));
        if (deleted > 0) {
            log.info("Deleted {} rows of database {} from {} (past its retention)", deleted, targetId, table);
        }
    }

    private void dropOlderThan(String table, LocalDate cutoff) {
        List<String> partitions = jdbc.queryForList("""
                select c.relname from pg_inherits i
                  join pg_class c on c.oid = i.inhrelid
                  join pg_class p on p.oid = i.inhparent
                 where p.relname = ?""", String.class, table);
        String prefix = table + "_p";
        for (String partition : partitions) {
            if (!partition.startsWith(prefix)) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(partition.substring(prefix.length()), SUFFIX);
            } catch (RuntimeException e) {
                continue;
            }
            if (day.isBefore(cutoff)) {
                synchronized (this) {
                    jdbc.execute("drop table if exists " + partition);
                    known.remove(partition);
                }
                log.info("Dropped partition {} (past retention)", partition);
            }
        }
    }
}
