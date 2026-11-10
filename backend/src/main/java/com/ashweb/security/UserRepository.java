package com.ashweb.security;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public record AppUser(long id, String username, String passwordHash, String role, boolean enabled,
                          Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AppUser map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AppUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("role"), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public List<AppUser> findAll() {
        return jdbc.sql("select * from app_user order by username").query(UserRepository::map).list();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.sql("select * from app_user where username = ?").param(username)
                .query(UserRepository::map).optional();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.sql("select * from app_user where id = ?").param(id).query(UserRepository::map).optional();
    }

    public long count() {
        return jdbc.sql("select count(*) from app_user").query(Long.class).single();
    }

    public long countEnabledAdmins() {
        return jdbc.sql("select count(*) from app_user where role = 'ADMIN' and enabled").query(Long.class).single();
    }

    public long insert(String username, String passwordHash, String role) {
        return jdbc.sql("insert into app_user (username, password_hash, role) values (?, ?, ?) returning id")
                .params(username, passwordHash, role).query(Long.class).single();
    }

    public void update(long id, String role, boolean enabled) {
        jdbc.sql("update app_user set role = ?, enabled = ? where id = ?").params(role, enabled, id).update();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("update app_user set password_hash = ? where id = ?").params(passwordHash, id).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from app_user where id = ?").param(id).update();
    }
}
