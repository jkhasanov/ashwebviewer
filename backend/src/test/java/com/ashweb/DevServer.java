package com.ashweb;

import com.ashweb.collector.CollectorManager;
import com.ashweb.security.SecretCipher;
import com.ashweb.target.DbType;
import com.ashweb.target.TargetRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Development launcher: starts a throwaway PostgreSQL, runs the application against it, registers that same
 * PostgreSQL as a monitored database and generates some load on it, so every screen has data to show.
 *
 * Sign in with admin / admin12345.
 */
public class DevServer {

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(54329)
                // Kept between runs, so databases added while trying things out are still there next time
                // (until "mvn clean" removes target/)
                .setDataDirectory(java.nio.file.Path.of("target/dev/pgdata").toAbsolutePath())
                .setCleanDataDirectory(false)
                .start();
        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";

        System.setProperty("spring.datasource.url", url + "?reWriteBatchedInserts=true");
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("ashweb.security.master-key-file", "target/dev/master.key");
        System.setProperty("ashweb.security.initial-admin-password", "admin12345");

        ConfigurableApplicationContext context = SpringApplication.run(AshWebApplication.class, args);

        TargetRepository targets = context.getBean(TargetRepository.class);
        if (targets.findAll().isEmpty()) {
            SecretCipher cipher = context.getBean(SecretCipher.class);
            CollectorManager collectors = context.getBean(CollectorManager.class);
            long id = targets.insert("demo-postgres", DbType.POSTGRES, url, "postgres", cipher.encrypt("postgres"),
                    true);
            collectors.start(targets.findById(id).orElseThrow());
            long broken = targets.insert("demo-unreachable", DbType.POSTGRES,
                    "jdbc:postgresql://localhost:1/nothing", "nobody", cipher.encrypt("x"), true);
            collectors.start(targets.findById(broken).orElseThrow());
        }
        startLoad(url);
    }

    /** A few sessions doing different kinds of work: CPU, sleeping (Timeout waits) and row lock contention. */
    static void startLoad(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "postgres", "postgres"); Statement st = c.createStatement()) {
            st.execute("create table if not exists demo_lock (id int primary key, n bigint not null default 0)");
            st.execute("insert into demo_lock values (1, 0) on conflict do nothing");
        }
        String[] statements = {
                "select count(*) from generate_series(1, 3000000) g where md5(g::text) like 'a%'",
                "select pg_sleep(1.5)",
                "select sum(g) from generate_series(1, 2000000) g",
                "LOCK",
                "LOCK",
        };
        for (int i = 0; i < statements.length; i++) {
            String statement = statements[i];
            Thread t = new Thread(() -> {
                while (true) {
                    try (Connection c = DriverManager.getConnection(url + "?ApplicationName=demo-load", "postgres",
                            "postgres"); Statement st = c.createStatement()) {
                        while (true) {
                            if (statement.equals("LOCK")) {
                                c.setAutoCommit(false);
                                st.execute("update demo_lock set n = n + 1 where id = 1");
                                st.execute("select pg_sleep(0.8)");
                                c.commit();
                            } else {
                                st.execute(statement);
                            }
                            Thread.sleep(ThreadLocalRandom.current().nextInt(200, 2500));
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }, "demo-load-" + i);
            t.setDaemon(true);
            t.start();
        }
    }
}
