package com.taxonomy.architecture.service;

import com.taxonomy.export.DiagramViewMetadata;

/** Supplies current report-view metadata without exposing application preferences. */
@FunctionalInterface
public interface ArchitectureReportMetadataPort {
    /** Resolves the current metadata for each report; implementations must not snapshot user preferences. */
    DiagramViewMetadata resolve();
}
