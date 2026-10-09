package com.taxonomy.composition.plugins;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.*;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class CapturedExtensionIdentityTest {
    private PluginDescriptor descriptor(String hash) {
        return new PluginDescriptor(new PluginIdentity("example.plugin", "1.0.0", hash.repeat(64)),
                ">=1.0.0 & <2.0.0", List.of(), Set.of(), PluginMode.DYNAMIC);
    }
    @Test void preparedExportMustNotSilentlyInvokeAReplacementArtifact() throws Exception {
        var catalog = new PluginCatalog(); var old = descriptor("a");
        ExportFormatExtension extension = new ExportFormatExtension() {
            public ExportFormatDescriptor descriptor() { return new ExportFormatDescriptor("sample", "Sample", "txt", "text/plain", false); }
            public ExportResult export(ExportContext context) { return new ExportResult(new byte[]{1}); }
        };
        catalog.publish(old, List.of(extension));
        var captured = new ExportFormatExtensionRegistry(catalog).getRequired("sample");
        catalog.beginDraining(old.identity()); catalog.awaitReleased(old.identity(), Duration.ZERO); catalog.remove(old.identity());
        catalog.publish(descriptor("b"), List.of(extension));
        assertThatThrownBy(() -> captured.export(null)).isInstanceOf(ExtensionUnavailableException.class);
    }
    @Test void preparedRendererMustNotUseReplacementMetadataOrCode() throws Exception {
        var catalog = new PluginCatalog(); var old = descriptor("a");
        ReportRendererExtension extension = new ReportRendererExtension() {
            public ReportFormatDescriptor descriptor() { return new ReportFormatDescriptor("sample", "Sample", "txt", "text/plain", false); }
            public ReportRenderResult render(ReportRenderContext context) { return new ReportRenderResult(new byte[]{1}); }
        };
        catalog.publish(old, List.of(extension));
        var captured = new ReportRendererRegistry(catalog, List.of()).getRequired("sample");
        catalog.beginDraining(old.identity()); catalog.awaitReleased(old.identity(), Duration.ZERO); catalog.remove(old.identity());
        catalog.publish(descriptor("b"), List.of(extension));
        assertThatThrownBy(() -> captured.render(ReportRenderContext.ofPayload("frozen"))).isInstanceOf(ExtensionUnavailableException.class);
    }
}
