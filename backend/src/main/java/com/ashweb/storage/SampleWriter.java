package com.ashweb.storage;

import com.ashweb.collector.Sample;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores a batch of samples: the raw rows plus their per-minute rollup. */
@Component
public class SampleWriter {

    private static final int MAX_SQL_TEXT = 100_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PartitionManager partitions;

    public SampleWriter(JdbcTemplate jdbc, TransactionTemplate tx, PartitionManager partitions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.partitions = partitions;
    }

    private record RollupKey(Instant bucket, String waitClass, String event, String sqlId, long sessionId,
                             long sessionSerial) implements Comparable<RollupKey> {
        @Override
        public int compareTo(RollupKey o) {
            int c = bucket.compareTo(o.bucket);
            if (c == 0) c = waitClass.compareTo(o.waitClass);
            if (c == 0) c = event.compareTo(o.event);
            if (c == 0) c = sqlId.compareTo(o.sqlId);
            if (c == 0) c = Long.compare(sessionId, o.sessionId);
            if (c == 0) c = Long.compare(sessionSerial, o.sessionSerial);
            return c;
        }
    }

    private static final class RollupValue {
        int samples;
        String sqlOpname;
        String userName;
        String program;
    }

    public Instant newestSampleTime(long targetId) {
        // Ignore anything stamped in the future (a clock was wrong when it was stored): resuming from there
        // would skip everything until real time caught up
        OffsetDateTime max = jdbc.queryForObject("""
                select max(sample_time) from ash_sample
                 where target_id = ? and sample_time <= now() + interval '1 minute'""",
                OffsetDateTime.class, targetId);
        return max == null ? null : max.toInstant();
    }

    /** @return number of rows stored (samples older than the raw retention are skipped) */
    public int write(long targetId, List<Sample> batch) {
        Instant cutoff = partitions.rawCutoff(targetId);
        List<Sample> samples = batch.stream().filter(s -> !s.sampleTime().isBefore(cutoff)).toList();
        if (samples.isEmpty()) {
            return 0;
        }

        // Sorted so concurrent writers always lock rollup rows in the same order
        Map<RollupKey, RollupValue> rollup = new TreeMap<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        for (Sample s : samples) {
            days.add(LocalDate.ofInstant(s.sampleTime(), ZoneOffset.UTC));
            RollupKey key = new RollupKey(s.sampleTime().truncatedTo(ChronoUnit.MINUTES), s.waitClass(), s.event(),
                    s.sqlId(), s.sessionId(), s.sessionSerial());
            RollupValue value = rollup.computeIfAbsent(key, k -> new RollupValue());
            value.samples++;
            value.sqlOpname = s.sqlOpname();
            value.userName = s.userName();
            value.program = s.program();
        }
        for (LocalDate day : days) {
            partitions.ensure(PartitionManager.RAW, day);
            partitions.ensure(PartitionManager.ROLLUP, day);
        }

        List<Map.Entry<RollupKey, RollupValue>> rollupRows = new ArrayList<>(rollup.entrySet());
        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate("""
                    insert into ash_sample (target_id, sample_time, session_id, session_serial, user_name, program,
                                            sql_id, sql_opname, wait_class, event, extra)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""", samples, 1000, (ps, s) -> {
                ps.setLong(1, targetId);
                ps.setObject(2, s.sampleTime().atOffset(ZoneOffset.UTC));
                ps.setLong(3, s.sessionId());
                ps.setLong(4, s.sessionSerial());
                ps.setString(5, clean(s.userName()));
                ps.setString(6, clean(s.program()));
                ps.setString(7, s.sqlId());
                ps.setString(8, clean(s.sqlOpname()));
                ps.setString(9, s.waitClass());
                ps.setString(10, s.event());
                ps.setObject(11, clean(s.extra()), Types.VARCHAR);
            });
            jdbc.batchUpdate("""
                    insert into ash_rollup_1m (target_id, bucket, wait_class, event, sql_id, session_id,
                                               session_serial, sql_opname, user_name, program, samples)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (target_id, bucket, wait_class, event, sql_id, session_id, session_serial)
                    do update set samples = ash_rollup_1m.samples + excluded.samples""", rollupRows, 1000,
                    (ps, row) -> {
                        RollupKey k = row.getKey();
                        RollupValue v = row.getValue();
                        ps.setLong(1, targetId);
                        ps.setObject(2, k.bucket().atOffset(ZoneOffset.UTC));
                        ps.setString(3, k.waitClass());
                        ps.setString(4, k.event());
                        ps.setString(5, k.sqlId());
                        ps.setLong(6, k.sessionId());
                        ps.setLong(7, k.sessionSerial());
                        ps.setString(8, clean(v.sqlOpname));
                        ps.setString(9, clean(v.userName));
                        ps.setString(10, clean(v.program));
                        ps.setInt(11, v.samples);
                    });
        });
        return samples.size();
    }

    public void writeSqlTexts(long targetId, Map<String, String> texts) {
        if (texts.isEmpty()) {
            return;
        }
        // Sorted for a stable lock order
        List<Map.Entry<String, String>> rows = new ArrayList<>(new TreeMap<>(texts).entrySet());
        jdbc.batchUpdate("""
                insert into sql_text (target_id, sql_id, sql_text) values (?, ?, ?)
                on conflict (target_id, sql_id) do nothing""", rows, 500, (ps, row) -> {
            String text = clean(row.getValue());
            ps.setLong(1, targetId);
            ps.setString(2, row.getKey());
            ps.setString(3, text.length() > MAX_SQL_TEXT ? text.substring(0, MAX_SQL_TEXT) : text);
        });
    }

    /** PostgreSQL text cannot hold the NUL character. */
    private static String clean(String value) {
        return value == null || value.indexOf('\0') < 0 ? value : value.replace("\0", "");
    }
}
