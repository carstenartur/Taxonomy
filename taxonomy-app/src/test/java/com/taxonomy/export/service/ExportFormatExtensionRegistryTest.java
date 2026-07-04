package com.taxonomy.export.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExportFormatExtensionRegistry}.
 *
 * <p>No Spring context required — the registry is a plain Java class.
 */
class ExportFormatExtensionRegistryTest {

    // ── Happy-path: lookup and listing ──────────────────────────────────────

    @Test
    void findByFormatIdReturnsPresentForRegisteredId() {
        ExportFormatExtension ext = stubExtension("mermaid");
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(ext));

        Optional<ExportFormatExtension> result = registry.findByFormatId("mermaid");

        assertThat(result).isPresent();
        assertThat(result.get().descriptor().id()).isEqualTo("mermaid");
    }

    @Test
    void findByFormatIdIsCaseInsensitive() {
        ExportFormatExtension ext = stubExtension("archimate");
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(ext));

        assertThat(registry.findByFormatId("ArchiMate")).isPresent();
        assertThat(registry.findByFormatId("ARCHIMATE")).isPresent();
    }

    @Test
    void findByFormatIdReturnsEmptyForUnknownId() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("mermaid")));

        assertThat(registry.findByFormatId("unknown")).isEmpty();
    }

    @Test
    void findByFormatIdReturnsEmptyForNull() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("mermaid")));

        assertThat(registry.findByFormatId(null)).isEmpty();
    }

    @Test
    void findByFormatIdReturnsEmptyForBlank() {
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(stubExtension("mermaid")));

        assertThat(registry.findByFormatId("  ")).isEmpty();
    }

    @Test
    void getRequiredReturnsExtensionForKnownId() {
        ExportFormatExtension ext = stubExtension("visio");
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of(ext));

        ExportFormatExtension result = registry.getRequired("visio");

        assertThat(result.descriptor().id()).isEqualTo("visio");
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
        ExportFormatExtensionRegistry registry =
                new ExportFormatExtensionRegistry(List.of());

        assertThat(registry.listDescriptors()).isEmpty();
    }

    // ── Duplicate ID validation ──────────────────────────────────────────────

    @Test
    void rejectsDuplicateFormatId() {
        ExportFormatExtension ext1 = stubExtension("mermaid");
        ExportFormatExtension ext2 = stubExtension("mermaid");

        assertThatThrownBy(() ->
                new ExportFormatExtensionRegistry(List.of(ext1, ext2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mermaid");
    }

    @Test
    void rejectsDuplicateFormatIdCaseInsensitive() {
        ExportFormatExtension ext1 = stubExtension("mermaid");
        ExportFormatExtension ext2 = stubExtension("Mermaid");

        assertThatThrownBy(() ->
                new ExportFormatExtensionRegistry(List.of(ext1, ext2)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Stable format IDs ────────────────────────────────────────────────────

    @Test
    void mermaidFormatIdIsStable() {
        assertThat(MermaidExportExtension.FORMAT_ID).isEqualTo("mermaid");
    }

    @Test
    void archiMateFormatIdIsStable() {
        assertThat(ArchiMateExportExtension.FORMAT_ID).isEqualTo("archimate");
    }

    @Test
    void visioFormatIdIsStable() {
        assertThat(VisioExportExtension.FORMAT_ID).isEqualTo("visio");
    }

    @Test
    void structurizrFormatIdIsStable() {
        assertThat(StructurizrExportExtension.FORMAT_ID).isEqualTo("structurizr");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
