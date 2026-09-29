package com.taxonomy.security;

import org.junit.jupiter.api.Test;

/** Verify the actual Maven-resolved Jackson implementation, not merely a version string. */
class JacksonXmlDatatypeLimitsTest {
    @Test void oversizedDurationIsRejected() { JacksonXmlDatatypeLimitsCases.durationLimit(); }
    @Test void oversizedCalendarIsRejected() { JacksonXmlDatatypeLimitsCases.calendarLimit(); }
    @Test void normalDurationRemainsSupported() { JacksonXmlDatatypeLimitsCases.normalDuration(); }
    @Test void normalCalendarRemainsSupported() { JacksonXmlDatatypeLimitsCases.normalCalendar(); }
}
