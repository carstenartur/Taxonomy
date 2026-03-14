package com.taxonomy.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@link FrameworkImportService}.
 */
class FrameworkImportServiceTest {

    private FrameworkImportService service;
    private DslMaterializeService materializeService;

    @BeforeEach
    void setUp() {
        materializeService = mock(DslMaterializeService.class);
        service = new FrameworkImportService(materializeService);
    }

    @Test
    void getAvailableProfilesReturnsRegisteredProfiles() {
        List<ProfileInfo> profiles = service.getAvailableProfiles();
        assertThat(profiles).isNotEmpty();
        List<String> profileIds = profiles.stream().map(ProfileInfo::profileId).toList();
        assertThat(profileIds).contains("uaf", "apqc", "c4");
    }

    @Test
    void uafProfileHasXmlFormat() {
        List<ProfileInfo> profiles = service.getAvailableProfiles();
        ProfileInfo uaf = profiles.stream()
                .filter(p -> "uaf".equals(p.profileId())).findFirst().orElse(null);
        assertThat(uaf).isNotNull();
        assertThat(uaf.acceptedFileFormat()).isEqualTo("xml");
        assertThat(uaf.displayName()).isEqualTo("UAF / DoDAF");
    }

    @Test
    void apqcProfileHasCsvFormat() {
        List<ProfileInfo> profiles = service.getAvailableProfiles();
        ProfileInfo apqc = profiles.stream()
                .filter(p -> "apqc".equals(p.profileId())).findFirst().orElse(null);
        assertThat(apqc).isNotNull();
        assertThat(apqc.acceptedFileFormat()).isEqualTo("csv");
    }

    @Test
    void c4ProfileHasDslFormat() {
        List<ProfileInfo> profiles = service.getAvailableProfiles();
        ProfileInfo c4 = profiles.stream()
                .filter(p -> "c4".equals(p.profileId())).findFirst().orElse(null);
        assertThat(c4).isNotNull();
        assertThat(c4.acceptedFileFormat()).isEqualTo("dsl");
        assertThat(c4.displayName()).isEqualTo("C4 / Structurizr");
    }

    @Test
    void previewUafXml() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="e1" xsi:type="uaf:Capability" name="TestCap"/>
                  <packagedElement xmi:id="e2" xsi:type="uaf:System" name="TestSys"/>
                </xmi:XMI>
                """;
        FrameworkImportResult result = service.preview("uaf",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.success()).isTrue();
        assertThat(result.elementsTotal()).isEqualTo(2);
        assertThat(result.profileId()).isEqualTo("uaf");
    }

    @Test
    void previewApqcCsv() throws Exception {
        String csv = """
                PCF ID,Name,Level,Description
                1.0,Vision and Strategy,1,Strategic planning
                1.1,Define business concept,2,Business definition
                """;
        FrameworkImportResult result = service.preview("apqc",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.success()).isTrue();
        assertThat(result.elementsTotal()).isEqualTo(2);
        assertThat(result.profileId()).isEqualTo("apqc");
    }

    @Test
    void previewC4Dsl() throws Exception {
        String dsl = """
                workspace {
                    model {
                        user = person "User" "A user"
                        sys = softwareSystem "App" "The application"
                        user -> sys "Uses"
                    }
                }
                """;
        FrameworkImportResult result = service.preview("c4",
                new ByteArrayInputStream(dsl.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.success()).isTrue();
        assertThat(result.elementsTotal()).isEqualTo(2);
        assertThat(result.relationsTotal()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void previewUnknownProfileReturnsFalse() {
        FrameworkImportResult result = service.preview("nonexistent",
                new ByteArrayInputStream(new byte[0]));
        assertThat(result.success()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("Unknown profile"));
    }

    @Test
    void importCallsMaterializeService() throws Exception {
        when(materializeService.materialize(anyString(), anyString(), anyString(), any()))
                .thenReturn(new DslMaterializeService.MaterializeResult(
                        true, List.of(), List.of(), 2, 1, 42L));

        String csv = """
                PCF ID,Name,Level,Description
                1.0,Vision and Strategy,1,Test
                """;
        FrameworkImportResult result = service.importFile("apqc",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "main");

        assertThat(result.success()).isTrue();
        assertThat(result.relationsCreated()).isEqualTo(2);
        assertThat(result.hypothesesCreated()).isEqualTo(1);
        assertThat(result.documentId()).isEqualTo(42L);
        verify(materializeService).materialize(anyString(), anyString(), eq("main"), any());
    }

    @Test
    void importUnknownProfileReturnsFalse() {
        FrameworkImportResult result = service.importFile("bad-profile",
                new ByteArrayInputStream(new byte[0]), "main");
        assertThat(result.success()).isFalse();
    }
}
