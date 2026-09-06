package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.PartChange;
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

    @Test
    void comparisonReadsEachVersionOnceAndOnlyComparesTheSelectedPart() throws Exception {
        var before = snapshot(A);
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        parts.put("word/document.xml", xml.replace("</w:body>", "<!-- changed --></w:body>")
                .getBytes(StandardCharsets.UTF_8));
        var after = snapshot(B);
        when(repository.read(ID, A)).thenReturn(before);
        when(repository.read(ID, B)).thenReturn(after);

        var comparison = service.comparePart(ID, A, B, "word/document.xml");

        assertEquals(PartChange.MODIFIED, comparison.change());
        assertEquals(xml, comparison.before().textContent());
        assertTrue(comparison.after().textContent().contains("<!-- changed -->"));
        verify(repository).read(ID, A);
        verify(repository).read(ID, B);
        verifyNoMoreInteractions(repository);
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void sameRevisionUsesOneSnapshotAndOnePartView() throws Exception {
        when(repository.read(ID, A)).thenReturn(snapshot(A));

        var comparison = service.comparePart(ID, A, A, "word/document.xml");

        assertNull(comparison.change());
        assertSame(comparison.before(), comparison.after());
        verify(repository).read(ID, A);
        verifyNoMoreInteractions(repository);
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void unrelatedPartChangeDoesNotMarkTheSelectedPartAsModified() throws Exception {
        var before = snapshot(A);
        parts.put("word/qa-note.xml", "<note/>".getBytes(StandardCharsets.UTF_8));
        when(repository.read(ID, A)).thenReturn(before);
        when(repository.read(ID, B)).thenReturn(snapshot(B));

        var comparison = service.comparePart(ID, A, B, "word/document.xml");

        assertNull(comparison.change());
        assertEquals(comparison.before(), comparison.after());
        verify(repository).read(ID, A);
        verify(repository).read(ID, B);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void addedAndDeletedPartsRetainExactPresenceAndDirection() throws Exception {
        var before = snapshot(A);
        parts.put("word/qa-note.xml", "<note/>".getBytes(StandardCharsets.UTF_8));
        when(repository.read(ID, A)).thenReturn(before);
        when(repository.read(ID, B)).thenReturn(snapshot(B));

        var added = service.comparePart(ID, A, B, "word/qa-note.xml");
        var deleted = service.comparePart(ID, B, A, "word/qa-note.xml");

        assertEquals(PartChange.ADDED, added.change());
        assertNull(added.before());
        assertEquals("<note/>", added.after().textContent());
        assertEquals(PartChange.DELETED, deleted.change());
        assertEquals(added.after(), deleted.before());
        assertNull(deleted.after());
        verify(repository, times(2)).read(ID, A);
        verify(repository, times(2)).read(ID, B);
        verifyNoMoreInteractions(repository);
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void absentPartAndMissingRevisionAreNeverConfusedWithEachOther() throws Exception {
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        when(repository.read(ID, B)).thenReturn(snapshot(B));
        assertThrows(TemplateNotFoundException.class,
                () -> service.comparePart(ID, A, B, "word/absent.xml"));
        var missing = new TemplateNotFoundException(ID, B);
        when(repository.read(ID, B)).thenThrow(missing);
        assertSame(missing, assertThrows(TemplateNotFoundException.class,
                () -> service.comparePart(ID, A, B, "word/absent.xml")));
        verify(repository, times(2)).read(ID, A);
        verify(repository, times(2)).read(ID, B);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void comparisonRejectsUnsafePathsAndMutableRevisionsBeforeReads() {
        for (String path : new String[]{null, "", " ", "/word/document.xml", "word/..", "word/.",
                "./word/document.xml", "word//document.xml", "word/ /document.xml",
                "word/document.xml/", "word/a:b.xml", "word\\document.xml", "word/\0document.xml"}) {
            assertThrows(IllegalArgumentException.class, () -> service.comparePart(ID, A, B, path));
        }
        for (String revision : new String[]{null, "", "HEAD", "main", "abcdef0", "*"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.comparePart(ID, revision, B, "word/document.xml"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.comparePart(ID, A, revision, "word/document.xml"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void comparisonValidatesBothReportContractsBeforeInterpretingPartAbsence() throws Exception {
        var before = snapshot(A);
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        parts.put("word/document.xml", xml.replace(DecisionRationaleTemplateContract.BODY_MARKER,
                "removed-marker").getBytes(StandardCharsets.UTF_8));
        when(repository.read(ID, A)).thenReturn(before);
        when(repository.read(ID, B)).thenReturn(snapshot(B));
        assertThrows(IllegalArgumentException.class,
                () -> service.comparePart(ID, A, B, "word/absent.xml"));
        verify(repository).read(ID, A);
        verify(repository).read(ID, B);
        verifyNoMoreInteractions(repository);
        verify(codec, never()).pack(anyMap());
    }

    @Test
    void emptyExistingPartsAreNotTreatedAsMissing() throws Exception {
        parts.put("word/qa-note.txt", new byte[0]);
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        var comparison = service.comparePart(ID, A, A, "word/qa-note.txt");
        assertNull(comparison.change());
        assertNotNull(comparison.before());
        assertEquals(0, comparison.before().size());
        assertEquals("", comparison.before().textContent());
        assertSame(comparison.before(), comparison.after());
        verify(repository).read(ID, A);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void mixedCaseRevisionsUseOneCanonicalSnapshotIdentity() throws Exception {
        when(repository.read(ID, A)).thenReturn(snapshot(A));

        var comparison = service.comparePart(ID, A.toUpperCase(java.util.Locale.ROOT), A,
                "word/document.xml");

        assertNull(comparison.change());
        assertSame(comparison.before(), comparison.after());
        verify(repository).read(ID, A);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void textPartMetadataDistinguishesXmlJsonAndPlainText() throws Exception {
        parts.put("word/qa-note.txt", "note".getBytes(StandardCharsets.UTF_8));
        parts.put("word/qa-data.json", "{}".getBytes(StandardCharsets.UTF_8));
        when(repository.read(ID, A)).thenReturn(snapshot(A));
        var expected = Map.of("word/qa-note.txt", "text/plain",
                "word/qa-data.json", "application/json", "word/document.xml", "application/xml",
                "_rels/.rels", "application/xml");
        for (var entry : expected.entrySet()) {
            var part = service.readPart(ID, A, entry.getKey());
            assertEquals(entry.getValue(), part.mediaType());
            assertNotNull(part.textContent());
        }
        verify(repository, times(expected.size())).read(ID, A);
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
