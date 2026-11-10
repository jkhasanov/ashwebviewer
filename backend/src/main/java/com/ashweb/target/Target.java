package com.ashweb.target;

/**
 * A monitored database.
 *
 * @param rawDays    days to keep its samples, or null for the server's setting
 * @param rollupDays days to keep its per-minute totals, or null for the server's setting
 */
public record Target(long id, String name, DbType dbType, String jdbcUrl, String username, String passwordEnc,
                     boolean enabled, Integer rawDays, Integer rollupDays) {
}
