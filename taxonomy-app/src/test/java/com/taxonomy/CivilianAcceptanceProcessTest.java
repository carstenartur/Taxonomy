package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scenario owns its application settings; only explicit UI test flags go on argv. */
class CivilianAcceptanceProcessTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "custom.llm.api.key", "spring.datasource.password", "taxonomy.admin-password",
            "embedding.token", "llm.api-key", "spring.datasource.url",
            "taxonomy.unrecognized-setting", "custom.unrecognized-setting"
    })
    void secretCapableApplicationSettingsNeverBecomeChildArguments(String key) throws Exception {
        assertFalse(forwarded(key), "Application setting must not be copied to child argv: " + key);
    }

    @Test
    void explicitNonSecretBrowserFlagsRemainAvailable() throws Exception {
        assertTrue(forwarded("generateScreenshots"));
        assertTrue(forwarded("java.awt.headless"));
        assertFalse(forwarded("generateScreenshots.password"));
        assertFalse(forwarded("java.awt.headless.secret"));
    }

    private static boolean forwarded(String key) throws Exception {
        var selector = CivilianAcceptanceProcess.class.getDeclaredMethod("testProperty", String.class);
        selector.setAccessible(true);
        return (boolean) selector.invoke(null, key);
    }
}
