package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Inserted requirements and taxonomy descriptions must never become template source. */
class PromptLiteralDataTest {
    private final PromptTemplateService service = new PromptTemplateService();
    private static final String DATA = "\"Arbeitsschutz\" \u00e4 { } ``` $1 \\ "
            + "{{NODE_LIST}} {{TAXONOMY_NAME}} {{PARENT_SCORE}} {{EXPECTED_KEYS}} {{MIN_SCORE}}";

    @Test
    void scoringReplacesOnlyOriginalTemplateTokensIncludingRepeatedOnes() {
        service.setTemplate("BR", "{{BUSINESS_TEXT}}|{{NODE_LIST}}|{{PARENT_SCORE}}|{{EXPECTED_KEYS}}|{{BUSINESS_TEXT}}|{{UNKNOWN}}");
        assertEquals(DATA + "|" + DATA + "|70|BR|" + DATA + "|{{UNKNOWN}}",
                service.renderPrompt("BR", DATA, DATA, 70, "BR"));
    }

    @Test
    void optionalExpectedKeysRemainEmptyWithoutInterpretingData() {
        service.setTemplate("CP", "{{NODE_LIST}}|{{EXPECTED_KEYS}}|{{TAXONOMY_NAME}}");
        assertEquals(DATA + "||Capabilities", service.renderPrompt("CP", "requirement", DATA, 100, null));
    }

    @Test
    void productDataCannotChangeTheMinimumScoreOrExpectedKeys() {
        service.setTemplate("IP-product", "{{BUSINESS_TEXT}}|{{NODE_LIST}}|{{EXPECTED_KEYS}}|{{MIN_SCORE}}");
        assertEquals(DATA + "|" + DATA + "|IP|30", service.renderProductPrompt(DATA, DATA, "IP", 30));
    }

    @Test
    void justificationRetainsPlaceholdersInsideRequirementsAndPaths() {
        String requirement = "Requirement {{LEAF_CODE}} {{PATH_DESCRIPTION}} {{CROSS_REFERENCES}}";
        String path = "Path {{CROSS_REFERENCES}}";
        String prompt = service.renderLeafJustificationPrompt(requirement, "CP", path, "references");
        assertTrue(prompt.contains("Business Requirement: " + requirement));
        assertTrue(prompt.contains(path));
        assertTrue(prompt.contains("ending at CP"));
    }

    @Test
    void regulationDocumentDoesNotExpandEmbeddedNodeListPlaceholder() {
        service.setTemplate("reg-map-default", "{{DOCUMENT_TEXT}}|{{NODE_LIST}}");
        assertEquals(DATA + "|nodes", service.renderRegulationMappingPrompt("reg-map-default", DATA, "nodes"));
    }
}
