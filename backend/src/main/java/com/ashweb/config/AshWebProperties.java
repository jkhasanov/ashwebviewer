package com.ashweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ashweb")
public record AshWebProperties(
        @DefaultValue Security security,
        @DefaultValue Collector collector,
        @DefaultValue Retention retention) {

    public record Security(
            @DefaultValue("./data/master.key") String masterKeyFile,
            @DefaultValue("") String initialAdminPassword) {
    }

    public record Collector(
            @DefaultValue("5000") long oracleIntervalMs,
            @DefaultValue("1000") long postgresIntervalMs,
            @DefaultValue("60") int initialLoadMinutes,
            @DefaultValue("30") int queryTimeoutSeconds) {
    }

    public record Retention(
            @DefaultValue("7") int rawDays,
            @DefaultValue("90") int rollupDays) {
    }
}
