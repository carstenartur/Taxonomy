package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.PartChange;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDiff;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService.TemplatePartView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentTemplatePartComparisonControllerTest {
    private static final String ID = "comparison-fixture";
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String PATH = "word/document.xml";
    @Mock DocumentTemplateService templates;

    @Test
    void modifiedPartUsesOnlyTheSelectedImmutableSnapshots() throws Exception {
        stub(PartChange.MODIFIED);
        when(templates.readPart(ID, A, PATH)).thenReturn(part("<p>before</p>"));
        when(templates.readPart(ID, B, PATH)).thenReturn(part("<p>after</p>"));
        var model = compare();
        assertEquals("TEXT", model.getAttribute("comparisonMode"));
        assertEquals("MODIFIED", model.getAttribute("partChange"));
        assertEquals(A, model.getAttribute("fromRevision"));
        assertEquals(B, model.getAttribute("toRevision"));
        verify(templates).diff(ID, A, B);
        verify(templates).readPart(ID, A, PATH);
        verify(templates).readPart(ID, B, PATH);
        verifyNoMoreInteractions(templates);
    }

    @Test
    void addedPartDoesNotReadAMissingOldPart() throws Exception {
        stub(PartChange.ADDED);
        when(templates.readPart(ID, B, PATH)).thenReturn(part("<p>new</p>"));
        var model = compare();
        assertNull(model.getAttribute("beforePart"));
        assertEquals("ADDED", model.getAttribute("partChange"));
        assertEquals("TEXT", model.getAttribute("comparisonMode"));
        verify(templates).diff(ID, A, B);
        verify(templates).readPart(ID, B, PATH);
        verifyNoMoreInteractions(templates);
    }

    @Test
    void deletedPartDoesNotReadAMissingNewPart() throws Exception {
        stub(PartChange.DELETED);
        when(templates.readPart(ID, A, PATH)).thenReturn(part("<p>old</p>"));
        var model = compare();
        assertNull(model.getAttribute("afterPart"));
        assertEquals("DELETED", model.getAttribute("partChange"));
        verify(templates).diff(ID, A, B);
        verify(templates).readPart(ID, A, PATH);
        verifyNoMoreInteractions(templates);
    }

    @Test
    void binaryPartKeepsItsMetadataWithoutAttemptingATextDiff() throws Exception {
        stub(PartChange.ADDED);
        var binary = new TemplatePartView(PATH, 32, "application/octet-stream", null);
        when(templates.readPart(ID, B, PATH)).thenReturn(binary);
        var model = compare();
        assertEquals("BINARY", model.getAttribute("comparisonMode"));
        assertSame(binary, model.getAttribute("afterPart"));
        assertNull(model.getAttribute("comparisonRows"));
    }

    @Test
    void oversizedPartIsAnExplicitLimitRatherThanAPartialOrEmptyDiff() throws Exception {
        stub(PartChange.ADDED);
        when(templates.readPart(ID, B, PATH)).thenReturn(
                new TemplatePartView(PATH, TemplateTextDiff.MAX_CHARACTERS + 1L, "application/xml", null));
        var model = compare();
        assertEquals("LIMIT", model.getAttribute("comparisonMode"));
        assertNull(model.getAttribute("comparisonRows"));
    }

    @Test
    void lossyOrUtf16DecodingIsNotPresentedAsReliableText() throws Exception {
        stub(PartChange.MODIFIED);
        when(templates.readPart(ID, A, PATH)).thenReturn(part("<p>valid</p>"));
        when(templates.readPart(ID, B, PATH)).thenReturn(part("<\0p\0>\uFFFD"));
        assertEquals("BINARY", compare().getAttribute("comparisonMode"));
    }

    @Test
    void missingRevisionStays404AndIsNotAnAddedPartOrTheCurrentVersion() throws Exception {
        var missing = new TemplateNotFoundException("private-storage-detail", A);
        when(templates.diff(ID, A, B)).thenThrow(missing);
        var failure = assertThrows(ResponseStatusException.class, this::compare);
        assertEquals(404, failure.getStatusCode().value());
        assertSame(missing, failure.getCause());
        assertFalse(failure.getReason().contains("private-storage-detail"));
        verify(templates).diff(ID, A, B);
        verifyNoMoreInteractions(templates);
    }

    @Test
    void invalidOrMutableInputsFailBeforeRepositoryAccess() {
        var controller = new DocumentTemplatePartComparisonController(templates);
        for (String invalid : new String[]{null, "", "HEAD", "main", "abc1234", "*"}) {
            for (String[] pair : new String[][]{{invalid, B}, {A, invalid}}) {
                var failure = assertThrows(ResponseStatusException.class,
                        () -> controller.comparePart(ID, pair[0], pair[1], PATH, new ConcurrentModel()));
                assertEquals(400, failure.getStatusCode().value());
            }
        }
        for (String invalid : new String[]{null, "", " ", "../word/document.xml", "/word/document.xml",
                "word\\document.xml", "word/..", "word/.", "./word/document.xml",
                "word//document.xml", "word/ /document.xml", "word/document.xml/",
                "word/a:b.xml", "word/\0document.xml"}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> controller.comparePart(ID, A, B, invalid, new ConcurrentModel())).getStatusCode().value());
        }
        verifyNoInteractions(templates);
    }

    @Test
    void unchangedEmptyPartIsPresentOnBothSides() throws Exception {
        when(templates.diff(ID, A, B)).thenReturn(new TemplateDiff(ID, A, B, Map.of()));
        when(templates.readPart(ID, A, PATH)).thenReturn(part(""));
        when(templates.readPart(ID, B, PATH)).thenReturn(part(""));
        var model = compare();
        assertEquals("UNCHANGED", model.getAttribute("partChange"));
        assertNotNull(model.getAttribute("beforePart"));
        assertNotNull(model.getAttribute("afterPart"));
        assertEquals("TEXT", model.getAttribute("comparisonMode"));
    }

    private void stub(PartChange change) throws Exception {
        when(templates.diff(ID, A, B)).thenReturn(new TemplateDiff(ID, A, B, Map.of(PATH, change)));
    }

    private ConcurrentModel compare() throws Exception {
        var model = new ConcurrentModel();
        assertEquals("document-template-part-comparison",
                new DocumentTemplatePartComparisonController(templates).comparePart(ID, A, B, PATH, model));
        return model;
    }

    private static TemplatePartView part(String text) {
        return new TemplatePartView(PATH, text.getBytes(StandardCharsets.UTF_8).length, "application/xml", text);
    }
}
