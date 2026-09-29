package com.taxonomy.security;

import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/** Small bounded fixtures for CVE-2026-68497; never constructs a large DoS payload. */
public final class JacksonXmlDatatypeLimitsCases {
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(32).build())
            .build()).build();

    private JacksonXmlDatatypeLimitsCases() { }

    public static void durationLimit() {
        requireLimit("\"P" + "9".repeat(64) + "Y\"", Duration.class);
    }

    public static void calendarLimit() {
        requireLimit("\"00:00:00." + "9".repeat(64) + "\"", XMLGregorianCalendar.class);
    }

    public static void normalDuration() {
        if (!"P1Y2M3D".equals(MAPPER.readValue("\"P1Y2M3D\"", Duration.class).toString())) {
            throw new AssertionError("Normal XML duration must remain readable");
        }
    }

    public static void normalCalendar() {
        XMLGregorianCalendar value = MAPPER.readValue("\"2000-01-02T03:04:05Z\"", XMLGregorianCalendar.class);
        if (value.getYear() != 2000 || value.getMonth() != 1 || value.getDay() != 2
                || value.getHour() != 3 || value.getMinute() != 4 || value.getSecond() != 5
                || value.getTimezone() != 0) {
            throw new AssertionError("Normal XML calendar must remain readable");
        }
    }

    private static void requireLimit(String json, Class<?> type) {
        try {
            MAPPER.readValue(json, type);
        } catch (StreamConstraintsException expected) {
            return;
        }
        throw new AssertionError("XML datatype must enforce the configured numeric length limit: " + type.getSimpleName());
    }

    public static void main(String[] args) {
        normalDuration();
        normalCalendar();
        int failures = 0;
        for (Runnable test : new Runnable[] {JacksonXmlDatatypeLimitsCases::durationLimit,
                JacksonXmlDatatypeLimitsCases::calendarLimit}) {
            try { test.run(); } catch (AssertionError failure) {
                failures++;
                System.out.println(failure.getMessage());
            }
        }
        System.out.println("Jackson XML datatype limits: 4 cases, " + failures + " failures");
        if (failures != 0) { throw new AssertionError("Jackson dependency lacks required XML numeric limits"); }
    }
}
