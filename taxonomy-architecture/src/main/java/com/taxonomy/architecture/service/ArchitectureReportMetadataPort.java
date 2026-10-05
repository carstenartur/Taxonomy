package com.taxonomy.architecture.service;

import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.dto.RequirementArchitectureView;

/** Supplies current report-view metadata without exposing application preferences. */
@FunctionalInterface
public interface ArchitectureReportMetadataPort {
    /** Resolves the current metadata for each report; implementations must not snapshot user preferences. */
    DiagramViewMetadata resolve();

    /** Applies report metadata within its architecture boundary; callers need no export DTO dependency. */
    default void applyTo(RequirementArchitectureView view) {
        var metadata = resolve();
        view.setViewTitle(metadata.viewTitle());
        view.setViewDescription(metadata.viewDescription());
        view.setContainmentEnabled(metadata.containmentEnabled());
        view.setActiveRules(metadata.activeRules());
    }
}
