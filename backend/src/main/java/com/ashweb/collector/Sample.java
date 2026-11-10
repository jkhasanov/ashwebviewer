package com.ashweb.collector;

import java.time.Instant;

/**
 * One active session at one sample time, normalised across database types.
 *
 * @param sqlId   empty string when the session had no statement
 * @param sqlText statement text when the source delivers it with the sample (PostgreSQL), otherwise null
 * @param extra   JSON object with source specific columns, or null
 */
public record Sample(Instant sampleTime, long sessionId, long sessionSerial, String userName, String program,
                     String sqlId, String sqlOpname, String waitClass, String event, String sqlText, String extra) {

    public static final String CPU = "CPU";
}
