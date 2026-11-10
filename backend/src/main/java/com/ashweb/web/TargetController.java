package com.ashweb.web;

import com.ashweb.activity.ActivityService;
import com.ashweb.activity.ActivityService.Dimension;
import com.ashweb.activity.ActivityService.OverviewRow;
import com.ashweb.activity.ActivityService.TimeSeries;
import com.ashweb.activity.ActivityService.TopResult;
import com.ashweb.collector.CollectorManager;
import com.ashweb.collector.TargetConnections;
import com.ashweb.security.SecretCipher;
import com.ashweb.target.DbType;
import com.ashweb.target.Target;
import com.ashweb.target.TargetRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
                               @RequestParam(required = false) String waitClass) {
        require(id);
        checkRange(from, to);
        return activity.timeSeries(id, from, to, waitClass);
    }

    @GetMapping("/{id}/top")
    public TopResult top(@PathVariable long id, @RequestParam Instant from, @RequestParam Instant to,
                         @RequestParam Dimension dimension, @RequestParam(required = false) String waitClass,
                         @RequestParam(defaultValue = "30") int limit) {
        require(id);
        checkRange(from, to);
        return activity.top(id, from, to, dimension, waitClass, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/{id}/sql/{sqlId}")
    public Map<String, String> sqlText(@PathVariable long id, @PathVariable String sqlId) {
        require(id);
        String text = activity.sqlText(id, sqlId);
        if (text == null) {
            throw ApiException.notFound("No text stored for this statement");
        }
        return Map.of("sqlId", sqlId, "sqlText", text);
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
