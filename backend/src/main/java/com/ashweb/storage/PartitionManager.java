package com.ashweb.storage;

import com.ashweb.config.AshWebProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the daily (UTC) partitions of the sample tables on demand and enforces retention by dropping
 * whole partitions, which is instant compared with deleting rows.
 */
@Component
public class PartitionManager {

    public static final String RAW = "ash_sample";
    public static final String ROLLUP = "ash_rollup_1m";

    private static final Logger log = LoggerFactory.getLogger(PartitionManager.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final AshWebProperties properties;
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    public PartitionManager(JdbcTemplate jdbc, AshWebProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Instant rawCutoff() {
        return cutoffDay(properties.retention().rawDays()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public Instant rollupCutoff() {
        return cutoffDay(properties.retention().rollupDays()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static LocalDate cutoffDay(int retentionDays) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(1, retentionDays));
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

    /** Keeps tomorrow's partitions ready and drops the ones that are past retention. */
    @Scheduled(initialDelay = 5_000, fixedDelay = 3_600_000)
    public void maintain() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (String table : List.of(RAW, ROLLUP)) {
            ensure(table, today);
            ensure(table, today.plusDays(1));
        }
        dropOlderThan(RAW, cutoffDay(properties.retention().rawDays()));
        dropOlderThan(ROLLUP, cutoffDay(properties.retention().rollupDays()));
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
