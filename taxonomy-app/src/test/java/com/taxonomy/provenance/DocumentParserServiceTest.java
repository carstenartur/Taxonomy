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
    void longParagraphsAreSplitNotTruncated() {
        // Build a paragraph well over 2000 chars with distinct sentences
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("Sentence number ").append(i)
                    .append(" provides important detail about the requirement specification. ");
        }
        String text = sb.toString(); // ~3800 chars

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        // Must produce more than 1 candidate (split, not truncated)
        assertThat(candidates).hasSizeGreaterThan(1);

        // No text should be lost — concatenated candidate texts must contain all sentences
        String allText = candidates.stream()
                .map(RequirementCandidate::getText)
                .reduce("", (a, b) -> a + " " + b);
        for (int i = 1; i <= 50; i++) {
            assertThat(allText).contains("Sentence number " + i);
        }

        // Each candidate must be within the max length
        for (RequirementCandidate c : candidates) {
            assertThat(c.getText().length()).isLessThanOrEqualTo(2000);
        }
    }

    // ── A.1 — DOCX style heading markers ──────────────────────────────────────

    @Test
    void docxHeadingMarkersAreRecognised() {
        String text = "[H1] Introduction\n\n" +
                "This chapter describes the fundamental requirements for the information system " +
                "architecture in public sector applications.\n\n" +
                "[H2] Scope\n\n" +
                "The scope of this document covers all architectural requirements " +
                "relevant to the public administration domain context.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getSectionHeading()).isEqualTo("Introduction");
        // Second candidate should have hierarchical path
        assertThat(candidates.get(1).getSectionHeading()).isEqualTo("Introduction > Scope");
    }

    // ── A.3 — Hierarchical sectionPath ────────────────────────────────────────

    @Test
    void hierarchicalSectionPathIsBuiltFromNestedHeadings() {
        String text = "Chapter 1 Architecture\n\n" +
                "Section 2 Security\n\n" +
                "The system must enforce role-based access control for all administrative " +
                "operations and maintain audit logs of critical actions.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getSectionHeading())
                .isEqualTo("Chapter 1 Architecture > Section 2 Security");
    }

    @Test
    void sameLevelHeadingReplacesCurrentOnStack() {
        String text = "§ 1 Allgemeines\n\n" +
                "Die allgemeinen Bestimmungen gelten für alle Verfahren der Verwaltung und sind verbindlich.\n\n" +
                "§ 2 Datenschutz\n\n" +
                "Personenbezogene Daten dürfen nur verarbeitet werden, wenn eine gesetzliche Grundlage vorliegt.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(2);
        // §1 and §2 are the same level — §2 replaces §1, not nested
        assertThat(candidates.get(0).getSectionHeading()).isEqualTo("§ 1 Allgemeines");
        assertThat(candidates.get(1).getSectionHeading()).isEqualTo("§ 2 Datenschutz");
    }

    @Test
    void deeperLevelHeadingPopsCorrectly() {
        String text = "Chapter 1 Overview\n\n" +
                "Section 2 Details\n\n" +
                "This section covers the detailed requirements for secure data processing " +
                "across all application components and integration layers.\n\n" +
                "Chapter 3 Deployment\n\n" +
                "The deployment strategy must support continuous delivery for all microservices " +
                "and maintain zero-downtime during rolling updates.";

        List<RequirementCandidate> candidates = parserService.extractCandidates(text);

        assertThat(candidates).hasSize(2);
        // "Section 2" is under "Chapter 1"
        assertThat(candidates.get(0).getSectionHeading())
                .isEqualTo("Chapter 1 Overview > Section 2 Details");
        // "Chapter 3" resets the stack (pops both Section 2 and Chapter 1)
        assertThat(candidates.get(1).getSectionHeading())
                .isEqualTo("Chapter 3 Deployment");
    }

    @Test
    void overlongSentenceIsHardSplit() {
        // A single "sentence" (no period/punctuation) longer than 2000 chars
        String longSentence = "X".repeat(3000);

        List<RequirementCandidate> candidates = parserService.extractCandidates(longSentence);

        // Must produce at least 2 candidates from the hard split
        assertThat(candidates).hasSizeGreaterThan(1);
        // Each chunk must respect the max length
        for (RequirementCandidate c : candidates) {
            assertThat(c.getText().length()).isLessThanOrEqualTo(2000);
        }
    }
}
