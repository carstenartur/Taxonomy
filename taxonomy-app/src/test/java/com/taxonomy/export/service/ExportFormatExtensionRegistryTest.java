package com.taxonomy.export.service;

import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportFormatExtensionRegistryTest {

    @Test
    void findByFormatIdReturnsPresentForRegisteredId() {
        ExportFormatExtension extension = stubExtension("mermaid");
        ExportFormatExtensionRegistry registry = new ExportFormatExtensionRegistry(List.of(extension));
        Optional<ExportFormatExtension> result = registry.findByFormatId("mermaid");
        assertThat(result).isPresent();
        assertThat(result.get().descriptor().id()).isEqualTo("mermaid");
    }

    @Test
    void findByFormatIdIsCaseInsensitive() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("archimate")));
        assertThat(registry.findByFormatId("ArchiMate")).isPresent();
        assertThat(registry.findByFormatId("ARCHIMATE")).isPresent();
    }

    @Test
    void findByFormatIdReturnsEmptyForUnknownNullAndBlankIds() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("mermaid")));
        assertThat(registry.findByFormatId("unknown")).isEmpty();
        assertThat(registry.findByFormatId(null)).isEmpty();
        assertThat(registry.findByFormatId("  ")).isEmpty();
    }

    @Test
    void getRequiredReturnsExtensionForKnownId() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("visio")));
        assertThat(registry.getRequired("visio").descriptor().id()).isEqualTo("visio");
    }

    @Test
    void getRequiredThrowsForUnknownId() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("mermaid")));
        assertThatThrownBy(() -> registry.getRequired("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void listDescriptorsReturnsAllFormats() {
        ExportFormatExtensionRegistry registry = new ExportFormatExtensionRegistry(
                List.of(stubExtension("visio"), stubExtension("mermaid"), stubExtension("archimate")));
        List<ExportFormatDescriptor> descriptors = registry.listDescriptors();
        assertThat(descriptors)
                .extracting(ExportFormatDescriptor::id)
                .containsExactlyInAnyOrder("mermaid", "archimate", "visio");
    }

    @Test
    void emptyRegistryReturnsEmptyList() {
        assertThat(new ExportFormatExtensionRegistry(List.of()).listDescriptors()).isEmpty();
    }

    @Test
    void rejectsDuplicateFormatIdCaseInsensitively() {
        assertThatThrownBy(() -> new ExportFormatExtensionRegistry(
                List.of(stubExtension("mermaid"), stubExtension("Mermaid"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingIgnoringCase("mermaid");
    }

    @Test
    void stableFormatIdsRemainBackwardCompatible() {
        assertThat(MermaidExportExtension.FORMAT_ID).isEqualTo("mermaid");
        assertThat(ArchiMateExportExtension.FORMAT_ID).isEqualTo("archimate");
        assertThat(VisioExportExtension.FORMAT_ID).isEqualTo("visio");
        assertThat(StructurizrExportExtension.FORMAT_ID).isEqualTo("structurizr");
    }

    private static ExportFormatExtension stubExtension(String formatId) {
        ExportFormatDescriptor descriptor = new ExportFormatDescriptor(
                formatId, formatId.toUpperCase(), "ext", "text/plain", false);
        return new ExportFormatExtension() {
            @Override
            public ExportFormatDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ExportResult export(ExportContext context) {
                return new ExportResult(new byte[0]);
            }
        };
    }
}
