package com.taxonomy.provenance;

import com.taxonomy.dto.AiExtractedCandidate;
import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.dto.RegulationArchitectureMatch;
import com.taxonomy.dto.RequirementCandidate;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.config.DocumentImportLimits;
import com.taxonomy.provenance.controller.DocumentImportController;
import com.taxonomy.provenance.controller.DocumentImportController.ConfirmCandidatesRequest;
import com.taxonomy.provenance.controller.DocumentImportController.ConfirmedCandidate;
import com.taxonomy.provenance.service.DocumentAnalysisService;
import com.taxonomy.provenance.service.DocumentLimitException;
import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService.CandidateInput;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService.ConfirmationResult;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService.ProvenanceCommandException;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService.RegistrationResult;
import com.taxonomy.provenance.service.SourceProvenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Controller boundary contracts complement the real MVC and persistence suites. */
class DocumentImportControllerContractTest {

    private final DocumentParserService parser = mock(DocumentParserService.class);
    private final DocumentProvenanceCommandService commands = mock(DocumentProvenanceCommandService.class);
    private final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
    private final DocumentImportLimits limits = new DocumentImportLimits();
    private final DocumentImportController controller = new DocumentImportController(
            parser, mock(SourceProvenanceService.class), commands, analysis, limits);
    private final MockMultipartFile upload = new MockMultipartFile(
            "file", "requirements.pdf", "application/pdf", new byte[]{1});

    @BeforeEach
    void configureBoundedInput() {
        limits.setMaxUploadBytes(1024);
        limits.setMaxLlmCharacters(128);
    }

