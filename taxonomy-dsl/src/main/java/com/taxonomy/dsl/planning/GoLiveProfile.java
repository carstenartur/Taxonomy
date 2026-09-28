package com.taxonomy.dsl.planning;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A planning goal only. YEAR is not coerced to a date and neither is an operational assertion. */
public final class GoLiveProfile implements PlanningProfile {
    @Override public Descriptor descriptor() {
        return new Descriptor("go-live", "1", List.of(new Field("precision", true, List.of("YEAR", "DATE")),
                new Field("value", true, List.of())));
    }
    @Override public void validate(Map<String, String> values) {
        if (!values.keySet().equals(Set.of("precision", "value")))
            throw new IllegalArgumentException("Go-live needs exactly precision and value");
        String value = values.get("value");
        if ("YEAR".equals(values.get("precision")) && value.matches("[0-9]{4}") && !"0000".equals(value)) return;
        if ("DATE".equals(values.get("precision")) && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try { if (LocalDate.parse(value).getYear() > 0) return; }
            catch (DateTimeParseException invalid) { /* rejected below, never corrected */ }
        }
        throw new IllegalArgumentException("Use a year (YYYY) or an exact valid date (YYYY-MM-DD) with matching precision");
    }
}
