package com.taxonomy.shared.config;

import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.ConfigurableDiagramSelectionPolicy;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.export.DiagramSelectionPolicy;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.StructurizrExportService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.preferences.PreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers export-module classes as Spring beans.
 * <p>The taxonomy-export module is framework-free; this configuration
 * wires its classes into the Spring application context.</p>
 */
@Configuration
public class ExportConfig {

    private static final Logger log = LoggerFactory.getLogger(ExportConfig.class);

    @Bean
    public ArchiMateDiagramService archiMateDiagramService() {
        return new ArchiMateDiagramService();
    }

    @Bean
    public ArchiMateXmlExporter archiMateXmlExporter() {
        return new ArchiMateXmlExporter();
    }

    @Bean
    public DiagramSelectionPolicy diagramSelectionPolicy(PreferencesService preferencesService) {
        // Delegating policy: reads the preference on each apply() call
        return rawModel -> resolvePolicy(preferencesService).apply(rawModel);
    }

    @Bean
    public DiagramProjectionService diagramProjectionService(DiagramSelectionPolicy policy) {
        var service = new DiagramProjectionService();
        service.setPolicy(policy);
        return service;
    }

    @Bean
    public MermaidExportService mermaidExportService() {
        return new MermaidExportService();
    }

    @Bean
    public StructurizrExportService structurizrExportService() {
        return new StructurizrExportService();
    }

    @Bean
    public VisioDiagramService visioDiagramService() {
        return new VisioDiagramService();
    }

    @Bean
    public VisioPackageBuilder visioPackageBuilder() {
        return new VisioPackageBuilder();
    }

    /**
     * Resolves the active {@link DiagramSelectionPolicy} from the current
     * {@code diagram.policy} preference value.
     *
     * @param prefs the preferences service providing the current setting
     * @return the resolved policy (never {@code null}; defaults to {@code defaultImpact})
     */
    static DiagramSelectionPolicy resolvePolicy(PreferencesService prefs) {
        String policyName = prefs.getString("diagram.policy", "defaultImpact");
        DiagramSelectionConfig config = switch (policyName) {
            case "leafOnly" -> DiagramSelectionConfig.leafOnly();
            case "clustering" -> DiagramSelectionConfig.clustering();
            case "trace" -> DiagramSelectionConfig.trace();
            case "defaultImpact" -> DiagramSelectionConfig.defaultImpact();
            default -> {
                log.warn("Unknown diagram.policy '{}', falling back to defaultImpact", policyName);
                yield DiagramSelectionConfig.defaultImpact();
            }
        };
        return new ConfigurableDiagramSelectionPolicy(config);
    }

    /**
     * Resolves human-readable view metadata for the active diagram policy.
     *
     * @param prefs the preferences service providing the current setting
     * @return view metadata generated from the active policy configuration
     */
    public static DiagramViewMetadata resolveViewMetadata(PreferencesService prefs) {
        String policyName = prefs.getString("diagram.policy", "defaultImpact");
        DiagramSelectionConfig config = switch (policyName) {
            case "leafOnly" -> DiagramSelectionConfig.leafOnly();
            case "clustering" -> DiagramSelectionConfig.clustering();
            case "trace" -> DiagramSelectionConfig.trace();
            default -> DiagramSelectionConfig.defaultImpact();
        };
        return DiagramViewMetadata.fromConfig(config, policyName);
    }
}
