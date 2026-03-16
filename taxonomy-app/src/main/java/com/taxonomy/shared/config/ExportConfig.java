package com.taxonomy.shared.config;

import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers export-module classes as Spring beans.
 * <p>The taxonomy-export module is framework-free; this configuration
 * wires its classes into the Spring application context.</p>
 */
@Configuration
public class ExportConfig {

    @Bean
    public ArchiMateDiagramService archiMateDiagramService() {
        return new ArchiMateDiagramService();
    }

    @Bean
    public ArchiMateXmlExporter archiMateXmlExporter() {
        return new ArchiMateXmlExporter();
    }

    @Bean
    public DiagramProjectionService diagramProjectionService() {
        return new DiagramProjectionService();
    }

    @Bean
    public MermaidExportService mermaidExportService() {
        return new MermaidExportService();
    }

    @Bean
    public VisioDiagramService visioDiagramService() {
        return new VisioDiagramService();
    }

    @Bean
    public VisioPackageBuilder visioPackageBuilder() {
        return new VisioPackageBuilder();
    }
}
