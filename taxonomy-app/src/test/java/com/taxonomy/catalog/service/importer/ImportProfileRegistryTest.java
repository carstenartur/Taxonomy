package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.extension.api.importer.ImportInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        assertThat(registry.listDescriptors())
                .extracting(descriptor -> descriptor.profileId())
                .containsExactlyInAnyOrder("uaf", "apqc", "apqc-excel", "c4");
    }

    @Test
    void findByIdReturnsKnownProfilesAndRejectsUnknownValues() {
        assertThat(registry.findById("uaf")).isPresent();
        assertThat(registry.findById("APQC")).isPresent();
        assertThat(registry.findById(" apqc-excel ")).isPresent();
        assertThat(registry.findById("c4")).isPresent();
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
    void descriptorsExposeExpectedMetadata() {
        com.taxonomy.extension.api.importer.ImportProfileDescriptor uaf =
                registry.getRequired("uaf").descriptor();
        assertThat(uaf.displayName()).isEqualTo("UAF / DoDAF");
        assertThat(uaf.acceptedFileFormat()).isEqualTo("xml");
        assertThat(uaf.supportedElementTypes()).isNotEmpty();

        assertThat(registry.getRequired("apqc").descriptor().acceptedFileFormat())
                .isEqualTo("csv");
        assertThat(registry.getRequired("c4").descriptor().acceptedFileFormat())
                .isEqualTo("dsl");
    }

    @Test
    void previewViaRegistryDelegatesToUafExtension() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="e1" xsi:type="uaf:Capability" name="Cap1"/>
                </xmi:XMI>
                """;
        FrameworkImportResult result = registry.getRequired("uaf").preview(
                ImportInput.forPreview(new ByteArrayInputStream(
                        xml.getBytes(StandardCharsets.UTF_8))));
        assertThat(result.success()).isTrue();
        assertThat(result.profileId()).isEqualTo("uaf");
        assertThat(result.elementsTotal()).isEqualTo(1);
    }

    @Test
    void duplicateProfileIdThrowsOnRegistryConstruction() {
        DslMaterializeService materializeService = mock(DslMaterializeService.class);
        assertThatThrownBy(() -> new ImportProfileRegistry(List.of(
                new UafImportProfileExtension(materializeService),
                new UafImportProfileExtension(materializeService))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate import profile ID");
    }

    @Test
    void invalidDescriptorsFailFast() {
        com.taxonomy.extension.api.importer.ImportProfileExtension nullDescriptor =
                mock(com.taxonomy.extension.api.importer.ImportProfileExtension.class);
        when(nullDescriptor.descriptor()).thenReturn(null);
        assertThatThrownBy(() -> new ImportProfileRegistry(List.of(nullDescriptor)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("descriptor");

        com.taxonomy.extension.api.importer.ImportProfileExtension blankId =
                mock(com.taxonomy.extension.api.importer.ImportProfileExtension.class);
        when(blankId.descriptor()).thenReturn(
                new com.taxonomy.extension.api.importer.ImportProfileDescriptor(
                        "   ", "Broken", Set.of(), Set.of(), "xml"));
        assertThatThrownBy(() -> new ImportProfileRegistry(List.of(blankId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blank profile ID");
    }
}
