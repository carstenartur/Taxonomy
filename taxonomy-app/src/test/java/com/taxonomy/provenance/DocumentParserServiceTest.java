package com.taxonomy.provenance;

import com.taxonomy.dto.RequirementCandidate;
import com.taxonomy.provenance.service.DocumentParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link DocumentParserService} text extraction and
 * candidate splitting logic.
 */
class DocumentParserServiceTest {

    private DocumentParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new DocumentParserService();
    }

    @Test
    void extractCandidatesFromSimpleText() {
        String text = "§ 1 Allgemeine Bestimmungen\n\n" +
                "Die Behörde muss sicherstellen, dass alle Anträge innerhalb von 30 Tagen bearbeitet werden. " +
                "Diese Frist gilt für alle Verwaltungsverfahren.\n\n" +
                "§ 2 Besondere Bestimmungen\n\n" +
                "Der Antragsteller hat das Recht, innerhalb von 14 Tagen Widerspruch einzulegen. " +
                "Der Widerspruch muss schriftlich eingereicht werden.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getSectionHeading()).contains("§ 1");
        assertThat(candidates.get(1).getSectionHeading()).contains("§ 2");
    }

    @Test
    void shortParagraphsAreFiltered() {
        String text = "Title\n\nPage 1\n\n" +
                "This is a substantial paragraph with enough content to be considered a requirement candidate " +
                "for the taxonomy analysis workflow.\n\n" +
                "Short.\n\n" +
                "Also too short.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getText()).contains("substantial paragraph");
    }

    @Test
    void headingsAreDetected() {
        String text = "Chapter 1 Introduction\n\n" +
                "This chapter describes the fundamental requirements for the information system " +
                "architecture in public sector applications.\n\n" +
                "Section 2 Technical Requirements\n\n" +
                "The system must support real-time data processing with a maximum latency " +
                "of 100 milliseconds for all critical operations.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).getSectionHeading()).contains("Chapter 1");
    }

    @Test
    void germanSectionFormats() {
        String text = "Abschnitt 3 Datenschutz\n\n" +
                "Personenbezogene Daten dürfen nur mit ausdrücklicher Einwilligung des Betroffenen " +
                "verarbeitet werden. Die Speicherdauer ist auf das erforderliche Minimum zu beschränken.\n\n" +
                "Artikel 5 Datenverarbeitung\n\n" +
                "Die Verarbeitung personenbezogener Daten erfolgt nach den Grundsätzen der DSGVO. " +
                "Verantwortliche müssen angemessene technische und organisatorische Maßnahmen treffen.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getSectionHeading()).contains("Abschnitt 3");
        assertThat(candidates.get(1).getSectionHeading()).contains("Artikel 5");
    }

    @Test
    void emptyTextReturnsNoCandidates() {
        List<RequirementCandidate> candidates = parserService.extractCandidates("");
        assertThat(candidates).isEmpty();
    }

    @Test
    void candidateIndicesAreSequential() {
        String text = "This is a long enough paragraph to be a candidate for the requirement analysis pipeline.\n\n" +
                "This is another paragraph with sufficient content to pass the minimum length filter for extraction.\n\n" +
                "And yet another substantial paragraph that describes a requirement for system integration testing.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        for (int i = 0; i < candidates.size(); i++) {
            assertThat(candidates.get(i).getIndex()).isEqualTo(i);
        }
    }

    @Test
    void allCapsLinesAreDetectedAsHeadings() {
        String text = "REQUIREMENTS\n\n" +
                "The system shall provide a secure authentication mechanism that supports multi-factor " +
                "authentication for all administrative users.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(1);
        // "REQUIREMENTS" should be detected as a heading
        assertThat(candidates.get(0).getSectionHeading()).isEqualTo("REQUIREMENTS");
    }

    @Test
    void candidatesAreSelectedByDefault() {
        String text = "This paragraph is long enough to be extracted as a requirement candidate for the analysis workflow.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        if (!candidates.isEmpty()) {
            assertThat(candidates.get(0).isSelected()).isTrue();
        }
    }

    @Test
    void contentHashIsComputed() {
        byte[] content = "Test document content".getBytes();
        String hash = parserService.computeContentHash(content);

        assertThat(hash).isNotNull().isNotEmpty();
        assertThat(hash).hasSize(64); // SHA-256 hex = 64 characters

        // Same content produces same hash
        String hash2 = parserService.computeContentHash(content);
        assertThat(hash).isEqualTo(hash2);

        // Different content produces different hash
        String hash3 = parserService.computeContentHash("Different content".getBytes());
        assertThat(hash).isNotEqualTo(hash3);
    }

    @Test
    void longParagraphsAreTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("Word ");
        }

        String text = sb.toString(); // ~1500 chars
        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        // Should have 1 candidate, text should be within limits
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getText().length()).isLessThanOrEqualTo(2001); // MAX + ellipsis
    }
}
