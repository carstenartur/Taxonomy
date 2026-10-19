package com.taxonomy.acceptance;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import com.taxonomy.reporting.render.document.WordDocumentWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioWordContentsQaTest {
    private static final String METOC_REASON =
            "Published environmental awareness is assigned to the situational-awareness family "
            + "without inferring military METOC support studies or other support artefacts.";

    @Test
    void readyContentsAcceptCompleteAuthoredMetocSupportProse() throws Exception {
        try (var document = report(METOC_REASON)) {
            assertThat(document.getParagraphs()).anySatisfy(paragraph ->
                    assertThat(paragraph.getText()).isEqualTo(METOC_REASON));
            assertDoesNotThrow(() -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" TOC \\o \"1-3\" ", " \t toC \\o \"1-3\" ", "TOC", "TOC\\o \"1-3\""})
    void rejectsActualComplexTocFields(String instruction) throws Exception {
        try (var document = report("Complete authored evidence")) {
            var paragraph = document.createParagraph();
            fieldCharacter(paragraph, STFldCharType.BEGIN);
            instruction(paragraph, instruction);
            fieldCharacter(paragraph, STFldCharType.SEPARATE);
            paragraph.createRun().setText("Cached contents");
            fieldCharacter(paragraph, STFldCharType.END);
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void rejectsTocInstructionSplitInsideItsKeywordAcrossRunsAndParagraphs() throws Exception {
        try (var document = report("Complete authored evidence")) {
            var first = document.createParagraph();
            fieldCharacter(first, STFldCharType.BEGIN);
            instruction(first, " T");
            var second = document.createParagraph();
            instruction(second, "O");
            instruction(second, "C \\o \"1-3\"");
            fieldCharacter(second, STFldCharType.END);
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" TOC \\o \"1-3\" ", "\tToC", "TOC\\h"})
    void rejectsActualSimpleTocFields(String instruction) throws Exception {
        try (var document = report("Complete authored evidence")) {
            document.createParagraph().getCTP().addNewFldSimple().setInstr(instruction);
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void rejectsNestedTocFieldWithoutJoiningItsParentsInstruction() throws Exception {
        try (var document = report("Complete authored evidence")) {
            var paragraph = document.createParagraph();
            fieldCharacter(paragraph, STFldCharType.BEGIN);
            instruction(paragraph, "IF ");
            fieldCharacter(paragraph, STFldCharType.BEGIN);
            instruction(paragraph, "to");
            instruction(paragraph, "c \\o \"1-3\"");
            fieldCharacter(paragraph, STFldCharType.END);
            instruction(paragraph, " = \"\" \"empty\" \"present\"");
            fieldCharacter(paragraph, STFldCharType.END);
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void rejectsUnterminatedTocInstructionAtEndOfDocument() throws Exception {
        try (var document = report("Complete authored evidence")) {
            var paragraph = document.createParagraph();
            fieldCharacter(paragraph, STFldCharType.BEGIN);
            instruction(paragraph, "t");
            instruction(paragraph, "oc \\o \"1-3\"");
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void allowsNonTocInstructionsWithTocWordsInTheirArgumentsAndResults() throws Exception {
        try (var document = report(METOC_REASON)) {
            var paragraph = document.createParagraph();
            fieldCharacter(paragraph, STFldCharType.BEGIN);
            instruction(paragraph, "HYPERLINK \"https://example.test/TOC chapter\"");
            fieldCharacter(paragraph, STFldCharType.SEPARATE);
            paragraph.createRun().setText("TOC navigation");
            fieldCharacter(paragraph, STFldCharType.END);
            document.createParagraph().getCTP().addNewFldSimple().setInstr("SEQ Figure \\* ARABIC");
            assertDoesNotThrow(() -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void doesNotJoinAdjacentIndependentFieldInstructions() throws Exception {
        try (var document = report("Complete authored evidence")) {
            var paragraph = document.createParagraph();
            for (String part : new String[]{"TO", "C \\o \"1-3\""}) {
                fieldCharacter(paragraph, STFldCharType.BEGIN);
                instruction(paragraph, part);
                fieldCharacter(paragraph, STFldCharType.END);
            }
            assertDoesNotThrow(() -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void rejectsAnActualTocNestedInsideSimpleFieldResults() throws Exception {
        try (var document = report("Complete authored evidence")) {
            var simple = document.createParagraph().getCTP().addNewFldSimple();
            simple.setInstr("IF 1 = 1 \"yes\" \"no\"");
            simple.addNewR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
            simple.addNewR().addNewInstrText().setStringValue("ToC \\o \"1-3\"");
            simple.addNewR().addNewFldChar().setFldCharType(STFldCharType.END);
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void keepsTheReadyNavigationLinkGate() throws Exception {
        try (var document = report("Complete authored evidence")) {
            document.getTables().getFirst().getRow(1).getCell(1).getParagraphs().getFirst()
                    .getCTP().getHyperlinkArray(0).setAnchor("absent_bookmark");
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    @Test
    void keepsTheNoAutomaticRefreshGate() throws Exception {
        try (var document = report("Complete authored evidence")) {
            document.getSettings().getCTSettings().addNewUpdateFields().setVal(true);
            assertThrows(AssertionError.class, () -> ScenarioExportQa.verifyReadyContents(document, 2));
        }
    }

    private static XWPFDocument report(String prose) throws Exception {
        try (var document = new XWPFDocument()) {
            var writer = new WordDocumentWriter(document, new DecisionReportLabels("en"));
            writer.contents(Map.of("evidence", "Complete evidence"), 2);
            writer.heading("Complete evidence", 1, "evidence");
            writer.paragraph(prose);
            writer.completeContents();
            var bytes = new ByteArrayOutputStream();
            document.write(bytes);
            return new XWPFDocument(new ByteArrayInputStream(bytes.toByteArray()));
        }
    }

    private static void fieldCharacter(XWPFParagraph paragraph, STFldCharType.Enum type) {
        paragraph.createRun().getCTR().addNewFldChar().setFldCharType(type);
    }

    private static void instruction(XWPFParagraph paragraph, String text) {
        paragraph.createRun().getCTR().addNewInstrText().setStringValue(text);
    }
}
