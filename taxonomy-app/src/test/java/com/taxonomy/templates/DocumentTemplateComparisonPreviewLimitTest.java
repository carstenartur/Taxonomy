package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;

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
    private static final int ORDINARY_PREVIEW_BYTES = 1_048_576;

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
    void ordinaryPartReadDecodesTextAtTheExistingOneMegabyteBoundary() throws Exception {
        byte[] content = new byte[ORDINARY_PREVIEW_BYTES];
        Arrays.fill(content, (byte) 'y');
        var snapshot = snapshot(content);
        when(repository.read(ID, REVISION)).thenReturn(snapshot);
        var service = new DocumentTemplateService(repository, new OoxmlTemplatePackageCodec(), List.of());

        var part = service.readPart(ID, REVISION, PATH);

        assertEquals(ORDINARY_PREVIEW_BYTES, part.size());
        assertEquals("text/plain", part.mediaType());
        assertNotNull(part.textContent());
        assertEquals(ORDINARY_PREVIEW_BYTES,
                part.textContent().getBytes(StandardCharsets.UTF_8).length);
        verify(repository).read(ID, REVISION);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void ordinaryPartReadStopsDecodingAboveTheExistingOneMegabyteBoundary() throws Exception {
        byte[] content = new byte[ORDINARY_PREVIEW_BYTES + 1];
        Arrays.fill(content, (byte) 'z');
        var snapshot = snapshot(content);
        when(repository.read(ID, REVISION)).thenReturn(snapshot);
        var service = new DocumentTemplateService(repository, new OoxmlTemplatePackageCodec(), List.of());

        var part = service.readPart(ID, REVISION, PATH);

        assertEquals(ORDINARY_PREVIEW_BYTES + 1L, part.size());
        assertEquals("text/plain", part.mediaType());
        assertNull(part.textContent());
        verify(repository).read(ID, REVISION);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void validUtf8ReplacementCharacterRemainsTextInTheComparisonUi() throws Exception {
        String expected = "before \uFFFD after";
        byte[] content = expected.getBytes(StandardCharsets.UTF_8);
        var snapshot = snapshot(content);
        when(repository.read(ID, REVISION)).thenReturn(snapshot);
        var service = new DocumentTemplateService(repository, new OoxmlTemplatePackageCodec(), List.of());
        var model = new ConcurrentModel();

        String view = new DocumentTemplatePartComparisonController(service)
                .comparePart(ID, REVISION, REVISION, PATH, model);

        assertEquals("document-template-part-comparison", view);
        assertEquals("TEXT", model.getAttribute("comparisonMode"));
        var part = (DocumentTemplateService.TemplatePartView) model.getAttribute("beforePart");
        assertNotNull(part);
        assertEquals(expected, part.textContent());
        assertSame(part, model.getAttribute("afterPart"));
        verify(repository).read(ID, REVISION);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void malformedUtf8HasNoTextPreviewAtTheDecodeBoundary() throws Exception {
        byte[] content = new byte[]{'a', (byte) 0xC3, '(', 'b'};
        var snapshot = snapshot(content);
        when(repository.read(ID, REVISION)).thenReturn(snapshot);
        var service = new DocumentTemplateService(repository, new OoxmlTemplatePackageCodec(), List.of());

        var part = service.readPart(ID, REVISION, PATH);

        assertEquals(content.length, part.size());
        assertEquals("text/plain", part.mediaType());
        assertNull(part.textContent());
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
