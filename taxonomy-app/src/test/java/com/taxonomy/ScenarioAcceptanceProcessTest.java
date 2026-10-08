package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scenario owns its application settings; only explicit UI test flags go on argv. */
class ScenarioAcceptanceProcessTest {
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

    @ParameterizedTest
    @ValueSource(strings = {"taxonomy.test.browser", "webdriver.chrome.driver", "scenario.chrome.binary",
            "taxonomy.test.browser.version", "taxonomy.test.browser.cache", "taxonomy.test.browser.offline",
            "selenium.container.image", "scenario.screenshot.directory"})
    void browserRuntimeAndEvidencePathsSurviveTheRealJvmBoundary(String key) throws Exception {
        assertTrue(forwarded(key), "Browser configuration must reach the application JVM: " + key);
        assertFalse(forwarded(key + ".secret"), "Only the exact property name may be forwarded");
    }

    private static boolean forwarded(String key) throws Exception {
        var selector = ScenarioAcceptanceProcess.class.getDeclaredMethod("testProperty", String.class);
        selector.setAccessible(true);
        return (boolean) selector.invoke(null, key);
    }
}
