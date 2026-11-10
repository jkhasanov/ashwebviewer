package com.ashweb.target;

import java.util.List;

public enum DbType {
    ORACLE("jdbc:oracle:thin:",
            List.of("machine", "module", "action", "sessionType", "blockingSession", "planHashValue")),
    POSTGRES("jdbc:postgresql:", List.of("database", "client", "backendType"));

    private final String urlPrefix;
    private final List<String> sampleDetails;

    DbType(String urlPrefix, List<String> sampleDetails) {
        this.urlPrefix = urlPrefix;
        this.sampleDetails = sampleDetails;
    }

    /** The source specific members of a sample's "extra" that are worth a column when samples are listed. */
    public List<String> sampleDetails() {
        return sampleDetails;
    }

    public String urlPrefix() {
        return urlPrefix;
    }
}
