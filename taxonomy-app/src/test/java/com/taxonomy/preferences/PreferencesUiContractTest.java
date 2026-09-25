package com.taxonomy.preferences;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PreferencesUiContractTest {

    private static final Path ROOT = locateRoot();

    @Test
    void preferencesEditorPreservesSessionAndOnlyPersistsValidDirtyValues() throws IOException {
        String template = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/templates/index.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("type=\"button\" id=\"prefSaveBtn\"")
                .contains("type=\"button\" id=\"prefResetBtn\"")
                .contains("th:text=\"#{preferences.session.info}\"")
                .contains("th:text=\"#{preferences.repository.info}\"")
                .contains("data-pref-key=\"llm.rpm\"")
                .contains("data-pref-key=\"llm.timeout.seconds\"")
                .contains("data-pref-key=\"rate-limit.per-minute\"")
                .contains("data-pref-key=\"limits.max-business-text\"")
                .contains("data-pref-key=\"limits.max-architecture-nodes\"")
                .contains("data-pref-key=\"diagram.policy\"")
                .doesNotContain(
                        "data-pref-key=\"analysis.min-relevance-score\"",
                        "data-pref-key=\"dsl.default-branch\"",
                        "data-pref-key=\"dsl.project-name\"",
                        "data-pref-key=\"dsl.auto-save.interval-seconds\"",
                        "data-pref-key=\"dsl.remote.url\"",
                        "data-pref-key=\"dsl.remote.token\"",
                        "data-pref-key=\"dsl.remote.push-on-commit\"",
                        "data-pref-key=\"limits.max-export-nodes\"")
                .contains("var preferencesLoaded = false;")
                .contains("var requestInFlight = false;")
                .contains("function collectChanges()")
                .contains("el.checkValidity()")
                .contains("samePreferenceValue(currentPrefs[key], candidate.value)")
                .contains("body: JSON.stringify(result.changes)")
                .contains("Object.keys(result.changes).length === 0")
                .contains("invalid.reportValidity()")
                .contains("setPreferenceBusy(true)")
                .contains("saveBtn.setAttribute('aria-busy'")
                .contains("if (isPreferencesActive()) loadPreferences();")
                .contains("window.addEventListener('hashchange'")
                .contains("window.location.hash === '#preferences'")
                .contains("requestJson('/api/preferences/history')")
                .contains("t('preferences.saved')")
                .contains("t('preferences.reset')")
                .contains("t('preferences.reset.confirm')")
                .contains("t('preferences.load.failed'")
                .doesNotContain(
                        "showPrefStatus('Preferences saved successfully.'",
                        "showPrefStatus('Save failed:",
                        "confirm('Reset all preferences to application defaults?')",
                        "tbody.innerHTML = '<tr><td colspan=\"4\" class=\"text-muted small\">No history yet.");
    }

    @Test
    void preferencesFeedbackIsLocalizedInEnglishAndGerman() throws IOException {
        String english = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/i18n/messages.properties"),
                StandardCharsets.UTF_8);
        String german = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/i18n/messages_de.properties"),
                StandardCharsets.UTF_8);

        for (String key : new String[]{
                "preferences.session.info=",
                "preferences.repository.info=",
                "preferences.load.failed=",
                "preferences.no.changes=",
                "preferences.invalid=",
                "preferences.reset.confirm=",
                "preferences.history.failed="
        }) {
            assertThat(english).contains(key);
            assertThat(german).contains(key);
        }
    }

    private static Path locateRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
