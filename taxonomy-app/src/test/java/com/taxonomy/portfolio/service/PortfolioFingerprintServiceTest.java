package com.taxonomy.portfolio.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
