package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PortfolioFingerprintServiceTest {

    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void nullContentUsesTheCanonicalEmptyPayloadFingerprint() {
        PortfolioFingerprintService service =
                new PortfolioFingerprintService(null, null);

        assertEquals(EMPTY_SHA256, service.contentFingerprint(null));
        assertEquals(service.contentFingerprint(""), service.contentFingerprint(null));
    }

    @Test
    void portfolioPromptFingerprintBindsShippedRelationInstructions() {
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        var service = new PortfolioFingerprintService(null, templates);
        // Without overrides the old portfolio fingerprint used the same content as the scoring fingerprint.
        String previousInput = templates.analysisFingerprintTemplates(false).entrySet().stream()
                .map(entry -> entry.getKey() + '\u001f' + entry.getValue() + "\n")
                .collect(Collectors.joining());

        assertNotEquals(service.contentFingerprint(previousInput), service.promptFingerprint(),
                "Older snapshot identities did not include the shipped relation policy");
    }
}
