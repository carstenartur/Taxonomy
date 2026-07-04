package com.taxonomy.shared.extension;

import com.taxonomy.export.service.ExportContext;
import com.taxonomy.export.service.ExportFormatDescriptor;
import com.taxonomy.export.service.ExportFormatExtension;
import com.taxonomy.export.service.ExportResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExtensionRegistry}.
 */
class ExtensionRegistryTest {

    @Test
    void listByKindExposesSerializableDescriptorsWithoutImplementationTypes() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                new StubExportFormatExtension("mermaid", "Mermaid"),
                new StubExtension("gemini", "Gemini", "Cloud provider", ExtensionKind.LLM_PROVIDER)
        ));

        assertThat(registry.listByKind(ExtensionKind.EXPORT_FORMAT))
                .containsExactly(new ExtensionDescriptor(
                        "mermaid",
                        "Mermaid",
                        "Exports diagrams as Mermaid",
                        ExtensionKind.EXPORT_FORMAT
                ));

        assertThat(registry.findDescriptor(ExtensionKind.LLM_PROVIDER, "GEMINI"))
                .contains(new ExtensionDescriptor(
                        "gemini",
                        "Gemini",
                        "Cloud provider",
                        ExtensionKind.LLM_PROVIDER
                ));
    }

    @Test
    void duplicateIdsAreValidatedPerKind() {
        StubExtension first = new StubExtension("mermaid", "Mermaid", "", ExtensionKind.EXPORT_FORMAT);
        AlternativeStubExtension second = new AlternativeStubExtension(
                "MERMAID",
                "Mermaid 2",
                "",
                ExtensionKind.EXPORT_FORMAT
        );
        assertThatThrownBy(() -> new ExtensionRegistry(List.of(
                first,
                second
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate extension ID")
                .hasMessageContaining("EXPORT_FORMAT")
                .hasMessageContaining("normalized ID 'mermaid'")
                .hasMessageContaining(first.getClass().getName())
                .hasMessageContaining(second.getClass().getName());
    }

    @Test
    void listByKindReturnsDescriptorsSortedById() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                new StubExtension("zeta", "Zeta", "", ExtensionKind.EXPORT_FORMAT),
                new StubExtension("alpha", "Alpha", "", ExtensionKind.EXPORT_FORMAT),
                new StubExtension("Beta", "Beta", "", ExtensionKind.EXPORT_FORMAT)
        ));

        assertThat(registry.listByKind(ExtensionKind.EXPORT_FORMAT))
                .extracting(ExtensionDescriptor::id)
                .containsExactly("alpha", "Beta", "zeta");
    }

    @Test
    void sameIdIsAllowedAcrossDifferentKinds() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                new StubExtension("shared", "Export Shared", "", ExtensionKind.EXPORT_FORMAT),
                new StubExtension("shared", "Report Shared", "", ExtensionKind.REPORT_RENDERER)
        ));

        assertThat(registry.listAll()).hasSize(2);
        assertThat(registry.getRequiredDescriptor(ExtensionKind.EXPORT_FORMAT, "shared").displayName())
                .isEqualTo("Export Shared");
        assertThat(registry.getRequiredDescriptor(ExtensionKind.REPORT_RENDERER, "shared").displayName())
                .isEqualTo("Report Shared");
    }

    private record StubExtension(
            String id,
            String displayName,
            String description,
            ExtensionKind kind
    ) implements TaxonomyExtension {
    }

    private record AlternativeStubExtension(
            String id,
            String displayName,
            String description,
            ExtensionKind kind
    ) implements TaxonomyExtension {
    }

    private static final class StubExportFormatExtension implements ExportFormatExtension {

        private final ExportFormatDescriptor descriptor;

        private StubExportFormatExtension(String id, String displayName) {
            this.descriptor = new ExportFormatDescriptor(id, displayName, "mmd", "text/plain", false);
        }

        @Override
        public ExportFormatDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ExportResult export(ExportContext context) {
            return new ExportResult(new byte[0]);
        }
    }
}
