package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateComparisonPreviewLimitTest {
    private static final String ID = "comparison-preview-limit";
    private static final String REVISION = "a".repeat(40);
    private static final String PATH = "word/large-note.txt";

    @Mock DocumentTemplateGitRepository repository;

    @Test
    void comparisonDoesNotDecodeTextThatWillBeRenderedAsLimit() throws Exception {
        byte[] content = new byte[TemplateTextDiff.MAX_CHARACTERS + 1];
        Arrays.fill(content, (byte) 'x');
        var snapshot = snapshot(content);
        when(repository.read(ID, REVISION)).thenReturn(snapshot);
        var service = new DocumentTemplateService(repository, new OoxmlTemplatePackageCodec(), List.of());

        var comparison = service.comparePart(ID, REVISION, REVISION, PATH);

        assertNull(comparison.change());
        assertSame(comparison.before(), comparison.after());
        assertEquals(content.length, comparison.before().size());
        assertEquals("text/plain", comparison.before().mediaType());
        assertNull(comparison.before().textContent());
        verify(repository).read(ID, REVISION);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void ordinaryPartReadKeepsItsExistingOneMegabytePreviewBoundary() throws Exception {
        byte[] content = new byte[TemplateTextDiff.MAX_CHARACTERS + 1];
        Arrays.fill(content, (byte) 'y');
        var snapshot = snapshot(content);
        when(repository.read(ID, REVISION)).thenReturn(snapshot);
        var service = new DocumentTemplateService(repository, new OoxmlTemplatePackageCodec(), List.of());

        var part = service.readPart(ID, REVISION, PATH);

        assertEquals(content.length, part.size());
        assertEquals("text/plain", part.mediaType());
        assertNotNull(part.textContent());
        assertEquals(content.length, part.textContent().getBytes(StandardCharsets.UTF_8).length);
        verify(repository).read(ID, REVISION);
        verifyNoMoreInteractions(repository);
    }

    private static TemplateSnapshot snapshot(byte[] content) {
        Map<String, byte[]> parts = Map.of(PATH, content);
        var manifest = new TemplateManifest(1, ID, "Preview limit", ID + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, "2026-09-06T00:00:00Z", "qa",
                content.length, parts.size(), OoxmlTemplatePackageCodec.packageSha256(parts));
        return new TemplateSnapshot(manifest, REVISION, parts);
    }
}
