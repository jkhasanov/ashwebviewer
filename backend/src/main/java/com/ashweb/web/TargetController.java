package com.ashweb.web;

import com.ashweb.activity.ActivityService;
import com.ashweb.activity.ActivityService.Dimension;
import com.ashweb.activity.ActivityService.Filter;
import com.ashweb.activity.ActivityService.OverviewRow;
import com.ashweb.activity.ActivityService.SampleRow;
import com.ashweb.activity.ActivityService.SessionInfo;
import com.ashweb.activity.ActivityService.TimeSeries;
import com.ashweb.activity.ActivityService.TopResult;
import com.ashweb.collector.CollectorManager;
import com.ashweb.collector.SqlLiveDetail;
import com.ashweb.collector.TargetConnections;
import com.ashweb.security.SecretCipher;
import com.ashweb.target.DbType;
import com.ashweb.target.Target;
import com.ashweb.target.TargetRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Monitored databases: reading is open to every signed-in user, changes need the admin role (SecurityConfig). */
@RestController
@RequestMapping("/api/targets")
public class TargetController {

    private static final Duration MAX_RANGE = Duration.ofDays(400);

    public record TargetInput(
            @NotBlank @Size(max = 80) String name,
            @NotNull DbType dbType,
            @NotBlank @Size(max = 2000) String jdbcUrl,
            @NotBlank @Size(max = 200) String username,
            @Size(max = 1000) String password,
            Boolean enabled) {
    }

    /** The stored password is never sent back. */
    public record TargetView(long id, String name, DbType dbType, String jdbcUrl, String username, boolean enabled,
                             CollectorManager.Status collector, Double avgActiveSessions,
                             Map<String, Double> byWaitClass, double[] spark) {
    }

    public record TestResult(boolean ok, String message) {
    }

    private final TargetRepository targets;
    private final CollectorManager collectors;
    private final TargetConnections connections;
    private final SecretCipher cipher;
    private final ActivityService activity;

    public TargetController(TargetRepository targets, CollectorManager collectors, TargetConnections connections,
                            SecretCipher cipher, ActivityService activity) {
        this.targets = targets;
        this.collectors = collectors;
        this.connections = connections;
        this.cipher = cipher;
        this.activity = activity;
    }

    private TargetView view(Target t, OverviewRow load) {
        return new TargetView(t.id(), t.name(), t.dbType(), t.jdbcUrl(), t.username(), t.enabled(),
                collectors.status(t.id()),
                load == null ? null : load.avgActiveSessions(),
                load == null ? Map.of() : load.byWaitClass(),
                load == null ? null : load.spark());
    }

    private Target require(long id) {
        return targets.findById(id).orElseThrow(() -> ApiException.notFound("Database not found"));
    }

    /** Every database with its collector status and recent load: the overview page. */
    @GetMapping
    public List<TargetView> list() {
        Map<Long, OverviewRow> load = activity.overview();
        return targets.findAll().stream().map(t -> view(t, load.get(t.id()))).toList();
    }

    @GetMapping("/{id}")
    public TargetView get(@PathVariable long id) {
        return view(require(id), null);
    }

    @PostMapping
    public TargetView create(@Valid @RequestBody TargetInput body) {
        validate(body, null);
        if (body.password() == null || body.password().isEmpty()) {
            throw ApiException.badRequest("Password is required");
        }
        boolean enabled = body.enabled() == null || body.enabled();
        long id = targets.insert(body.name().strip(), body.dbType(), body.jdbcUrl().strip(), body.username().strip(),
                cipher.encrypt(body.password()), enabled);
        Target created = require(id);
        if (enabled) {
            collectors.start(created);
        }
        return view(created, null);
    }

    @PutMapping("/{id}")
    public TargetView update(@PathVariable long id, @Valid @RequestBody TargetInput body) {
        Target existing = require(id);
        validate(body, id);
        boolean keepPassword = body.password() == null || body.password().isEmpty();
        Target updated = new Target(id, body.name().strip(), body.dbType(), body.jdbcUrl().strip(),
                body.username().strip(), keepPassword ? existing.passwordEnc() : cipher.encrypt(body.password()),
                body.enabled() == null ? existing.enabled() : body.enabled());
        targets.update(updated);
        if (updated.enabled()) {
            collectors.start(updated);
        } else {
            collectors.stop(id);
        }
        return view(updated, null);
    }

