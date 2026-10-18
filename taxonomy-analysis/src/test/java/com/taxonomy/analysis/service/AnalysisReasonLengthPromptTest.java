package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.taxonomy.dto.AnalysisReasonLength;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisReasonLengthPromptTest {
    @Test
    void categoryPolicyAllowsAJustifiedStopAtTheSupportedParent() {
        var prompts = new PromptTemplateService();
        prompts.loadDefaults();
        String prompt = prompts.renderPrompt("BR", "Provide a public information service.",
                "BR-1223: Civil Governance Roles - Establish local institutions and the rule of law.\n", 55, "BR-1223");
        assertTrue(prompt.contains("no offered child has a supported match"));
        assertTrue(prompt.contains("explicit zero for every offered child"));
        assertTrue(prompt.contains("parent assessment remains valid"));
        assertTrue(prompt.contains("not a confidence or implementation-coverage percentage"));
    }

    @Test
    void defaultReasonPolicyReachesEveryScoringTemplateIncludingOverrides() {
        var prompts = new PromptTemplateService();
        prompts.setTemplate("BP", "Legacy short text: {{BUSINESS_TEXT}} {{NODE_LIST}} {{EXPECTED_KEYS}}");
        prompts.setTemplate("root-relevance", "Root: {{BUSINESS_TEXT}} {{NODE_LIST}} {{EXPECTED_KEYS}}");
        prompts.setTemplate("IP-product", "Product: {{BUSINESS_TEXT}} {{NODE_LIST}} {{EXPECTED_KEYS}}");

        for (String prompt : List.of(
                prompts.renderPrompt("BP", "Authored requirement", "Nodes", 0.5, "BP"),
                prompts.renderRootPrompt("BP", "Authored requirement", "Nodes"),
                prompts.renderProductPrompt("Authored requirement", "Nodes", "IP", 50))) {
            assertTrue(prompt.contains("Mandatory decision reason policy (reason-length-v1): standard"), prompt);
            assertTrue(prompt.contains("1–2 sentences"), prompt);
            assertTrue(prompt.contains("supplied requirement and catalogue evidence"), prompt);
            assertTrue(prompt.contains("0.5 means 0.5%"), "Reason length must preserve the numeric contract");
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisReasonLength.class)
    void capturedModeControlsRootCategoryProductAndLeafReasons(AnalysisReasonLength mode) {
        var prompts = new PromptTemplateService();
        prompts.loadDefaults();
        prompts.setTemplate("justify-leaf", "CUSTOM LEAF {{BUSINESS_TEXT}} {{LEAF_CODE}}");
        String expected = switch (mode) {
            case SHORT -> "one concise sentence";
            case STANDARD -> "1–2 sentences";
            case DETAILED -> "3–5 substantive sentences";
        };
        try (var ignored = AnalysisReasonContext.open(mode)) {
            for (String prompt : List.of(
                    prompts.renderPrompt("BP", "Requirement {{NODE_LIST}} $1", "Nodes", 0.5, "BP"),
                    prompts.renderRootPrompt("BP", "Requirement", "Nodes"),
                    prompts.renderProductPrompt("Requirement", "Nodes", "IP", 50),
                    prompts.renderLeafJustificationPrompt("Requirement", "BP", "Path", "References"))) {
                assertTrue(prompt.contains("reason-length-v1): " + mode.value()), prompt);
                assertTrue(prompt.contains(expected), prompt);
                assertTrue(prompt.contains("do not invent facts"), prompt);
                if (mode == AnalysisReasonLength.DETAILED) {
                    assertTrue(prompt.contains("at most 100 words"));
                    assertTrue(prompt.contains("requirement facts"));
                    assertTrue(prompt.contains("justify the assigned weight or suitability"));
                    assertTrue(prompt.contains("uncertainty only when the supplied evidence supports it"));
                }
            }
            assertTrue(prompts.renderLeafJustificationPrompt("Requirement", "BP", "Path", "References")
                    .startsWith("CUSTOM LEAF Requirement BP"));
            assertTrue(prompts.renderPrompt("BP", "Requirement {{NODE_LIST}} $1", "Nodes", 0.5, "BP")
                    .contains("Requirement {{NODE_LIST}} $1"), "User input remains literal data");
        }
        assertNull(AnalysisReasonContext.current());
    }

    @Test
    void independentScopesRestoreTheOriginalPolicyAfterFailure() {
        try (var outer = AnalysisReasonContext.open(AnalysisReasonLength.SHORT)) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (var inner = AnalysisReasonContext.open(AnalysisReasonLength.DETAILED)) {
                    assertEquals(AnalysisReasonLength.DETAILED, AnalysisReasonContext.current());
                    throw new IllegalArgumentException("authored failure");
                }
            });
            assertEquals(AnalysisReasonLength.SHORT, AnalysisReasonContext.current());
        }
        assertNull(AnalysisReasonContext.current());
    }
}