    @Test
    void uploadCompletesFileIoBeforeRegistrationAndReturnsAuthoritativeSourceIds() throws Exception {
        var parsed = parsed(List.of());
        when(parser.parse(upload)).thenReturn(parsed);
        when(parser.computeContentHash(upload)).thenReturn("content-hash");
        when(commands.registerDocument(SourceType.REGULATION, "Safety rules", "application/pdf",
                "content-hash")).thenReturn(new RegistrationResult(11, 22));

        var response = controller.uploadDocument(upload, "  Safety rules  ", "regulation");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(parsed);
        assertThat(parsed.getSourceArtifactId()).isEqualTo(11L);
        assertThat(parsed.getSourceVersionId()).isEqualTo(22L);
        var order = inOrder(parser, commands);
        order.verify(parser).parse(upload);
        order.verify(parser).computeContentHash(upload);
        order.verify(commands).registerDocument(SourceType.REGULATION, "Safety rules",
                "application/pdf", "content-hash");
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "UNSUPPORTED"})
    void missingOrUnsupportedSourceTypeUsesDocumentFallback(String sourceType) throws Exception {
        when(parser.parse(upload)).thenReturn(parsed(List.of()));
        when(parser.computeContentHash(upload)).thenReturn("content-hash");
        when(commands.registerDocument(SourceType.UPLOADED_DOCUMENT, "requirements.pdf",
                "application/pdf", "content-hash")).thenReturn(new RegistrationResult(11, 22));

        assertThat(controller.uploadDocument(upload, " ", sourceType).getStatusCode().value())
                .isEqualTo(200);

        verify(commands).registerDocument(SourceType.UPLOADED_DOCUMENT, "requirements.pdf",
                "application/pdf", "content-hash");
    }

    @Test
    void absentTitleAndFilenameStillRegisterWithUsableFallbackTitle() throws Exception {
        MultipartFile unnamed = mock(MultipartFile.class);
        when(unnamed.getSize()).thenReturn(1L);
        when(parser.parse(unnamed)).thenReturn(parsed(List.of()));
        when(parser.computeContentHash(unnamed)).thenReturn("content-hash");
        when(commands.registerDocument(SourceType.REGULATION, "document", "application/pdf",
                "content-hash")).thenReturn(new RegistrationResult(11, 22));

        assertThat(controller.uploadDocument(unnamed, null, "REGULATION").getStatusCode().value())
                .isEqualTo(200);

        verify(commands).registerDocument(SourceType.REGULATION, "document", "application/pdf",
                "content-hash");
    }

    @Test
    void hashReadFailureCannotStartTheDatabaseCommand() throws Exception {
        when(parser.parse(upload)).thenReturn(parsed(List.of()));
        when(parser.computeContentHash(upload)).thenThrow(new IOException("unreadable content"));

        assertResponse(controller.uploadDocument(upload, "Rules", "REGULATION"), 422,
                Map.of("error", "DOCUMENT_PARSE_FAILED",
                        "message", "The document could not be parsed as PDF or DOCX"));

        verifyNoInteractions(commands, analysis);
    }

    @Test
    void registrationValidationKeepsItsStableClientError() throws Exception {
        String oversizedTitle = "T".repeat(501);
        when(parser.parse(upload)).thenReturn(parsed(List.of()));
        when(parser.computeContentHash(upload)).thenReturn("content-hash");
        when(commands.registerDocument(SourceType.REGULATION, oversizedTitle, "application/pdf",
                "content-hash")).thenThrow(new ProvenanceCommandException(
                        "SOURCE_TITLE_TOO_LARGE", "Source title exceeds 500 characters"));

        assertResponse(controller.uploadDocument(upload, oversizedTitle, "REGULATION"), 400,
                Map.of("error", "SOURCE_TITLE_TOO_LARGE",
                        "message", "Source title exceeds 500 characters"));
    }

    @Test
    void extractionPreservesCandidatesAndSectionOrderInProviderInput() throws Exception {
        var candidates = List.of(
                new RequirementCandidate(0, "Section 1", "Alpha obligation", 1),
                new RequirementCandidate(1, null, "Beta obligation", 1),
                new RequirementCandidate(2, " ", "Gamma obligation", 2));
        String text = "Section 1:\nAlpha obligation\n\nBeta obligation\n\nGamma obligation\n\n";
        var extracted = List.of(new AiExtractedCandidate("Reviewed obligation", "Section 1", .8, "LEGAL"));
        when(parser.parse(upload)).thenReturn(parsed(candidates));
        when(analysis.extractWithAi(text, "REGULATION")).thenReturn(extracted);

        assertResponse(controller.extractWithAi(upload, "REGULATION"), 200, Map.of(
                "fileName", "requirements.pdf", "totalPages", 2,
                "inputTruncated", false, "inputCharacters", text.length(),
                "ruleBased", candidates, "aiCandidates", extracted));

        verify(analysis).extractWithAi(text, "REGULATION");
        verifyNoInteractions(commands);
    }

    @Test
    void extractionCapsProviderTextButKeepsAllCandidatesAvailableForReview() throws Exception {
        var candidates = List.of(
                new RequirementCandidate(0, "1", "A".repeat(200), 1),
                new RequirementCandidate(1, "2", "Later requirement", 2));
        when(parser.parse(upload)).thenReturn(parsed(candidates));
        String bounded = "1:\n" + "A".repeat(125);
        when(analysis.extractWithAi(bounded, "REGULATION")).thenReturn(List.of());

        assertResponse(controller.extractWithAi(upload, "REGULATION"), 200, Map.of(
                "fileName", "requirements.pdf", "totalPages", 2,
                "inputTruncated", true, "inputCharacters", 128,
                "ruleBased", candidates, "aiCandidates", List.of()));

        verify(analysis).extractWithAi(bounded, "REGULATION");
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(ints = {127, 128, 129})
    void previewFallbackDistinguishesExactBudgetFromTruncation(int length) throws Exception {
        var parsed = parsed(null);
        parsed.setFileName(null);
        parsed.setRawTextPreview("X".repeat(length));
        when(parser.parse(upload)).thenReturn(parsed);
        String bounded = "X".repeat(Math.min(length, 128));
        when(analysis.extractWithAi(bounded, "REGULATION")).thenReturn(List.of());

        assertResponse(controller.extractWithAi(upload, "REGULATION"), 200, Map.of(
                "fileName", "", "totalPages", 2,
                "inputTruncated", length > 128, "inputCharacters", bounded.length(),
                "ruleBased", List.of(), "aiCandidates", List.of()));

        verify(analysis).extractWithAi(bounded, "REGULATION");
    }

    @Test
    void missingPreviewAndEmptyCandidatesSupplyAnEmptyInput() throws Exception {
        var parsed = parsed(List.of());
        parsed.setRawTextPreview(null);
        when(parser.parse(upload)).thenReturn(parsed);
        when(analysis.extractWithAi("", "REGULATION")).thenReturn(List.of());

        assertResponse(controller.extractWithAi(upload, "REGULATION"), 200, Map.of(
                "fileName", "requirements.pdf", "totalPages", 2,
                "inputTruncated", false, "inputCharacters", 0,
                "ruleBased", List.of(), "aiCandidates", List.of()));

        verify(analysis).extractWithAi("", "REGULATION");
    }

    @Test
    void mappingReportsTruncationWhenTheNextCandidateExceedsTheBudget() throws Exception {
        var candidates = List.of(
                new RequirementCandidate(0, null, "A".repeat(126), 1),
                new RequirementCandidate(1, null, "Must not reach the provider", 2));
        var matches = List.of(new RegulationArchitectureMatch(
                "unit-fixture-node", "REQUIRES", .8, "Section 1", "Authored unit fixture"));
        String bounded = "A".repeat(126) + "\n\n";
        when(parser.parse(upload)).thenReturn(parsed(candidates));
        when(analysis.mapRegulationToArchitecture(bounded)).thenReturn(matches);

        assertResponse(controller.mapRegulation(upload), 200, Map.of(
                "fileName", "requirements.pdf", "totalPages", 2,
                "inputTruncated", true, "inputCharacters", 128, "matches", matches));

        verify(analysis).mapRegulationToArchitecture(bounded);
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @EnumSource(value = UploadOperation.class, names = {"EXTRACT", "MAP"})
    void analysisParseFailuresDoNotCallAProvider(UploadOperation operation) throws Exception {
        when(parser.parse(upload)).thenThrow(new IOException("unreadable content"));

        assertResponse(invoke(operation, upload), 422, Map.of(
                "error", "DOCUMENT_PARSE_FAILED",
                "message", "The document could not be parsed as PDF or DOCX"));

        verifyNoInteractions(commands, analysis);
    }

    @ParameterizedTest
    @EnumSource(UploadOperation.class)
    void parserLimitExceptionIsPreserved(UploadOperation operation) throws Exception {
        var failure = new DocumentLimitException("PDF_PAGE_LIMIT_EXCEEDED", "Too many pages");
        when(parser.parse(upload)).thenThrow(failure);

        assertThatThrownBy(() -> invoke(operation, upload)).isSameAs(failure);

        verifyNoInteractions(commands, analysis);
    }

    @ParameterizedTest
    @EnumSource(UploadOperation.class)
    void missingUploadIsRejectedBeforeParsingOrPersisting(UploadOperation operation) {
        assertThatThrownBy(() -> invoke(operation, null))
                .isInstanceOf(DocumentLimitException.class)
                .hasMessage("The uploaded file is empty");

        verifyNoInteractions(parser, commands, analysis);
    }

    @Test
    void confirmationReturnsAuthoritativeNewAndAlreadyLinkedCounts() {
        var candidates = List.of(new ConfirmedCandidate("Keep original text", "Section 1"));
        var input = List.of(new CandidateInput("Keep original text", "Section 1"));
        when(commands.confirmCandidates(11, 22, input)).thenReturn(new ConfirmationResult(0, 1));

        assertResponse(controller.confirmCandidates(new ConfirmCandidatesRequest(11L, 22L, candidates)),
                200, Map.of("linked", 0, "alreadyLinked", 1,
                        "message", "0 requirement candidate(s) linked to source"));

        verify(commands).confirmCandidates(11, 22, input);
        verifyNoInteractions(parser, analysis);
    }

    @Test
    void missingSourceIdentifiersCannotReachTheConfirmationCommand() {
        for (var request : Arrays.asList(null,
                new ConfirmCandidatesRequest(null, 22L, List.of()),
                new ConfirmCandidatesRequest(11L, null, List.of()))) {
            assertResponse(controller.confirmCandidates(request), 400,
                    Map.of("error", "SOURCE_IDENTIFIERS_REQUIRED"));
        }

        verifyNoInteractions(commands, parser, analysis);
    }

    @Test
    void absentCandidateListUsesTheSameCommandValidationAsAnEmptyList() {
        when(commands.confirmCandidates(11, 22, List.of()))
                .thenThrow(new ProvenanceCommandException("NO_CANDIDATES", "No candidates were selected"));

        assertResponse(controller.confirmCandidates(new ConfirmCandidatesRequest(11L, 22L, null)),
                400, Map.of("error", "NO_CANDIDATES", "message", "No candidates were selected"));

        verify(commands).confirmCandidates(11, 22, List.of());
    }

    @Test
    void nullCandidateReachesValidationInTheSingleCompleteBatch() {
        var request = new ConfirmCandidatesRequest(11L, 22L, Arrays.asList(
                new ConfirmedCandidate("First requirement", "Section 1"), null));
        var input = List.of(new CandidateInput("First requirement", "Section 1"), new CandidateInput(null, null));
        when(commands.confirmCandidates(11, 22, input)).thenThrow(new ProvenanceCommandException(
                "CANDIDATE_TEXT_REQUIRED", "Every candidate must contain text"));

        assertResponse(controller.confirmCandidates(request), 400, Map.of(
                "error", "CANDIDATE_TEXT_REQUIRED", "message", "Every candidate must contain text"));

        verify(commands).confirmCandidates(11, 22, input);
        verifyNoMoreInteractions(commands);
    }

    @Test
    void candidateLimitIsNotConvertedToAGenericConfirmationFailure() {
        var input = List.of(new CandidateInput("A".repeat(2001), null));
        var failure = new DocumentLimitException("CANDIDATE_TEXT_TOO_LARGE", "Candidate too long");
        when(commands.confirmCandidates(11, 22, input)).thenThrow(failure);
        var request = new ConfirmCandidatesRequest(11L, 22L,
                List.of(new ConfirmedCandidate("A".repeat(2001), null)));

        assertThatThrownBy(() -> controller.confirmCandidates(request)).isSameAs(failure);
    }

    private ResponseEntity<?> invoke(UploadOperation operation, MultipartFile file) {
        return switch (operation) {
            case UPLOAD -> controller.uploadDocument(file, "Rules", "REGULATION");
            case EXTRACT -> controller.extractWithAi(file, "REGULATION");
            case MAP -> controller.mapRegulation(file);
        };
    }

    private static DocumentParseResult parsed(List<RequirementCandidate> candidates) {
        var result = new DocumentParseResult();
        result.setFileName("requirements.pdf");
        result.setMimeType("application/pdf");
        result.setTotalPages(2);
        result.setRawTextPreview("Fallback document preview");
        result.setCandidates(candidates);
        return result;
    }

    private static void assertResponse(ResponseEntity<?> response, int status, Map<String, ?> body) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(body);
    }

    private enum UploadOperation { UPLOAD, EXTRACT, MAP }
}
