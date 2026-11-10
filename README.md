# ASH Web Viewer

A central web server that shows database "Top Activity" (active session history) for many databases at
once. It is a web re-implementation of the ideas in the desktop tool
[ASH-Viewer](https://github.com/akardapolov/ASH-Viewer).

- **Oracle Enterprise Edition**: reads `v$active_session_history` (requires the Diagnostic Pack licence).
- **PostgreSQL**: samples `pg_stat_activity` once a second.

## What it does (version 0.1)

- Overview of every database: collector status, current load, wait mix, last hour.
- Top Activity per database: stacked chart of average active sessions by wait class, live (last hour,
  refreshing) or history (8 h, 12 h, 1 day, 1 week, 1 month, custom range).
- Drag across the chart to rank the top SQL and top sessions of that period.
- Filter to one wait class to see it broken down by wait event.
- SQL detail: click a SQL ID for its full text, its activity over time, the sessions that ran it, and
  (Oracle) its execution plans and cumulative statistics read live from the database.
- Ten visual styles, switchable from the top bar.
- Admins add, edit, test, start, stop and delete databases, and manage users. Viewers are read-only.

Not yet built: session detail, raw sample grid, Oracle ASH report,
10046 tracing, Oracle Standard Edition, Oracle RAC (`gv$`).

## How it is put together

```
backend/    Spring Boot 4 (Java 21). Collector, storage, REST API, login. Serves the built front end.
frontend/   React + TypeScript + Vite, charts with Apache ECharts.
deploy/     systemd unit and example configuration.
```

- One collector per database runs on its own virtual thread with a two-connection pool, so a slow or
  unreachable database does not delay the others. Failing collectors back off (up to one minute).
- Storage is PostgreSQL 14+. `ash_sample` holds one row per active session per second;
  `ash_rollup_1m` holds per-minute counts. Both are partitioned by day and retention drops whole
  partitions. Ranges up to 3 hours inside raw retention are answered from samples, longer ones from the
  rollup.
- Passwords of monitored databases are encrypted with AES-256-GCM. The key is a file on the server
  (`ashweb.security.master-key-file`), not in the database. **Back the key file up**; without it the
  stored passwords cannot be read and must be entered again.

## Build

Needs JDK 21+, Maven 3.9+ and Node 20+.

```
cd frontend && npm install && npm run build
cd ../backend && mvn package
```

The result is `backend/target/ashweb.jar`, which includes the front end. `mvn package` runs the
integration tests against a temporary PostgreSQL that it downloads and starts itself.

## Run for development

```
cd backend
mvn test-compile exec:java -Dexec.mainClass=com.ashweb.DevServer -Dexec.classpathScope=test
```

This starts a local PostgreSQL (data kept in `backend/target/dev`), the application on http://localhost:8080 and some demo load.
Sign in with `admin` / `admin12345`. For front-end work also run `npm run dev` in `frontend/` and open
http://localhost:5173 (API calls are proxied to port 8080).

## Install on a Linux server

1. Create the storage database:
   ```sql
   create user ashweb password 'change-me';
   create database ashweb owner ashweb;
   ```
2. Install the application:
   ```
   sudo useradd --system --home /opt/ashweb ashweb
   sudo mkdir -p /opt/ashweb/config /opt/ashweb/data
   sudo cp backend/target/ashweb.jar /opt/ashweb/
   sudo cp deploy/application.yml /opt/ashweb/config/     # then edit it
   sudo chown -R ashweb:ashweb /opt/ashweb && sudo chmod 700 /opt/ashweb/data /opt/ashweb/config
   sudo cp deploy/ashweb.service /etc/systemd/system/
   sudo systemctl daemon-reload && sudo systemctl enable --now ashweb
   ```
3. Read the first admin password from the log (`journalctl -u ashweb | grep "Created user"`), sign in at
   `http://server:8080` and change it.

Put the server behind HTTPS (for example nginx) before using it outside a trusted network; see the
comments in `deploy/application.yml`.

## Accounts needed on monitored databases

Oracle:

```sql
create user ashweb identified by "...";
grant create session to ashweb;
grant select on v_$active_session_history to ashweb;
grant select on v_$sqlstats to ashweb;
grant select on v_$sql_plan to ashweb;   -- for execution plans on the SQL detail page
```

PostgreSQL:

```sql
create user ashweb password '...';
grant pg_read_all_stats to ashweb;
```

For PostgreSQL 14+, setting `compute_query_id = on` groups statements that differ only in literal values.

## Licence note

The original ASH-Viewer is GPLv3. This project is new code; it reuses the approach and the queries
against the database activity views. If you distribute it, check whether that makes GPLv3 apply to you.
