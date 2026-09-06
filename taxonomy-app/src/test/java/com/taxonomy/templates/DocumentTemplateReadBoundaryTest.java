package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Read-side validation must not require creating a downloadable ZIP archive. */
@ExtendWith(MockitoExtension.class)
class DocumentTemplateReadBoundaryTest {
    private static final String ID = DecisionRationaleTemplateContract.TEMPLATE_ID;
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    @Mock DocumentTemplateGitRepository repository;
    private OoxmlTemplatePackageCodec codec;
    private DocumentTemplateService service;
    private Map<String, byte[]> parts;

    @BeforeEach
    void setUp() throws IOException {
        codec = spy(new OoxmlTemplatePackageCodec());
        try (var input = new ClassPathResource(DecisionRationaleTemplateContract.DEFAULT_RESOURCE)
                .getInputStream()) {
            parts = new TreeMap<>(codec.unpack(input).parts());
        }
        service = new DocumentTemplateService(repository, codec,
                List.of(new DecisionRationaleTemplateContract()));
        clearInvocations(codec);
    }

    @Test
    void metadataPreservesCurrentAndHistoricalVersionsWithoutPacking() throws Exception {
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        when(repository.readCurrent(ID)).thenReturn(snapshot(B));
        var historical = service.describe(ID, A);
        var current = service.describeCurrent(ID);
        assertEquals(A, historical.headCommit());
        assertEquals(B, current.headCommit());
        assertEquals("Report template", historical.displayName());
        assertEquals(OoxmlTemplatePackageCodec.packageSha256(parts), historical.packageSha256());
        assertEquals(parts.size(), historical.partCount());
        verify(codec, times(2)).validatePackage(anyMap());
        verify(codec, never()).pack(anyMap());
        verify(repository).read(ID, A);
        verify(repository).readCurrent(ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void invalidCanonicalPackageIsRejectedRatherThanJustReturningItsManifest() throws Exception {
        parts.remove("[Content_Types].xml");
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        assertThrows(IllegalArgumentException.class, () -> service.describe(ID, A));
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void metadataStillEnforcesTheActualReportTemplateContract() throws Exception {
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(xml.contains(DecisionRationaleTemplateContract.BODY_MARKER));
        parts.put("word/document.xml", xml.replace(DecisionRationaleTemplateContract.BODY_MARKER,
                "missing-body-marker").getBytes(StandardCharsets.UTF_8));
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        assertThrows(IllegalArgumentException.class, () -> service.describe(ID, A));
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void metadataStillRejectsActiveWordFields() throws Exception {
        service = new DocumentTemplateService(repository, codec, List.of());
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(xml.contains("</w:body>"));
        parts.put("word/document.xml", xml.replace("</w:body>",
                "<w:p><w:fldSimple w:instr=\"INCLUDETEXT &quot;file:///private&quot;\"/>"
                        + "</w:p></w:body>").getBytes(StandardCharsets.UTF_8));
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        assertThrows(IllegalArgumentException.class, () -> service.describe(ID, A));
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void invalidReadPartPathsAreRejectedBeforeAnyRepositoryRead() {
        for (String path : new String[]{null, "", " ", "/word/document.xml", "word/..", "word/.",
                "./word/document.xml", "word//document.xml", "word/ /document.xml",
                "word/document.xml/", "word/a:b.xml", "word\\document.xml", "word/\0document.xml"}) {
            assertThrows(IllegalArgumentException.class, () -> service.readPart(ID, A, path));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void validCanonicalPackagePartsRemainReadable() throws Exception {
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        for (String path : List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml")) {
            var part = service.readPart(ID, A, path);
            assertEquals(path, part.path());
            assertEquals(parts.get(path).length, part.size());
            assertEquals(new String(parts.get(path), StandardCharsets.UTF_8), part.textContent());
        }
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void missingHistoricalMetadataNeverFallsBackToTheCurrentVersion() throws Exception {
        var missing = new TemplateNotFoundException(ID, A);
        when(repository.read(ID, A)).thenThrow(missing);
        assertSame(missing, assertThrows(TemplateNotFoundException.class, () -> service.describe(ID, A)));
        verify(repository).read(ID, A);
        verifyNoMoreInteractions(repository);
        verify(codec, never()).pack(anyMap());
    }

    private TemplateSnapshot snapshot(String revision) {
        var manifest = new TemplateManifest(1, ID, "Report template", ID + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, "2026-09-06T00:00:00Z", "qa",
                parts.values().stream().mapToLong(bytes -> bytes.length).sum(), parts.size(),
                OoxmlTemplatePackageCodec.packageSha256(parts));
        return new TemplateSnapshot(manifest, revision, parts);
    }
}