    @PostMapping("/{id}/start")
    public TargetView start(@PathVariable long id) {
        require(id);
        targets.setEnabled(id, true);
        Target target = require(id);
        collectors.start(target);
        return view(target, null);
    }

    @PostMapping("/{id}/stop")
    public TargetView stop(@PathVariable long id) {
        require(id);
        targets.setEnabled(id, false);
        collectors.stop(id);
        return view(require(id), null);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        require(id);
        collectors.stop(id);
        targets.delete(id);
    }

    /**
     * Tries the connection settings without saving them. When editing with the password left empty,
     * pass the id so the stored password is used.
     */
    @PostMapping("/test")
    public TestResult test(@Valid @RequestBody TargetInput body, @RequestParam(required = false) Long id) {
        validateUrl(body);
        String password = body.password();
        if (password == null || password.isEmpty()) {
            if (id == null) {
                throw ApiException.badRequest("Password is required");
            }
            password = cipher.decrypt(require(id).passwordEnc());
        }
        try {
            String product = connections.test(body.dbType(), body.jdbcUrl().strip(), body.username().strip(),
                    password);
            return new TestResult(true, "Connected: " + product.lines().findFirst().orElse(""));
        } catch (Exception e) {
            return new TestResult(false, CollectorManager.describe(e));
        }
    }

    @GetMapping("/{id}/activity")
    public TimeSeries activity(@PathVariable long id, @RequestParam Instant from, @RequestParam Instant to,
                               @RequestParam(required = false) String waitClass,
                               @RequestParam(required = false) String sqlId,
                               @RequestParam(required = false) Long sessionId,
                               @RequestParam(required = false) Long sessionSerial) {
        require(id);
        checkRange(from, to);
        return activity.timeSeries(id, from, to, new Filter(waitClass, sqlId, sessionId, sessionSerial));
    }

    @GetMapping("/{id}/top")
    public TopResult top(@PathVariable long id, @RequestParam Instant from, @RequestParam Instant to,
                         @RequestParam Dimension dimension, @RequestParam(required = false) String waitClass,
                         @RequestParam(required = false) String sqlId,
                         @RequestParam(required = false) Long sessionId,
                         @RequestParam(required = false) Long sessionSerial,
                         @RequestParam(defaultValue = "30") int limit) {
        require(id);
        checkRange(from, to);
        return activity.top(id, from, to, dimension, new Filter(waitClass, sqlId, sessionId, sessionSerial),
                Math.max(1, Math.min(limit, 200)));
    }

    /** What is stored about one session: who it was and when it was active. */
    @GetMapping("/{id}/session/{sessionId}/{sessionSerial}")
    public SessionInfo sessionInfo(@PathVariable long id, @PathVariable long sessionId,
                                   @PathVariable long sessionSerial) {
        require(id);
        SessionInfo info = activity.sessionInfo(id, sessionId, sessionSerial);
        if (info == null) {
            throw ApiException.notFound("Nothing is stored about this session");
        }
        return info;
    }

    /** The grid pages through at most this many samples, and a download holds at most this many. */
    private static final int MAX_SAMPLES = 100_000;

    /**
     * One page of stored samples.
     *
     * @param total       samples in the period, counted up to the limit of the grid
     * @param totalCapped true when there are more than that
     * @param keptSince   older samples have been removed
     * @param detailKeys  the source specific values each row may carry, in display order
     */
    public record SamplesPage(Instant from, Instant to, Instant keptSince, List<String> detailKeys, long total,
                              boolean totalCapped, int offset, List<SampleRow> rows) {
    }

    @GetMapping("/{id}/samples")
    public SamplesPage samples(@PathVariable long id, @RequestParam Instant from, @RequestParam Instant to,
                               @RequestParam(required = false) String waitClass,
                               @RequestParam(required = false) String sqlId,
                               @RequestParam(required = false) Long sessionId,
                               @RequestParam(required = false) Long sessionSerial,
                               @RequestParam(defaultValue = "desc") String order,
                               @RequestParam(defaultValue = "200") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        Target target = require(id);
        checkRange(from, to);
        Filter filter = new Filter(waitClass, sqlId, sessionId, sessionSerial);
        List<String> keys = target.dbType().sampleDetails();
        offset = Math.max(0, Math.min(offset, MAX_SAMPLES));
        List<SampleRow> rows = new ArrayList<>();
        activity.forEachSample(id, from, to, filter, order.equals("asc"), Math.max(1, Math.min(limit, 1000)), offset,
                keys, rows::add);
        long total = activity.countSamples(id, from, to, filter, MAX_SAMPLES);
        return new SamplesPage(from, to, activity.samplesKeptSince(), keys, Math.min(total, MAX_SAMPLES),
                total > MAX_SAMPLES, offset, rows);
    }

