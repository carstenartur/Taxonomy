package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the generated task-hierarchy DOM to its ergonomic CSS and progression contract. */
class TaskHierarchyStyleContractTest {

    @Test
    void generatedDisclosureClassesMatchTheStylesheetSelectors() throws Exception {
        String source = resource("/static/js/shared/taxonomy-onboarding.js");

        assertThat(source)
                .contains("next.className = 'analysis-next-action'")
                .contains("details.className = 'operational-context-details'")
                .contains("title.className = 'operational-context-title'")
                .contains("body.className = 'operational-context-content'")
                .contains("body.className = 'analysis-secondary-tools-content'")
                .contains("legendDetails.className = 'score-legend-details mb-3'")
                .doesNotContain("analysis-task-next")
                .doesNotContain("operational-context-body")
                .doesNotContain("analysis-secondary-tools-body")
                .doesNotContain("analysis-score-legend");
    }

    @Test
    void taskNumberStylesCannotCollapseTheCopyContainer() throws Exception {
        String stylesheet = resource("/static/css/taxonomy-ergonomics.css");

        assertThat(stylesheet)
                .contains(".analysis-task-stages li > .analysis-task-number")
                .contains(".analysis-task-stages li[data-state=\"current\"] > .analysis-task-number")
                .contains(".analysis-task-stages li[data-state=\"complete\"] > .analysis-task-number")
                .contains(".analysis-task-stages li[data-state=\"error\"] > .analysis-task-number")
                .contains(".analysis-task-copy")
                .doesNotContain(".analysis-task-stages li > span")
                .doesNotContain("li[data-state=\"current\"] > span")
                .doesNotContain("li[data-state=\"complete\"] > span")
                .doesNotContain("li[data-state=\"error\"] > span");
    }

    @Test
    void errorRecoveryActionUsesTheAccessibleFilledDangerVariant() throws Exception {
        String source = resource("/static/js/shared/taxonomy-onboarding.js");

        assertThat(source)
                .contains("nextAction.className = 'btn btn-sm btn-danger'")
                .doesNotContain("nextAction.className = 'btn btn-sm btn-outline-danger'");
    }

    @Test
    void reviewingResultsAdvancesToARealContinuationStage() throws Exception {
        String source = resource("/static/js/shared/taxonomy-onboarding.js");

        assertThat(source)
                .contains("reviewAcknowledged = true")
                .contains("setTaskState(3, 'current')")
                .contains("nextAction.textContent = t('analysis.task.next.continue')")
                .contains("nextAction.dataset.action = 'open-architecture'")
                .contains("window.navigateToPage('architecture')");
    }

    @Test
    void continuationActionIsAvailableInBothSupportedLocales() throws Exception {
        assertThat(resource("/i18n/messages_task_focus.properties"))
                .contains("analysis.task.next.continue=Explore architecture context");
        assertThat(resource("/i18n/messages_task_focus_de.properties"))
                .contains("analysis.task.next.continue=Architekturkontext erkunden");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = TaskHierarchyStyleContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
