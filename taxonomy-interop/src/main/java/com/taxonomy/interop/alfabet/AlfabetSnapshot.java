package com.taxonomy.interop.alfabet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded external observation, NOT a canonical architecture or an authoritative inventory. */
public record AlfabetSnapshot(ExternalScope source, String mappingVersion, Instant startedAt, Instant finishedAt,
                              int pageCount, long responseBytes, List<ReportData> reports) {
    public AlfabetSnapshot { reports = List.copyOf(reports); }
    @JsonProperty public boolean authoritativeInventory() { return false; }
    @JsonProperty public String evidenceStatus() { return "REPORTS_EXHAUSTED_NOT_AN_ATOMIC_SNAPSHOT"; }
    public record ReportData(String report, AlfabetReadProfile.Kind kind, List<Row> rows) {
        public ReportData { rows = List.copyOf(rows); }
    }
    /** For relation reports refStr identifies the base object, NOT the relationship itself. */
    public record Row(String className, String refStr, Map<String, String> fields,
                      Map<String, String> columns, Set<String> nullColumns) {
        public Row { fields = Map.copyOf(fields); columns = Map.copyOf(columns); nullColumns = Set.copyOf(nullColumns); }
    }
}
