package com.taxonomy.composition.plugins;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.*;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.shared.extension.*;
import com.taxonomy.shared.extension.runtime.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SharedPluginCatalogTest {
    @Test void existingFacadesObserveAnEntireLatePublicationAndDrainTogether() {
        var catalog = new PluginCatalog();
        var metadata = new ExtensionRegistry(catalog);
        var exports = new ExportFormatExtensionRegistry(catalog);
        var reports = new ReportRendererRegistry(catalog, List.of());
        assertThat(metadata.listAll()).isEmpty(); assertThat(exports.listDescriptors()).isEmpty();
        assertThat(reports.listReportTypeIds()).isEmpty();
        var plugin = new PluginDescriptor(new PluginIdentity("example.formats", "1.0.0", "c".repeat(64)),
                ">=1.0.0 & <2.0.0", List.of(), Set.of("export", "render"), PluginMode.DYNAMIC);
        ExportFormatExtension export = new ExportFormatExtension() {
            public ExportFormatDescriptor descriptor() { return new ExportFormatDescriptor("external", "External", "txt", "text/plain", false); }
            public ExportResult export(ExportContext context) { return new ExportResult(new byte[]{42}); }
        };
        ReportRendererExtension report = new ReportRendererExtension() {
            public String reportTypeId() { return "external-report"; }
            public Class<?> reportModelType() { return String.class; }
            public ReportFormatDescriptor descriptor() { return new ReportFormatDescriptor("txt", "External", "txt", "text/plain", false); }
            public ReportRenderResult render(ReportRenderContext context) { return new ReportRenderResult(new byte[]{42}); }
        };
        catalog.publish(plugin, List.of(export, report));
        assertThat(metadata.listAll()).hasSize(2);
        assertThat(exports.listDescriptors()).extracting(ExportFormatDescriptor::id).containsExactly("external");
        assertThat(reports.listReportTypeIds()).containsExactly("external-report");
        var selectedExport = exports.getRequired("external");
        var selectedReport = reports.getRequired("external-report", "txt");
        selectedExport.export(null); selectedReport.render(ReportRenderContext.ofPayload("captured"));
        assertThat(catalog.activeLeases(plugin.identity())).isZero();
        catalog.beginDraining(plugin.identity());
        assertThat(metadata.listAll()).isEmpty(); assertThat(exports.listDescriptors()).isEmpty();
        assertThat(reports.listReportTypeIds()).isEmpty();
        assertThatThrownBy(() -> selectedExport.export(null)).isInstanceOf(ExtensionUnavailableException.class);
        assertThatThrownBy(() -> selectedReport.render(ReportRenderContext.ofPayload("new"))).isInstanceOf(ExtensionUnavailableException.class);
    }
}