    /** The same samples as a spreadsheet file, oldest first. */
    @GetMapping("/{id}/samples.csv")
    public ResponseEntity<StreamingResponseBody> samplesCsv(
            @PathVariable long id, @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String waitClass, @RequestParam(required = false) String sqlId,
            @RequestParam(required = false) Long sessionId, @RequestParam(required = false) Long sessionSerial) {
        Target target = require(id);
        checkRange(from, to);
        Filter filter = new Filter(waitClass, sqlId, sessionId, sessionSerial);
        List<String> keys = target.dbType().sampleDetails();
        StreamingResponseBody body = out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            // The mark makes spreadsheet programs read the file as UTF-8
            writer.write('\uFEFF');
            writer.write("sample_time_utc,session_id,session_serial,user_name,program,sql_id,sql_type,wait_class,event");
            for (String key : keys) {
                writer.write(',');
                writer.write(key);
            }
            writer.write("\r\n");
            try {
                activity.forEachSample(id, from, to, filter, true, MAX_SAMPLES, 0, keys, row -> {
                    try {
                        writer.write(row.time().toString());
                        writer.write("," + row.sessionId() + "," + row.sessionSerial());
                        for (String text : new String[] {row.userName(), row.program(), row.sqlId(),
                                row.sqlOpname(), row.waitClass(), row.event()}) {
                            writer.write(',');
                            writer.write(csv(text));
                        }
                        for (String key : keys) {
                            writer.write(',');
                            writer.write(csv(row.details().get(key)));
                        }
                        writer.write("\r\n");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ash-samples-" + id + "-" + from.getEpochSecond() + ".csv\"")
                .body(body);
    }

    /**
     * A value as a CSV field. Values that a spreadsheet would run as a formula get a leading apostrophe,
     * because program and module names are chosen by whoever connects to the monitored database.
     */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if ("=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** What is stored about a statement: its text and type. Either may be null. */
    public record SqlInfo(String sqlId, String sqlOpname, String sqlText) {
    }

    @GetMapping("/{id}/sql/{sqlId}")
    public SqlInfo sqlInfo(@PathVariable long id, @PathVariable String sqlId) {
        require(id);
        return new SqlInfo(sqlId, activity.sqlOpname(id, sqlId), activity.sqlText(id, sqlId));
    }

    /** Plans and statistics, read from the monitored database at this moment. */
    @GetMapping("/{id}/sql/{sqlId}/live")
    public SqlLiveDetail sqlLive(@PathVariable long id, @PathVariable String sqlId) {
        Target target = require(id);
        if (!sqlId.matches("[A-Za-z0-9]{1,40}")) {
            throw ApiException.badRequest("Not a statement id");
        }
        try (Connection connection = connections.open(target.dbType(), target.jdbcUrl(), target.username(),
                cipher.decrypt(target.passwordEnc()))) {
            return connections.sourceFor(target.dbType()).fetchSqlDetail(connection, sqlId);
        } catch (Exception e) {
            return new SqlLiveDetail(List.of(), List.of(),
                    List.of("The database could not be reached: " + CollectorManager.describe(e)));
        }
    }

    private void validate(TargetInput body, Long id) {
        validateUrl(body);
        if (targets.nameTaken(body.name().strip(), id)) {
            throw ApiException.badRequest("A database named '" + body.name().strip() + "' already exists");
        }
    }

    private static void validateUrl(TargetInput body) {
        if (!body.jdbcUrl().strip().startsWith(body.dbType().urlPrefix())) {
            throw ApiException.badRequest("The URL must start with " + body.dbType().urlPrefix());
        }
    }

    private static void checkRange(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("'from' must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("The time range is too long");
        }
    }
}
