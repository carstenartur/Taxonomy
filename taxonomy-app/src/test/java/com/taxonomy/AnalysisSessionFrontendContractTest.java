package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisSessionFrontendContractTest {

    @Test
    void invalidationClearsEveryRequirementDerivedStateWithoutTouchingPortfolioData()
            throws IOException {
        String script = sessionSources();

        assertThat(script)
                .contains("S.currentScores = null")
                .contains("S.currentReasons = {}")
                .contains("S.currentDiscrepancies = []")
                .contains("S.currentArchView = null")
                .contains("window._currentProvisionalRelations = []")
                .contains("gapAnalysisContent")
                .contains("patternDetectionContent")
                .contains("recommendationContent")
                .contains("requirementImpactResults")
                .contains("copilotContent")
                .contains("architectureViewPanel")
                .contains("suggestedRelationsPanel")
                .contains("taxonomy:analysis-invalidated")
                .contains("activeAnalysisControllers")
                .contains("controller.abort('analysis-invalidated')")
                .contains("analysisGeneration += 1")
                .doesNotContain("/api/projects/**/delete")
                .doesNotContain("/api/relations/**/delete");
    }

    @Test
    void staleTextOffersDistinctBusinessActionsWithContrastSafeDestructiveChoice()
            throws IOException {
        String script = sessionSources();

        assertThat(script)
                .contains("discard-edit")
                .contains("discard-analysis")
                .contains("add-requirement")
                .contains("new-project")
                .contains("save-version")
                .contains("className: 'btn btn-sm btn-danger'")
                .doesNotContain("className: 'btn btn-sm btn-outline-danger'")
                .contains("/api/projects/")
                .contains("/requirements")
                .contains("/versions");
    }

    @Test
    void architecturePromotionActionsFollowTheExistingRoleBoundary() throws IOException {
        String roleSurface = resource(
                "/static/js/security/taxonomy-role-surface.js");

        assertThat(roleSurface)
                .contains("[data-analysis-session-action=\"add-requirement\"]")
                .contains("[data-analysis-session-action=\"new-project\"]")
                .contains("[data-analysis-session-action=\"save-version\"]")
                .contains("[data-analysis-session-action=\"preserve-requirement\"]")
                .contains("applySelectorGroup(scope, architectureSelectors, canMutateArchitecture())");
    }

    @Test
    void draftConflictOffersContrastSafeRecoveryActions() throws IOException {
        String draft = resource("/static/js/core/taxonomy-analysis-session-draft.js");

        assertThat(draft)
                .contains("showActionAlert('danger'")
                .contains("className: 'btn btn-sm btn-light'")
                .contains("className: 'btn btn-sm btn-outline-dark'")
                .doesNotContain("className: 'btn btn-sm btn-outline-light'");
    }

    @Test
    void workingDraftIsWorkspacePinnedSerializedAndOptimisticallyVersioned()
            throws IOException {
        String session = sessionSources();
        String state = resource("/static/js/core/taxonomy-state.js");
        String loader = resource("/static/js/core/taxonomy-analysis-session.js");

        assertThat(session)
                .contains("/api/analysis-drafts/")
                .contains("expectedVersion: runtime.version")
                .contains("draftMutationQueue")
                .contains("body.expectedVersion = runtime.version")
                .contains("error.status === 409")
                .contains("X-Taxonomy-Workspace-Id")
                .contains("sessionStorage")
                .contains("window.location.reload()")
                .contains("taxonomy:analysis-draft-restored");
        assertThat(loader)
                .contains("taxonomy-analysis-session-core.js")
                .contains("taxonomy-analysis-session-transport.js")
                .contains("taxonomy-analysis-session-ui.js")
                .contains("taxonomy-analysis-session-draft.js")
                .contains("taxonomy-analysis-session-projects.js");
        assertThat(state).contains("taxonomy-analysis-session.js");
    }

    @Test
    void restoredDraftIncludesAnalysisOptionsAndRejectsSupersededResponses()
            throws IOException {
        String session = sessionSources();

        assertThat(session)
                .contains("payload.analysisOptions = analysisOptions()")
                .contains("includeArchitectureView")
                .contains("interactiveMode")
                .contains("providerSelect")
                .contains("guardedResponse")
                .contains("neverSettles")
                .contains("copilotIntervals");
    }

    @Test
    void postgresMigrationCarriesTheSameOptimisticLockContract() throws IOException {
        String migration = resource(
                "/db/migration/taxonomy/postgresql/V16__analysis_working_drafts.sql");

        assertThat(migration)
                .contains("create table if not exists analysis_working_draft")
                .contains("scope_key varchar(1024) not null")
                .contains("payload_json text not null")
                .contains("row_version bigint not null")
                .contains("unique (scope_key, username)");
    }

    private static String sessionSources() throws IOException {
        return resource("/static/js/core/taxonomy-analysis-session-core.js")
                + resource("/static/js/core/taxonomy-analysis-session-transport.js")
                + resource("/static/js/core/taxonomy-analysis-session-ui.js")
                + resource("/static/js/core/taxonomy-analysis-session-draft.js")
                + resource("/static/js/core/taxonomy-analysis-session-projects.js");
    }

    private static String resource(String path) throws IOException {
        try (var stream = AnalysisSessionFrontendContractTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
