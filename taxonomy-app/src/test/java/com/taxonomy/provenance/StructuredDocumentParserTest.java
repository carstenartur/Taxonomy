package com.taxonomy.provenance;

import com.taxonomy.dto.DocumentSection;
import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.provenance.service.StructuredDocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StructuredDocumentParser}.
 */
class StructuredDocumentParserTest {

    private StructuredDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new StructuredDocumentParser(new DocumentParserService());
    }

    @Test
    void emptyTextReturnsRootOnly() {
        DocumentSection root = parser.parse("");
        assertThat(root.getLevel()).isZero();
        assertThat(root.getHeading()).isEqualTo("Document Root");
        assertThat(root.getChildren()).isEmpty();
        assertThat(root.getParagraphs()).isEmpty();
    }

    @Test
    void nullTextReturnsRootOnly() {
        DocumentSection root = parser.parse(null);
        assertThat(root.getLevel()).isZero();
        assertThat(root.getChildren()).isEmpty();
    }

    @Test
    void singleHeadingWithParagraph() {
        String text = "Chapter 1 Introduction\n\n" +
                "This chapter provides an overview of the architectural requirements " +
                "for the public sector information system.";

        DocumentSection root = parser.parse(text);

        assertThat(root.getChildren()).hasSize(1);
        DocumentSection ch1 = root.getChildren().get(0);
        assertThat(ch1.getHeading()).isEqualTo("Chapter 1 Introduction");
        assertThat(ch1.getLevel()).isEqualTo(1);
        assertThat(ch1.getSectionPath()).isEqualTo("Chapter 1 Introduction");
        assertThat(ch1.getParagraphs()).hasSize(1);
        assertThat(ch1.getParagraphs().get(0)).contains("architectural requirements");
    }

    @Test
    void nestedHeadingsCreateHierarchy() {
        String text = "Chapter 1 Architecture\n\n" +
                "Section 2 Security\n\n" +
                "The system must enforce role-based access control for all administrative " +
                "operations and maintain audit logs of critical actions.";

        DocumentSection root = parser.parse(text);

        assertThat(root.getChildren()).hasSize(1);
        DocumentSection ch1 = root.getChildren().get(0);
        assertThat(ch1.getHeading()).isEqualTo("Chapter 1 Architecture");

        assertThat(ch1.getChildren()).hasSize(1);
        DocumentSection sec2 = ch1.getChildren().get(0);
        assertThat(sec2.getHeading()).isEqualTo("Section 2 Security");
        assertThat(sec2.getSectionPath()).isEqualTo("Chapter 1 Architecture > Section 2 Security");
        assertThat(sec2.getParagraphs()).hasSize(1);
    }

    @Test
    void sameLevelHeadingsAreSiblings() {
        String text = "§ 1 Allgemeines\n\n" +
                "Die allgemeinen Bestimmungen gelten für alle Verfahren der Verwaltung und sind verbindlich.\n\n" +
                "§ 2 Datenschutz\n\n" +
                "Personenbezogene Daten dürfen nur verarbeitet werden, wenn eine gesetzliche Grundlage vorliegt.";

        DocumentSection root = parser.parse(text);

        assertThat(root.getChildren()).hasSize(2);
        assertThat(root.getChildren().get(0).getHeading()).isEqualTo("§ 1 Allgemeines");
        assertThat(root.getChildren().get(1).getHeading()).isEqualTo("§ 2 Datenschutz");
    }

    @Test
    void deeperLevelPopsCorrectly() {
        String text = "Chapter 1 Overview\n\n" +
                "Section 2 Details\n\n" +
                "This section covers the detailed requirements for secure data processing " +
                "across all application components and integration layers.\n\n" +
                "Chapter 3 Deployment\n\n" +
                "The deployment strategy must support continuous delivery for all microservices.";

        DocumentSection root = parser.parse(text);

        assertThat(root.getChildren()).hasSize(2);
        DocumentSection ch1 = root.getChildren().get(0);
        assertThat(ch1.getHeading()).isEqualTo("Chapter 1 Overview");
        assertThat(ch1.getChildren()).hasSize(1);
        assertThat(ch1.getChildren().get(0).getHeading()).isEqualTo("Section 2 Details");

        DocumentSection ch3 = root.getChildren().get(1);
        assertThat(ch3.getHeading()).isEqualTo("Chapter 3 Deployment");
        assertThat(ch3.getChildren()).isEmpty();
    }

    @Test
    void paragraphsWithoutHeadingAttachToRoot() {
        String text = "This is a standalone paragraph without any heading structure. " +
                "It is long enough to pass the minimum length filter for extraction.";

        DocumentSection root = parser.parse(text);

        assertThat(root.getChildren()).isEmpty();
        assertThat(root.getParagraphs()).hasSize(1);
        assertThat(root.getParagraphs().get(0)).contains("standalone paragraph");
    }

    @Test
    void docxMarkerHeadingsAreRecognised() {
        String text = "[H1] Einleitung\n\n" +
                "Dieses Dokument beschreibt die Anforderungen an das Verwaltungssystem " +
                "und dessen technische Umsetzung im Detail.\n\n" +
                "[H2] Geltungsbereich\n\n" +
                "Der Geltungsbereich umfasst alle Verwaltungsverfahren der Behörde " +
                "und die damit verbundenen technischen Systeme.";

        DocumentSection root = parser.parse(text);

        assertThat(root.getChildren()).hasSize(1);
        DocumentSection h1 = root.getChildren().get(0);
        assertThat(h1.getHeading()).isEqualTo("Einleitung");
        assertThat(h1.getChildren()).hasSize(1);
        assertThat(h1.getChildren().get(0).getHeading()).isEqualTo("Geltungsbereich");
        assertThat(h1.getChildren().get(0).getSectionPath())
                .isEqualTo("Einleitung > Geltungsbereich");
    }
}
