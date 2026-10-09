package com.taxonomy.reporting.render.document;

import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRendererRegistryDecoratorTest {

    @Test
    void drainingRendererRemainsUnavailableInsteadOfBecomingAnUnknownFormat() {
        var catalog = new com.taxonomy.extension.runtime.PluginCatalog();
        var identity = new com.taxonomy.extension.api.plugin.PluginIdentity(
                "example.report", "1.0.0", "a".repeat(64));
        catalog.publish(new com.taxonomy.extension.api.plugin.PluginDescriptor(identity,
                ">=1.0.0", List.of(), java.util.Set.of(),
                com.taxonomy.extension.api.plugin.PluginMode.DYNAMIC), List.of(renderer("available")));
        var registry = new ReportRendererRegistry(catalog, List.of());
        assertThat(registry.getRequired("test", "txt").render(ReportRenderContext.ofPayload("report")).utf8())
                .isEqualTo("available");
        catalog.beginDraining(identity);
        assertThatThrownBy(() -> registry.getRequired("test", "txt"))
                .isInstanceOf(com.taxonomy.extension.api.plugin.ExtensionUnavailableException.class);
        assertThat(registry.findByFormatId("test", "unknown")).isEmpty();
        assertThatThrownBy(() -> registry.getRequired("test", "unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesInfrastructureDecoratorsBeforeRendererRegistration() {
        ReportRendererExtension original = renderer("original");
        ReportRendererDecorator decorator = new ReportRendererDecorator() {
            @Override
            public boolean supports(ReportRendererExtension renderer) {
                return "test".equals(renderer.reportTypeId());
            }

            @Override
            public ReportRendererExtension decorate(ReportRendererExtension renderer) {
                return renderer("decorated");
            }
        };

        ReportRendererRegistry registry = new ReportRendererRegistry(
                List.of(original),
                List.of(decorator));

        String rendered = registry.getRequired("test", "txt")
                .render(ReportRenderContext.ofPayload("payload"))
                .utf8();
        assertThat(rendered).isEqualTo("decorated");
    }

    private static ReportRendererExtension renderer(String output) {
        return new ReportRendererExtension() {
            @Override
            public String reportTypeId() {
                return "test";
            }

            @Override
            public Class<?> reportModelType() {
                return String.class;
            }

            @Override
            public ReportFormatDescriptor descriptor() {
                return new ReportFormatDescriptor(
                        "txt", "Text", "txt", "text/plain", false);
            }

            @Override
            public ReportRenderResult render(ReportRenderContext context) {
                context.payloadAs(String.class);
                return new ReportRenderResult(
                        output.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
