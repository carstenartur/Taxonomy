package com.taxonomy.export.reformulation;

/** Binary Word adapter receives fully frozen evidence; it cannot perform live lookups. */
public interface ReformulationDocxPort {
    byte[] render(ReformulationReportRenderer.Input report, FrozenReformulationArchitecture architecture);
}
