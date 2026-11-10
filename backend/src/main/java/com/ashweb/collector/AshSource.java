package com.ashweb.collector;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Reads active session samples from one kind of database. One instance per monitored database. */
public interface AshSource {

    long intervalMs();

    /**
     * Returns the samples that appeared since the previous call.
     *
     * @param watermark newest sample time already stored for this database, or null on the very first run
     */
    List<Sample> poll(Connection connection, Instant watermark) throws SQLException;

    /** Looks up statement texts the samples did not carry. Sources that inline the text return an empty map. */
    default Map<String, String> fetchSqlTexts(Connection connection, Collection<String> sqlIds) throws SQLException {
        return Map.of();
    }

    /**
     * How far the database's clock is ahead of this server's (negative when behind), once known.
     * Sample times are already corrected by it, so everything is stored on this server's timeline.
     */
    default java.time.Duration clockSkew() {
        return java.time.Duration.ZERO;
    }

    /** Cheap statement used by "test connection"; fails when the account cannot read the activity views. */
    String probeSql();

    /** Appends a JSON string member to a builder that is building a flat JSON object. Skips null values. */
    static void jsonField(StringBuilder json, String name, Object value) {
        if (value == null) {
            return;
        }
        json.append(json.length() == 0 ? '{' : ',').append('"').append(name).append("\":");
        if (value instanceof Number) {
            json.append(value);
            return;
        }
        json.append('"');
        String s = value.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c == 0) {
                        // jsonb cannot store NUL
                    } else if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    static String jsonEnd(StringBuilder json) {
        return json.length() == 0 ? null : json.append('}').toString();
    }
}
