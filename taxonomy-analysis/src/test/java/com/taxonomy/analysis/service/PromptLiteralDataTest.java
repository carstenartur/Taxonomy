package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Inserted requirements and taxonomy descriptions must never become template source. */
class PromptLiteralDataTest {
    private final PromptTemplateService service = new PromptTemplateService();
    private static final String DATA = "\"Arbeitsschutz\" \u00e4 { } ``` $1 \\ "
            + "{{NODE_LIST}} {{TAXONOMY_NAME}} {{PARENT_SCORE}} {{EXPECTED_KEYS}} {{MIN_SCORE}}";
    private static final String NUMERIC_CONTRACT = "\nMandatory numeric output contract (decimal-percent-v2): "
            + "this overrides older integer-only score instructions. Return JSON numbers with decimals allowed, "
            + "between 0 and 100. Use a decimal point, not a comma or a percent sign in the number. "
            + "0.5 means 0.5%, not 50%. Do not round a positive contribution to zero. "
            + "Explicit zero is negative evidence; missing or failed assessment is not zero.\n";

    @Test
    void scoringReplacesOnlyOriginalTemplateTokensIncludingRepeatedOnes() {
        service.setTemplate("BR", "{{BUSINESS_TEXT}}|{{NODE_LIST}}|{{PARENT_SCORE}}|{{EXPECTED_KEYS}}|{{BUSINESS_TEXT}}|{{UNKNOWN}}");
        String prompt = service.renderPrompt("BR", DATA, DATA, 70, "BR");
        assertEquals(DATA + "|" + DATA + "|70|BR|" + DATA + "|{{UNKNOWN}}" + scoreContract(70), withoutReasonPolicy(prompt));
    }

    @Test
    void optionalExpectedKeysRemainEmptyWithoutInterpretingData() {
        service.setTemplate("CP", "{{NODE_LIST}}|{{EXPECTED_KEYS}}|{{TAXONOMY_NAME}}");
        String prompt = service.renderPrompt("CP", "requirement", DATA, 100, null);
        assertEquals(DATA + "||Capabilities" + scoreContract(100), withoutReasonPolicy(prompt));
    }

    @Test
    void productDataCannotChangeTheMinimumScoreOrExpectedKeys() {
        service.setTemplate("IP-product", "{{BUSINESS_TEXT}}|{{NODE_LIST}}|{{EXPECTED_KEYS}}|{{MIN_SCORE}}");
        String prompt = service.renderProductPrompt(DATA, DATA, "IP", 30);
        assertEquals(DATA + "|" + DATA + "|IP|30" + NUMERIC_CONTRACT
                + "Product suitability is independent of parent relevance; do not divide the category budget between products.\n",
                withoutReasonPolicy(prompt));
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

    private static String scoreContract(int parentScore) {
        return NUMERIC_CONTRACT + "The scores must sum to " + parentScore
                + ". Preserve a positive child below 1%; for a 1% budget, two equal children have 0.5 each.\n";
    }

    private static String withoutReasonPolicy(String prompt) {
        String marker = "\nMandatory decision reason policy (reason-length-v1): ";
        int start = prompt.indexOf(marker);
        assertTrue(start >= 0, "The separately verified reason contract must follow the untouched literal template data");
        assertEquals(start, prompt.lastIndexOf(marker));
        return prompt.substring(0, start);
    }
}
