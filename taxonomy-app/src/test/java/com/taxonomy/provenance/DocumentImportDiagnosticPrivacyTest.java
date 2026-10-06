package com.taxonomy.provenance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.provenance.config.DocumentImportLimits;
import com.taxonomy.provenance.controller.DocumentImportController;
import com.taxonomy.provenance.service.DocumentAnalysisService;
import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService;
import com.taxonomy.provenance.service.SourceProvenanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentImportDiagnosticPrivacyTest {

    private static final String FILENAME = "private-filename\r\nforged-log-entry.pdf";
    private static final String CONTENT = "private-document-content";
    private static final String FAILURE = "private-failure-token\r\nforged-failure-entry";

    private final DocumentParserService parser = mock(DocumentParserService.class);
    private final DocumentProvenanceCommandService commands = mock(DocumentProvenanceCommandService.class);
    private final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
    private final DocumentImportController controller = new DocumentImportController(
            parser, mock(SourceProvenanceService.class), commands, analysis, new DocumentImportLimits());
    private final MockMultipartFile upload = new MockMultipartFile(
            "file", FILENAME, "application/pdf", new byte[]{1});
    private final Logger logger = (Logger) LoggerFactory.getLogger(DocumentImportController.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureDiagnostics() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreDiagnostics() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void parseFailureKeeps422WithoutLoggingFilenameOrParserDetails() throws Exception {
        when(parser.parse(upload)).thenThrow(new IOException(FAILURE));

        assertResponse(controller.uploadDocument(upload, "private-title", "REGULATION"),
                422, "DOCUMENT_PARSE_FAILED", "The document could not be parsed as PDF or DOCX");
        assertSafeEvent(Level.WARN, "DOCUMENT_PARSE_FAILED");
    }

    @Test
    void unexpectedUploadFailureKeeps500WithoutLoggingPrivateThrowable() throws Exception {
        when(parser.parse(upload)).thenThrow(failure());

        assertResponse(controller.uploadDocument(upload, "private-title", "REGULATION"),
                500, "DOCUMENT_IMPORT_FAILED", "The document could not be registered");
        assertSafeEvent(Level.ERROR, "DOCUMENT_IMPORT_FAILED");
    }

    @Test
    void aiFailureKeeps422WithoutLoggingDocumentOrProviderDetails() throws Exception {
        when(parser.parse(upload)).thenReturn(parsed());
        when(analysis.extractWithAi(CONTENT, "REGULATION")).thenThrow(failure());

        assertResponse(controller.extractWithAi(upload, "REGULATION"),
                422, "AI_EXTRACTION_FAILED", "AI-assisted extraction could not be completed");
        verify(analysis).extractWithAi(CONTENT, "REGULATION");
        assertSafeEvent(Level.ERROR, "AI_EXTRACTION_FAILED");
    }

    @Test
    void mappingFailureKeeps422WithoutLoggingDocumentOrProviderDetails() throws Exception {
        when(parser.parse(upload)).thenReturn(parsed());
        when(analysis.mapRegulationToArchitecture(CONTENT)).thenThrow(failure());

        assertResponse(controller.mapRegulation(upload),
                422, "REGULATION_MAPPING_FAILED", "Regulation mapping could not be completed");
        verify(analysis).mapRegulationToArchitecture(CONTENT);
        assertSafeEvent(Level.ERROR, "REGULATION_MAPPING_FAILED");
    }

    @Test
    void confirmationFailureKeeps400WithoutLoggingCandidateOrPersistenceDetails() {
        when(commands.confirmCandidates(eq(11L), eq(22L), anyList())).thenThrow(failure());
        var request = new DocumentImportController.ConfirmCandidatesRequest(11L, 22L,
                List.of(new DocumentImportController.ConfirmedCandidate(CONTENT, "private-heading")));

        ResponseEntity<?> response = controller.confirmCandidates(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "CANDIDATE_CONFIRMATION_FAILED"));
        assertSafeEvent(Level.ERROR, "CANDIDATE_CONFIRMATION_FAILED");
    }

    private static DocumentParseResult parsed() {
        var result = new DocumentParseResult();
        result.setFileName(FILENAME);
        result.setMimeType("application/pdf");
        result.setTotalPages(1);
        result.setRawTextPreview(CONTENT);
        result.setCandidates(List.of());
        return result;
    }

    private static RuntimeException failure() {
        return new IllegalStateException(FAILURE, new IOException("private-nested-cause"));
    }

    private static void assertResponse(ResponseEntity<?> response, int status, String error, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(Map.of("error", error, "message", message));
    }

    private void assertSafeEvent(Level level, String code) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage())
                    .doesNotContain("private-", "forged-", "\r", "\n")
                    .contains(code)
                    .hasSizeLessThan(200);
            assertThat(event.getThrowableProxy()).isNull();
            if (event.getArgumentArray() != null) {
                assertThat(event.getArgumentArray()).allSatisfy(argument ->
                        assertThat(String.valueOf(argument)).doesNotContain("private-", "forged-"));
            }
        });
    }
}
