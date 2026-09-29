package com.taxonomy.security;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression for CVE-2026-68497, using small inputs rather than resource exhaustion. */
class JacksonXmlDatatypeConstraintsTest {
    private static final int NUMBER_LIMIT = 64;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNumberLength(NUMBER_LIMIT).build())
            .build()).build();

    @Test
    void ordinaryXmlDatatypesRemainReadable() {
        assertEquals("PT60S", mapper.readValue("\"PT60S\"", Duration.class).toString());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), mapper.readValue(
                "\"2026-01-02T03:04:05Z\"", XMLGregorianCalendar.class).toGregorianCalendar().toInstant());
    }

    @Test
    void durationInsideAJsonStringHonorsNumberLengthLimit() {
        rejectsOverlongNumber("P" + "9".repeat(NUMBER_LIMIT + 1) + "Y", Duration.class);
    }

    @Test
    void calendarFractionInsideAJsonStringHonorsNumberLengthLimit() {
        rejectsOverlongNumber("2026-01-02T03:04:05." + "9".repeat(NUMBER_LIMIT + 1) + "Z",
                XMLGregorianCalendar.class);
    }

    private void rejectsOverlongNumber(String text, Class<?> type) {
        var failure = assertThrows(JacksonException.class,
                () -> mapper.readValue(mapper.writeValueAsString(text), type));
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        assertInstanceOf(StreamConstraintsException.class, cause,
                "Must fail at the input-size guard, not due to invalid XML datatype syntax");
    }
}
