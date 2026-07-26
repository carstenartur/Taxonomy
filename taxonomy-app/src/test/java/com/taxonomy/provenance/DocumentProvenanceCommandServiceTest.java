package com.taxonomy.provenance;

import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.repository.RequirementSourceLinkRepository;
import com.taxonomy.provenance.repository.SourceFragmentRepository;
import com.taxonomy.provenance.service.DocumentLimitException;
import com.taxonomy.provenance.service.DocumentProvenanceCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.ai.gemini.api-key=",
        "spring.ai.openai.api-key=",
        "spring.ai.deepseek.api-key=",
        "spring.ai.qwen.api-key=",
        "spring.ai.llama.api-key=",
        "spring.ai.mistral.api-key="
})
class DocumentProvenanceCommandServiceTest {

    @Autowired
    private DocumentProvenanceCommandService service;

    @Autowired
    private SourceFragmentRepository fragmentRepository;

    @Autowired
    private RequirementSourceLinkRepository linkRepository;

    @Test
    void registersArtifactAndVersionTogether() {
        var result = service.registerDocument(
                SourceType.REGULATION,
                "Atomic regulation",
                "application/pdf",
                "0123456789abcdef");

        assertTrue(result.sourceArtifactId() > 0);
        assertTrue(result.sourceVersionId() > 0);
    }

    @Test
    void rejectsVersionFromAnotherArtifactWithoutWritingFragmentsOrLinks() {
        var first = service.registerDocument(
                SourceType.REGULATION, "First source", "application/pdf", "hash-first");
        var second = service.registerDocument(
                SourceType.REGULATION, "Second source", "application/pdf", "hash-second");
        long fragmentsBefore = fragmentRepository.count();
        long linksBefore = linkRepository.count();

        var error = assertThrows(
                DocumentProvenanceCommandService.ProvenanceCommandException.class,
                () -> service.confirmCandidates(
                        first.sourceArtifactId(),
                        second.sourceVersionId(),
                        List.of(new DocumentProvenanceCommandService.CandidateInput(
                                "A sufficiently long candidate requirement for the mismatch test.",
                                "Section 1"))));

        assertEquals("SOURCE_VERSION_MISMATCH", error.getCode());
        assertEquals(fragmentsBefore, fragmentRepository.count());
        assertEquals(linksBefore, linkRepository.count());
    }

    @Test
    void repeatedCandidateConfirmationIsIdempotent() {
        var registration = service.registerDocument(
                SourceType.REGULATION, "Retry source", "application/pdf", "hash-retry");
        var candidate = new DocumentProvenanceCommandService.CandidateInput(
                "The authority shall retain a traceable architecture decision for every material change.",
                "Audit requirements");
        long fragmentsBefore = fragmentRepository.count();
        long linksBefore = linkRepository.count();

        var first = service.confirmCandidates(
                registration.sourceArtifactId(), registration.sourceVersionId(), List.of(candidate));
        var retry = service.confirmCandidates(
                registration.sourceArtifactId(), registration.sourceVersionId(), List.of(candidate));

        assertEquals(1, first.linked());
        assertEquals(0, first.alreadyLinked());
        assertEquals(0, retry.linked());
        assertEquals(1, retry.alreadyLinked());
        assertEquals(fragmentsBefore + 1, fragmentRepository.count());
        assertEquals(linksBefore + 1, linkRepository.count());
    }

    @Test
    void validatesCompleteBatchBeforeFirstWrite() {
        var registration = service.registerDocument(
                SourceType.REGULATION, "Validation source", "application/pdf", "hash-validation");
        long fragmentsBefore = fragmentRepository.count();
        long linksBefore = linkRepository.count();
        String oversized = "x".repeat(2_001);

        assertThrows(DocumentLimitException.class,
                () -> service.confirmCandidates(
                        registration.sourceArtifactId(),
                        registration.sourceVersionId(),
                        List.of(
                                new DocumentProvenanceCommandService.CandidateInput(
                                        "This first candidate is valid but must not be written partially.",
                                        "Valid"),
                                new DocumentProvenanceCommandService.CandidateInput(
                                        oversized,
                                        "Invalid"))));

        assertEquals(fragmentsBefore, fragmentRepository.count());
        assertEquals(linksBefore, linkRepository.count());
    }
}
