package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateServicePreconditionTest {

    private static final String CURRENT =
            "0123456789abcdef0123456789abcdef01234567";

    @Mock
    private DocumentTemplateGitRepository repository;
    @Mock
    private OoxmlTemplatePackageCodec codec;

    private DocumentTemplateService service;

    @BeforeEach
    void setUp() {
        service = new DocumentTemplateService(repository, codec);
    }

    @Test
    void commaSeparatedIfMatchAcceptsAnyStrongMatchingEntityTag() throws Exception {
        when(repository.readCurrent("report")).thenReturn(snapshot());

        assertThat(service.resolveExpectedVersion(
                "report", "\"stale\", \"" + CURRENT + "\""))
                .isEqualTo(CURRENT);
    }

    @Test
    void weakEntityTagDoesNotSatisfyIfMatch() throws Exception {
        when(repository.readCurrent("report")).thenReturn(snapshot());

        assertThatThrownBy(() -> service.resolveExpectedVersion(
                "report", "W/\"" + CURRENT + "\""))
                .isInstanceOf(TemplateConflictException.class)
                .hasMessageContaining(CURRENT);
    }

    @Test
    void ifMatchCannotCreateAResourceThatDoesNotExist() throws Exception {
        when(repository.readCurrent("report"))
                .thenThrow(new TemplateNotFoundException("report", null));

        assertThatThrownBy(() -> service.resolveExpectedVersion(
                "report", "\"" + CURRENT + "\""))
                .isInstanceOf(TemplateConflictException.class)
                .hasMessageContaining("no longer exists");
    }

    @Test
    void wildcardIfMatchRequiresAndUsesTheCurrentRepresentation() throws Exception {
        when(repository.readCurrent("report")).thenReturn(snapshot());

        assertThat(service.resolveExpectedVersion("report", "*"))
                .isEqualTo(CURRENT);
    }

    @Test
    void uploadedOoxmlCannotShadowTaxonomysInternalManifestName() throws Exception {
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
        OoxmlTemplatePackageCodec.PackageData packageData =
                new OoxmlTemplatePackageCodec.PackageData(
                        Map.of("custom/template.json", content),
                        content.length,
                        "package-sha");
        when(codec.unpack(any())).thenReturn(packageData);

        assertThatThrownBy(() -> service.upload(
                "report",
                "Report",
                new ByteArrayInputStream(new byte[]{1}),
                null,
                "admin",
                "create"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved Taxonomy metadata name");
    }

    private static TemplateSnapshot snapshot() {
        TemplateManifest manifest = new TemplateManifest(
                1,
                "report",
                "Report",
                "report.dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                Instant.parse("2026-08-22T12:00:00Z").toString(),
                "admin",
                1,
                1,
                "package-sha");
        return new TemplateSnapshot(
                manifest,
                CURRENT,
                Map.of("word/document.xml", new byte[]{1}));
    }
}
