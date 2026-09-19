package com.taxonomy.composition.report;

import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.shared.config.ExportConfig;
import org.springframework.stereotype.Component;

/** Application-owned bridge from live preferences to architecture report metadata. */
@Component
public class PreferenceArchitectureReportMetadata implements ArchitectureReportMetadataPort {
    private final PreferencesService preferencesService;

    public PreferenceArchitectureReportMetadata(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @Override
    public DiagramViewMetadata resolve() {
        return ExportConfig.resolveViewMetadata(preferencesService);
    }
}
