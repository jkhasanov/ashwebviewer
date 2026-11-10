# ASH Web Viewer

A central web server that shows database "Top Activity" (active session history) for many databases at
once. It is a web re-implementation of the ideas in the desktop tool
[ASH-Viewer](https://github.com/akardapolov/ASH-Viewer).

- **Oracle Enterprise Edition**: reads `v$active_session_history` (requires the Diagnostic Pack licence).
- **PostgreSQL**: samples `pg_stat_activity` once a second.

## What it does (version 0.2)

- Overview of every database: collector status, current load, wait mix, last hour.
- Top Activity per database: stacked chart of average active sessions by wait class, live (last hour,
  refreshing) or history (8 h, 12 h, 1 day, 1 week, 1 month, custom range).
- Drag across the chart to rank the top SQL and top sessions of that period.
- Filter to one wait class to see it broken down by wait event.
- SQL detail: click a SQL ID for its full text, its activity over time, the sessions that ran it, and
  (Oracle) its execution plans and cumulative statistics read live from the database.
- Session detail: click a session for who it was (user, program, machine, module or client), its activity
  over time and the statements it ran.
- Raw samples: from any chart, statement or session, open the individual samples behind the selected
  period, page through them and download them as CSV.
- Oracle ASH report: have the database write its own ASH report for the selected period (optionally
  for one statement, session or wait class), read it in the page and download it.
- Ten visual styles, switchable from the top bar.
- Admins add, edit, test, start, stop and delete databases, and manage users. Viewers are read-only.
- Retention is set for the server and can be overridden per database.

Not yet built: 10046 tracing, Oracle Standard Edition, Oracle RAC (`gv$`).

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

A ready-built `ashweb.jar` is attached to each
[release](https://github.com/jkhasanov/ashwebviewer/releases), so the server itself only needs Java 21.

### HTTPS

Put the server behind HTTPS before using it outside a trusted network. `deploy/nginx.conf` is a working
example for nginx. After it is in place, uncomment the three HTTPS settings in
`/opt/ashweb/config/application.yml` (`forward-headers-strategy` and the secure session cookie) and
restart the service.

### Monitoring

`GET /api/health` needs no sign-in and answers `{"status":"UP"}`, or status 503 when the storage
database cannot be reached. The state of each collector is on the Overview page.

### Retention

`ashweb.retention` in `application.yml` sets how many days of samples and of per-minute totals are kept
(7 and 90 unless changed). An admin can give a database its own values under Databases > Edit. Storage is
freed by dropping whole daily partitions once they are older than the longest retention in use; databases
that keep less have their older rows deleted hourly.

### Backup and upgrade

- Back up the storage database (`pg_dump ashweb`) and the key file (`/opt/ashweb/data/master.key`).
  The key file is small and never changes; without it the stored database passwords cannot be read.
- To upgrade, replace `/opt/ashweb/ashweb.jar` and run `sudo systemctl restart ashweb`. The storage
  database is brought up to date automatically on start. Collection pauses during the restart; Oracle
  databases are caught up afterwards from their own history, PostgreSQL databases have a gap.

## Accounts needed on monitored databases

Oracle:

```sql
create user ashweb identified by "...";
grant create session to ashweb;
grant select on v_$active_session_history to ashweb;
grant select on v_$sqlstats to ashweb;
grant select on v_$sql_plan to ashweb;   -- for execution plans on the SQL detail page
grant execute on dbms_workload_repository to ashweb;   -- for the ASH report
```

PostgreSQL:

```sql
create user ashweb password '...';
grant pg_read_all_stats to ashweb;
```

For PostgreSQL 14+, setting `compute_query_id = on` groups statements that differ only in literal values.

## Licence

GNU General Public License v3.0, the same licence as the original ASH-Viewer, whose approach and queries
against the database activity views this project reuses. See [LICENSE](LICENSE).
