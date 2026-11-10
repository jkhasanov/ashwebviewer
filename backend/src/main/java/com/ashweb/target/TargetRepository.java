package com.ashweb.target;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TargetRepository {

    private final JdbcClient jdbc;

    public TargetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Target map(ResultSet rs, int row) throws SQLException {
        return new Target(rs.getLong("id"), rs.getString("name"), DbType.valueOf(rs.getString("db_type")),
                rs.getString("jdbc_url"), rs.getString("username"), rs.getString("password_enc"),
                rs.getBoolean("enabled"));
    }

    public List<Target> findAll() {
        return jdbc.sql("select * from target order by name").query(TargetRepository::map).list();
    }

    public Optional<Target> findById(long id) {
        return jdbc.sql("select * from target where id = ?").param(id).query(TargetRepository::map).optional();
    }

    public boolean nameTaken(String name, Long exceptId) {
        return jdbc.sql("select count(*) from target where lower(name) = lower(?) and id <> ?")
                .params(name, exceptId == null ? -1L : exceptId).query(Long.class).single() > 0;
    }

    public long insert(String name, DbType type, String url, String username, String passwordEnc, boolean enabled) {
        return jdbc.sql("""
                insert into target (name, db_type, jdbc_url, username, password_enc, enabled)
                values (?, ?, ?, ?, ?, ?) returning id""")
                .params(name, type.name(), url, username, passwordEnc, enabled).query(Long.class).single();
    }

    public void update(Target t) {
        jdbc.sql("""
                update target set name = ?, db_type = ?, jdbc_url = ?, username = ?, password_enc = ?, enabled = ?,
                       updated_at = now() where id = ?""")
                .params(t.name(), t.dbType().name(), t.jdbcUrl(), t.username(), t.passwordEnc(), t.enabled(), t.id())
                .update();
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.sql("update target set enabled = ?, updated_at = now() where id = ?").params(enabled, id).update();
    }

    /** Removes the database and everything collected from it. */
    public void delete(long id) {
        jdbc.sql("delete from ash_sample where target_id = ?").param(id).update();
        jdbc.sql("delete from ash_rollup_1m where target_id = ?").param(id).update();
        jdbc.sql("delete from sql_text where target_id = ?").param(id).update();
        jdbc.sql("delete from target where id = ?").param(id).update();
    }
}
