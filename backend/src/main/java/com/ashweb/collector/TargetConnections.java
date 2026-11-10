package com.ashweb.collector;

import com.ashweb.config.AshWebProperties;
import com.ashweb.target.DbType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.springframework.stereotype.Component;

/** Builds connections to monitored databases with timeouts so a hung database cannot hang a collector. */
@Component
public class TargetConnections {

    private final AshWebProperties properties;

    public TargetConnections(AshWebProperties properties) {
        this.properties = properties;
    }

    public AshSource sourceFor(DbType type) {
        AshWebProperties.Collector c = properties.collector();
        return switch (type) {
            case ORACLE -> new OracleAshSource(c.oracleIntervalMs(), c.initialLoadMinutes(), c.queryTimeoutSeconds());
            case POSTGRES -> new PostgresAshSource(c.postgresIntervalMs(), c.queryTimeoutSeconds());
        };
    }

    private Properties driverProperties(DbType type, String username, String password) {
        return driverProperties(type, username, password, properties.collector().queryTimeoutSeconds() + 15);
    }

    private Properties driverProperties(DbType type, String username, String password, int readTimeoutSeconds) {
        Properties p = new Properties();
        p.setProperty("user", username);
        p.setProperty("password", password);
        switch (type) {
            case ORACLE -> {
                p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
                p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(readTimeoutSeconds * 1000));
                p.setProperty("v$session.program", "ashweb");
            }
            case POSTGRES -> {
                p.setProperty("connectTimeout", "10");
                p.setProperty("socketTimeout", String.valueOf(readTimeoutSeconds));
                p.setProperty("ApplicationName", "ashweb");
            }
        }
        return p;
    }

    /** Small pool for one collector. It connects lazily, so creating it never fails. */
    public HikariDataSource pool(String poolName, DbType type, String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(jdbcUrl);
        config.setDataSourceProperties(driverProperties(type, username, password));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setIdleTimeout(120_000);
        config.setConnectionTimeout(15_000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(true);
        return new HikariDataSource(config);
    }

    /** One-off read-only connection for an on-demand lookup. The caller closes it. */
    public Connection open(DbType type, String jdbcUrl, String username, String password) throws SQLException {
        return open(type, jdbcUrl, username, password, properties.collector().queryTimeoutSeconds() + 15);
    }

    /** As above, for a lookup whose answer may take up to readTimeoutSeconds to arrive. */
    public Connection open(DbType type, String jdbcUrl, String username, String password, int readTimeoutSeconds)
            throws SQLException {
        DriverManager.setLoginTimeout(15);
        Connection connection = DriverManager.getConnection(jdbcUrl,
                driverProperties(type, username, password, readTimeoutSeconds));
        try {
            connection.setReadOnly(true);
        } catch (SQLException e) {
            // A hint only; the statements run here are SELECTs
        }
        return connection;
    }

    /** Opens a one-off connection and runs the source's probe statement. Throws with the database's message. */
    public String test(DbType type, String jdbcUrl, String username, String password) throws SQLException {
        DriverManager.setLoginTimeout(15);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, driverProperties(type, username, password));
             Statement st = connection.createStatement()) {
            st.setQueryTimeout(15);
            try (ResultSet rs = st.executeQuery(sourceFor(type).probeSql())) {
                rs.next();
            }
            return connection.getMetaData().getDatabaseProductName() + " "
                    + connection.getMetaData().getDatabaseProductVersion();
        }
    }
}
