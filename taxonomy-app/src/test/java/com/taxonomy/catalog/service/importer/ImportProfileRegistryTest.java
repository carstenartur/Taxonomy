package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dto.FrameworkImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ImportProfileRegistry} and the bundled extension adapters.
 */
class ImportProfileRegistryTest {

    private ImportProfileRegistry registry;

    @BeforeEach
    void setUp() {
        DslMaterializeService materializeService = mock(DslMaterializeService.class);
        registry = new ImportProfileRegistry(List.of(
                new UafImportProfileExtension(materializeService),
                new ApqcCsvImportProfileExtension(materializeService),
                new ApqcExcelImportProfileExtension(materializeService),
                new C4ImportProfileExtension(materializeService)));
    }

    @Test
    void listDescriptorsContainsAllProfiles() {
        List<String> ids = registry.listDescriptors().stream()
                .map(ImportProfileDescriptor::profileId)
                .toList();
        assertThat(ids).containsExactlyInAnyOrder("uaf", "apqc", "apqc-excel", "c4");
    }

    @Test
    void findByIdReturnsExtensionForKnownProfile() {
        assertThat(registry.findById("uaf")).isPresent();
        assertThat(registry.findById("apqc")).isPresent();
        assertThat(registry.findById("apqc-excel")).isPresent();
        assertThat(registry.findById("c4")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertThat(registry.findById("nonexistent")).isEmpty();
        assertThat(registry.findById(null)).isEmpty();
        assertThat(registry.findById("  ")).isEmpty();
    }

    @Test
    void getRequiredThrowsForUnknown() {
        assertThatThrownBy(() -> registry.getRequired("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown import profile");
    }

    @Test
    void uafDescriptorHasExpectedMetadata() {
        ImportProfileDescriptor d = registry.getRequired("uaf").descriptor();
        assertThat(d.profileId()).isEqualTo("uaf");
        assertThat(d.displayName()).isEqualTo("UAF / DoDAF");
        assertThat(d.acceptedFileFormat()).isEqualTo("xml");
        assertThat(d.supportedElementTypes()).isNotEmpty();
    }

    @Test
    void apqcCsvDescriptorHasExpectedMetadata() {
        ImportProfileDescriptor d = registry.getRequired("apqc").descriptor();
        assertThat(d.profileId()).isEqualTo("apqc");
        assertThat(d.acceptedFileFormat()).isEqualTo("csv");
    }

    @Test
    void c4DescriptorHasExpectedMetadata() {
        ImportProfileDescriptor d = registry.getRequired("c4").descriptor();
        assertThat(d.profileId()).isEqualTo("c4");
        assertThat(d.acceptedFileFormat()).isEqualTo("dsl");
    }

    @Test
    void previewViaRegistryDelegatesToUafExtension() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="e1" xsi:type="uaf:Capability" name="Cap1"/>
                </xmi:XMI>
                """;
        ImportProfileExtension ext = registry.getRequired("uaf");
        FrameworkImportResult result = ext.preview(
                ImportInput.forPreview(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));

        assertThat(result.success()).isTrue();
        assertThat(result.profileId()).isEqualTo("uaf");
        assertThat(result.elementsTotal()).isEqualTo(1);
    }

    @Test
    void duplicateProfileIdThrowsOnRegistryConstruction() {
        DslMaterializeService ms = mock(DslMaterializeService.class);
        assertThatThrownBy(() -> new ImportProfileRegistry(List.of(
                new UafImportProfileExtension(ms),
                new UafImportProfileExtension(ms))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate import profile ID");
    }
}
