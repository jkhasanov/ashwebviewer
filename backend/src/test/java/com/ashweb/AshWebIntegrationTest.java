package com.ashweb;

import static org.assertj.core.api.Assertions.assertThat;

import com.ashweb.activity.ActivityService;
import com.ashweb.activity.ActivityService.Dimension;
import com.ashweb.activity.ActivityService.Filter;
import com.ashweb.collector.CollectorManager;
import com.ashweb.storage.PartitionManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End to end over real HTTP against a real PostgreSQL: sign in, register the storage database itself as a
 * monitored PostgreSQL, let the collector sample a busy session, and read the result back through the API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AshWebIntegrationTest {

    static EmbeddedPostgres postgres;
    static String url;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.start();
        url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("ashweb.security.master-key-file", "target/test-data/master.key");
        System.setProperty("ashweb.security.initial-admin-password", "test-admin-pw");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        postgres.close();
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    ActivityService activity;
    @Autowired
    CollectorManager collectors;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PartitionManager partitions;

    static final CookieManager adminCookies = new CookieManager();
    static final HttpClient admin = HttpClient.newBuilder().cookieHandler(adminCookies).build();
    static final CookieManager viewerCookies = new CookieManager();
    static final HttpClient viewer = HttpClient.newBuilder().cookieHandler(viewerCookies).build();
    static long targetId;

    HttpResponse<String> send(HttpClient client, CookieManager cookies, String method, String path, String json)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst()
                .ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static long idIn(String json) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        assertThat(m.find()).as("id in %s", json).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    @Order(1)
    void apiNeedsLogin() throws Exception {
        assertThat(send(admin, adminCookies, "GET", "/api/targets", null).statusCode()).isEqualTo(401);
        assertThat(send(admin, adminCookies, "POST", "/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"wrong\"}").statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void adminLogsInAndManagesUsers() throws Exception {
        HttpResponse<String> login = send(admin, adminCookies, "POST", "/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"test-admin-pw\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"role\":\"ADMIN\"");
        assertThat(send(admin, adminCookies, "GET", "/api/auth/me", null).body()).contains("\"username\":\"admin\"");

        HttpResponse<String> created = send(admin, adminCookies, "POST", "/api/users",
                "{\"username\":\"vera\",\"password\":\"viewer-pw-1\",\"role\":\"VIEWER\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);

        // The last admin cannot be demoted
        long adminId = idIn(send(admin, adminCookies, "GET", "/api/users", null).body()
                .replaceAll(".*(\\{[^{}]*\"username\":\"admin\"[^{}]*\\}).*", "$1"));
        assertThat(send(admin, adminCookies, "PUT", "/api/users/" + adminId,
                "{\"role\":\"VIEWER\",\"enabled\":true}").statusCode()).isEqualTo(400);
    }

    @Test
    @Order(3)
    void adminAddsDatabaseAndPasswordStaysSecret() throws Exception {
        String body = "{\"name\":\"self\",\"dbType\":\"POSTGRES\",\"jdbcUrl\":\"" + url
                + "\",\"username\":\"postgres\",\"password\":\"postgres\"}";
        HttpResponse<String> test = send(admin, adminCookies, "POST", "/api/targets/test", body);
        assertThat(test.body()).contains("\"ok\":true");

        HttpResponse<String> bad = send(admin, adminCookies, "POST", "/api/targets/test",
                body.replace(url, "jdbc:postgresql://localhost:1/none"));
        assertThat(bad.body()).contains("\"ok\":false");

        assertThat(send(admin, adminCookies, "POST", "/api/targets",
                body.replace("jdbc:postgresql:", "jdbc:h2:")).statusCode()).isEqualTo(400);

        // Retention of one database: within limits, and totals kept at least as long as samples
        String open = body.substring(0, body.length() - 1);
        assertThat(send(admin, adminCookies, "POST", "/api/targets", open + ",\"rawDays\":0}").statusCode())
                .isEqualTo(400);
        HttpResponse<String> tooShort = send(admin, adminCookies, "POST", "/api/targets",
                open + ",\"rawDays\":30,\"rollupDays\":10}");
        assertThat(tooShort.statusCode()).isEqualTo(400);
        assertThat(tooShort.body()).contains("at least as long");

        HttpResponse<String> created = send(admin, adminCookies, "POST", "/api/targets", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        assertThat(created.body()).doesNotContain("password").contains("\"rawDays\":null")
                .contains("\"defaultRawDays\":7").contains("\"defaultRollupDays\":90");
        targetId = idIn(created.body());

        String stored = jdbc.queryForObject("select password_enc from target where id = ?", String.class, targetId);
        assertThat(stored).isNotEqualTo("postgres").hasSizeGreaterThan(20);
    }

    @Test
    @Order(4)
    void collectorSamplesActivityAndApiReportsIt() throws Exception {
        Instant from = Instant.now().minusSeconds(5);
        // A session that is busy for about eight seconds
        Thread busy = new Thread(() -> {
            try (Connection c = DriverManager.getConnection(url + "?ApplicationName=it-load", "postgres", "postgres");
                 Statement st = c.createStatement()) {
                st.execute("select pg_sleep(8) /* ashweb-it */");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        busy.start();
        busy.join();
        Thread.sleep(2500);

        CollectorManager.Status status = collectors.status(targetId);
        assertThat(status.state()).as("collector error: %s", status.lastError())
                .isEqualTo(CollectorManager.State.RUNNING);
        assertThat(status.rowsCollected()).isGreaterThanOrEqualTo(5);

        Instant to = Instant.now();
        HttpResponse<String> sessions = send(admin, adminCookies, "GET", "/api/targets/" + targetId
                + "/top?dimension=SESSION&from=" + from + "&to=" + to, null);
        assertThat(sessions.statusCode()).as(sessions.body()).isEqualTo(200);
        assertThat(sessions.body()).contains("\"program\":\"it-load\"").contains("\"Timeout\"");

        HttpResponse<String> sql = send(admin, adminCookies, "GET", "/api/targets/" + targetId
                + "/top?dimension=SQL&from=" + from + "&to=" + to, null);
        assertThat(sql.body()).contains("ashweb-it").contains("\"sqlOpname\":\"SELECT\"");

        // pg_sleep shows up as about one active session waiting on Timeout/PgSleep for about eight seconds
        var top = activity.top(targetId, from, to, Dimension.SESSION, Filter.waitClass("Timeout"), 10);
        assertThat(top.rows()).isNotEmpty();
        assertThat(top.rows().get(0).samples()).isBetween(5L, 10L);

        // Charts deliberately stop ten seconds short of "now", so wait until the busy period is behind that edge
        Thread.sleep(15_000);
        var series = activity.timeSeries(targetId, Instant.now().minus(Duration.ofMinutes(10)), Instant.now(), Filter.NONE);
        assertThat(series.bucketSeconds()).isEqualTo(5);
        assertThat(series.series()).extracting(ActivityService.Series::name).contains("Timeout");
        double timeoutSeconds = series.series().stream().filter(s -> s.name().equals("Timeout"))
                .flatMapToDouble(s -> java.util.Arrays.stream(s.values())).sum() * series.bucketSeconds();
        assertThat(timeoutSeconds).isBetween(4.0, 10.5);

        var events = activity.timeSeries(targetId, Instant.now().minus(Duration.ofMinutes(10)), Instant.now(),
                Filter.waitClass("Timeout"));
        assertThat(events.series()).extracting(ActivityService.Series::name).contains("PgSleep");

        // The same answer must come from the per-minute rollup (used for long ranges)
        Long rolled = jdbc.queryForObject("""
                select sum(samples) from ash_rollup_1m where target_id = ? and wait_class = 'Timeout'""",
                Long.class, targetId);
        Long raw = jdbc.queryForObject("""
                select count(*) from ash_sample where target_id = ? and wait_class = 'Timeout'""",
                Long.class, targetId);
        assertThat(rolled).isEqualTo(raw);
        var longRange = activity.top(targetId, Instant.now().minus(Duration.ofHours(12)), Instant.now(),
                Dimension.SESSION, Filter.waitClass("Timeout"), 10);
        assertThat(longRange.totalSamples()).isEqualTo(raw);

        // SQL detail: activity and sessions narrowed to one statement, plus what the database itself reports
        Matcher sqlIdMatch = Pattern.compile("\\{\"sqlId\":\"([0-9a-f]+)\"[^{}]*ashweb-it").matcher(sql.body());
        assertThat(sqlIdMatch.find()).isTrue();
        String sqlId = sqlIdMatch.group(1);
        var forSql = activity.top(targetId, from, to, Dimension.SESSION, Filter.sql(sqlId), 10);
        assertThat(forSql.rows()).hasSize(1);
        assertThat(forSql.rows().get(0).program()).isEqualTo("it-load");
        assertThat(activity.top(targetId, from, to, Dimension.SESSION, Filter.sql("nosuchsql"), 10).rows()).isEmpty();
        var sqlSeries = activity.timeSeries(targetId, Instant.now().minus(Duration.ofMinutes(10)), Instant.now(),
                Filter.sql(sqlId));
        assertThat(sqlSeries.series()).extracting(ActivityService.Series::name).containsExactly("Timeout");
        HttpResponse<String> info = send(admin, adminCookies, "GET", "/api/targets/" + targetId + "/sql/" + sqlId,
                null);
        assertThat(info.body()).contains("ashweb-it").contains("\"sqlOpname\":\"SELECT\"");
        HttpResponse<String> live = send(admin, adminCookies, "GET",
                "/api/targets/" + targetId + "/sql/" + sqlId + "/live", null);
        assertThat(live.statusCode()).as(live.body()).isEqualTo(200);
        assertThat(live.body()).contains("\"plans\":[]").contains("does not keep execution plans");

        // Session detail: the session that ran the load, its statements, and what is known about it
        long sid = forSql.rows().get(0).sessionId();
        long serial = forSql.rows().get(0).sessionSerial();
        var sessionSql = activity.top(targetId, from, to, Dimension.SQL, Filter.session(sid, serial), 10);
        assertThat(sessionSql.rows()).extracting(ActivityService.TopRow::sqlId).contains(sqlId);
        assertThat(sessionSql.totalSamples()).isEqualTo(forSql.totalSamples());
        assertThat(activity.top(targetId, from, to, Dimension.SQL, Filter.session(sid, serial + 1), 10).rows())
                .isEmpty();
        HttpResponse<String> sessionSeries = send(admin, adminCookies, "GET", "/api/targets/" + targetId
                + "/activity?from=" + Instant.now().minus(Duration.ofMinutes(10)) + "&to=" + Instant.now()
                + "&sessionId=" + sid + "&sessionSerial=" + serial, null);
        assertThat(sessionSeries.statusCode()).as(sessionSeries.body()).isEqualTo(200);
        assertThat(sessionSeries.body()).contains("\"name\":\"Timeout\"");
        HttpResponse<String> sessionInfo = send(admin, adminCookies, "GET",
                "/api/targets/" + targetId + "/session/" + sid + "/" + serial, null);
        assertThat(sessionInfo.statusCode()).as(sessionInfo.body()).isEqualTo(200);
        assertThat(sessionInfo.body()).contains("\"program\":\"it-load\"").contains("\"backendType\"")
                .contains("\"samples\":" + forSql.totalSamples());
        assertThat(send(admin, adminCookies, "GET", "/api/targets/" + targetId + "/session/" + sid + "/1", null)
                .statusCode()).isEqualTo(404);

        // Raw samples: the rows behind the charts, as pages and as a file
        String sampleQuery = "?from=" + from + "&to=" + Instant.now() + "&sessionId=" + sid + "&sessionSerial="
                + serial + "&waitClass=Timeout";
        Long timeoutSamples = jdbc.queryForObject("""
                select count(*) from ash_sample
                 where target_id = ? and session_id = ? and session_serial = ? and wait_class = 'Timeout'""",
                Long.class, targetId, sid, serial);
        HttpResponse<String> samples = send(admin, adminCookies, "GET",
                "/api/targets/" + targetId + "/samples" + sampleQuery + "&limit=3", null);
        assertThat(samples.statusCode()).as(samples.body()).isEqualTo(200);
        assertThat(samples.body()).contains("\"total\":" + timeoutSamples + ",").contains("\"event\":\"PgSleep\"")
                .contains("\"backendType\":\"client backend\"").contains("\"totalCapped\":false");
        assertThat(samples.body().split("\"event\":", -1)).hasSize(4);
        HttpResponse<String> csv = send(admin, adminCookies, "GET",
                "/api/targets/" + targetId + "/samples.csv" + sampleQuery, null);
        assertThat(csv.statusCode()).as(csv.body()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.body()).contains("sample_time_utc,session_id").contains(",it-load,").contains(",PgSleep,");
        assertThat(csv.body().strip().split("\r\n")).hasSize((int) (timeoutSamples + 1));

        HttpResponse<String> overview = send(admin, adminCookies, "GET", "/api/targets", null);
        assertThat(overview.body()).contains("\"state\":\"RUNNING\"");
    }

    @Test
    @Order(5)
    void viewerCanReadButNotChange() throws Exception {
        assertThat(send(viewer, viewerCookies, "POST", "/api/auth/login",
                "{\"username\":\"vera\",\"password\":\"viewer-pw-1\"}").statusCode()).isEqualTo(200);
        assertThat(send(viewer, viewerCookies, "GET", "/api/targets", null).statusCode()).isEqualTo(200);
        assertThat(send(viewer, viewerCookies, "GET", "/api/users", null).statusCode()).isEqualTo(403);
        assertThat(send(viewer, viewerCookies, "POST", "/api/targets/" + targetId + "/stop", null).statusCode())
                .isEqualTo(403);
        assertThat(send(viewer, viewerCookies, "DELETE", "/api/targets/" + targetId, null).statusCode())
                .isEqualTo(403);
    }

    @Test
    @Order(6)
    void changeWithoutCsrfTokenIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/targets/" + targetId + "/stop"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(admin.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(collectors.status(targetId).state()).isNotEqualTo(CollectorManager.State.STOPPED);
    }

    @Test
    @Order(7)
    void stopAndDeleteRemoveCollectorAndData() throws Exception {
        assertThat(send(admin, adminCookies, "POST", "/api/targets/" + targetId + "/stop", null).statusCode())
                .isEqualTo(200);
        assertThat(collectors.status(targetId).state()).isEqualTo(CollectorManager.State.STOPPED);
        assertThat(send(admin, adminCookies, "DELETE", "/api/targets/" + targetId, null).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from ash_sample where target_id = ?", Long.class, targetId))
                .isZero();
    }

    @Test
    @Order(8)
    void retentionDropsOldPartitionsAndHealthNeedsNoSignIn() throws Exception {
        HttpResponse<String> health = send(HttpClient.newHttpClient(), new CookieManager(), "GET", "/api/health",
                null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");

        java.time.LocalDate old = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30);
        partitions.ensure(PartitionManager.RAW, old);
        String name = "ash_sample_p" + java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(old);
        assertThat(jdbc.queryForObject("select count(*) from pg_class where relname = ?", Long.class, name))
                .isEqualTo(1);
        partitions.maintain();
        assertThat(jdbc.queryForObject("select count(*) from pg_class where relname = ?", Long.class, name))
                .isZero();
        // A database with its own, shorter retention loses its older rows; the others keep theirs
        Long shortId = jdbc.queryForObject("""
                insert into target (name, db_type, jdbc_url, username, password_enc, enabled, raw_days)
                values ('short', 'POSTGRES', 'jdbc:postgresql://localhost:1/x', 'u', 'x', false, 2)
                returning id""", Long.class);
        Long defaultId = jdbc.queryForObject("""
                insert into target (name, db_type, jdbc_url, username, password_enc, enabled)
                values ('default', 'POSTGRES', 'jdbc:postgresql://localhost:1/x', 'u', 'x', false)
                returning id""", Long.class);
        java.time.LocalDate threeDaysAgo = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(3);
        partitions.ensure(PartitionManager.RAW, threeDaysAgo);
        for (Long id : java.util.List.of(shortId, defaultId)) {
            jdbc.update("""
                    insert into ash_sample (target_id, sample_time, session_id, session_serial, wait_class, event)
                    values (?, ?, 1, 1, 'CPU', 'CPU'), (?, now(), 1, 1, 'CPU', 'CPU')""",
                    id, threeDaysAgo.atTime(12, 0).atOffset(java.time.ZoneOffset.UTC), id);
        }
        partitions.maintain();
        assertThat(jdbc.queryForObject("select count(*) from ash_sample where target_id = ?", Long.class, shortId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ash_sample where target_id = ?", Long.class, defaultId))
                .isEqualTo(2);
        assertThat(partitions.rawCutoff(shortId)).isAfter(partitions.rawCutoff(defaultId));

        // Rollup retention is longer (90 days), so a 30 day old rollup partition stays
        partitions.ensure(PartitionManager.ROLLUP, old);
        partitions.maintain();
        assertThat(jdbc.queryForObject("select count(*) from pg_class where relname = ?", Long.class,
                name.replace("ash_sample", "ash_rollup_1m"))).isEqualTo(1);
    }
}
