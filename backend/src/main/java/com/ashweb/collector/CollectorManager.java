package com.ashweb.collector;

import com.ashweb.security.SecretCipher;
import com.ashweb.storage.SampleWriter;
import com.ashweb.target.Target;
import com.ashweb.target.TargetRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs one independent collector per enabled database. A single timer thread only fires ticks; the polling
 * itself runs on virtual threads, so a slow or unreachable database never delays the others.
 */
@Component
public class CollectorManager {

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    /** @param clockSkewSeconds database clock minus this server's clock; 0 when they agree or it is unknown */
    public record Status(State state, Instant lastSampleTime, Instant lastSuccessAt, String lastError,
                         Instant lastErrorAt, long rowsCollected, long clockSkewSeconds) {
        static final Status STOPPED = new Status(State.STOPPED, null, null, null, null, 0, 0);
    }

    private static final Logger log = LoggerFactory.getLogger(CollectorManager.class);
    private static final int MAX_SQL_TEXT_LOOKUPS_PER_POLL = 50;
    private static final int KNOWN_SQL_IDS_LIMIT = 50_000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private final TargetRepository targets;
    private final TargetConnections connections;
    private final SecretCipher cipher;
    private final SampleWriter writer;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "collector-timer");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("collector-", 0).factory());
    private final Map<Long, Collector> collectors = new ConcurrentHashMap<>();

    public CollectorManager(TargetRepository targets, TargetConnections connections, SecretCipher cipher,
                            SampleWriter writer) {
        this.targets = targets;
        this.connections = connections;
        this.cipher = cipher;
        this.writer = writer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        List<Target> enabled = targets.findAll().stream().filter(Target::enabled).toList();
        enabled.forEach(this::start);
        log.info("Started collectors for {} database(s)", enabled.size());
    }

    /** Starts (or restarts with fresh settings) the collector of a database. */
    public synchronized void start(Target target) {
        stop(target.id());
        Collector collector = new Collector(target);
        collectors.put(target.id(), collector);
        collector.schedule();
    }

    public synchronized void stop(long targetId) {
        Collector collector = collectors.remove(targetId);
        if (collector != null) {
            collector.close();
        }
    }

    public Status status(long targetId) {
        Collector collector = collectors.get(targetId);
        return collector == null ? Status.STOPPED : collector.status;
    }

    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
        new ArrayList<>(collectors.keySet()).forEach(this::stop);
        workers.shutdownNow();
    }

    private final class Collector {
        private final Target target;
        private final AshSource source;
        private final AtomicBoolean busy = new AtomicBoolean();
        /** Statement ids whose text is stored or was looked up already; bounded, oldest forgotten first. */
        private final Set<String> knownSqlIds = new LinkedHashSet<>();

        private volatile Status status = new Status(State.STARTING, null, null, null, null, 0, 0);
        private volatile boolean closed;
        private HikariDataSource pool;
        private ScheduledFuture<?> ticker;
        private Instant watermark;
        private boolean watermarkLoaded;
        private int failures;
        private long notBefore;

        Collector(Target target) {
            this.target = target;
            this.source = connections.sourceFor(target.dbType());
        }

        void schedule() {
            ticker = timer.scheduleAtFixedRate(this::tick, 0, source.intervalMs(), TimeUnit.MILLISECONDS);
        }

        private void tick() {
            // Skip the tick when the previous poll is still running or the collector is backing off
            if (closed || System.currentTimeMillis() < notBefore || !busy.compareAndSet(false, true)) {
                return;
            }
            try {
                workers.execute(() -> {
                    try {
                        poll();
                    } finally {
                        busy.set(false);
                    }
                });
            } catch (RuntimeException e) {
                busy.set(false);
            }
        }

        private void poll() {
            try {
                if (pool == null) {
                    pool = connections.pool("target-" + target.id(), target.dbType(), target.jdbcUrl(),
                            target.username(), cipher.decrypt(target.passwordEnc()));
                }
                if (!watermarkLoaded) {
                    watermark = writer.newestSampleTime(target.id());
                    watermarkLoaded = true;
                }
                List<Sample> samples;
                Map<String, String> sqlTexts = new LinkedHashMap<>();
                try (Connection connection = pool.getConnection()) {
                    samples = source.poll(connection, watermark);
                    collectSqlTexts(connection, samples, sqlTexts);
                }
                if (closed) {
                    return;
                }
                int stored = writer.write(target.id(), samples);
                writer.writeSqlTexts(target.id(), sqlTexts);
                for (Sample s : samples) {
                    if (watermark == null || s.sampleTime().isAfter(watermark)) {
                        watermark = s.sampleTime();
                    }
                }
                failures = 0;
                Status previous = status;
                status = new Status(State.RUNNING, watermark, Instant.now(), null, previous.lastErrorAt(),
                        previous.rowsCollected() + stored, source.clockSkew().toSeconds());
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                failures++;
                long backoff = Math.min(MAX_BACKOFF_MS, 2_000L << Math.min(failures, 5));
                notBefore = System.currentTimeMillis() + backoff;
                String message = rootMessage(e);
                if (failures == 1) {
                    log.warn("Collector for '{}' failed: {}", target.name(), message);
                }
                Status previous = status;
                status = new Status(State.ERROR, previous.lastSampleTime(), previous.lastSuccessAt(), message,
                        Instant.now(), previous.rowsCollected(), previous.clockSkewSeconds());
            }
        }

        private void collectSqlTexts(Connection connection, List<Sample> samples, Map<String, String> out) {
            Set<String> lookups = new LinkedHashSet<>();
            for (Sample s : samples) {
                if (s.sqlId().isEmpty() || knownSqlIds.contains(s.sqlId())) {
                    continue;
                }
                if (s.sqlText() != null) {
                    out.put(s.sqlId(), s.sqlText());
                    remember(s.sqlId());
                } else if (lookups.size() < MAX_SQL_TEXT_LOOKUPS_PER_POLL) {
                    lookups.add(s.sqlId());
                }
            }
            if (lookups.isEmpty()) {
                return;
            }
            try {
                out.putAll(source.fetchSqlTexts(connection, lookups));
            } catch (Exception e) {
                // Statement text is a nicety; never fail sample collection because of it
                log.debug("Could not read statement texts from '{}': {}", target.name(), rootMessage(e));
            }
            lookups.forEach(this::remember);
        }

        private void remember(String sqlId) {
            knownSqlIds.add(sqlId);
            if (knownSqlIds.size() > KNOWN_SQL_IDS_LIMIT) {
                var it = knownSqlIds.iterator();
                it.next();
                it.remove();
            }
        }

        void close() {
            closed = true;
            if (ticker != null) {
                ticker.cancel(false);
            }
            // Close the pool off the caller's thread: it waits for a poll that may be in flight
            HikariDataSource p = pool;
            if (p != null) {
                Thread.ofVirtual().start(p::close);
            }
        }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        message = message == null || message.isBlank() ? root.getClass().getSimpleName() : message.strip();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** Exposed for the "test connection" endpoint so errors read the same everywhere. */
    public static String describe(Throwable e) {
        return rootMessage(e);
    }
}
