package com.ashweb.target;

/** A monitored database. */
public record Target(long id, String name, DbType dbType, String jdbcUrl, String username, String passwordEnc,
                     boolean enabled) {
}
