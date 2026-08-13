package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RelationGitCommandUiContractTest {

    private static final Path RELATION_API = Path.of(
            "src/main/resources/static/js/api/relations-api.js");
    private static final Path RELATION_ADAPTER = Path.of(
            "src/main/resources/static/js/relations/"
                    + "taxonomy-relations-git-commands.js");
    private static final Path RELATION_BROWSER = Path.of(
            "src/main/resources/static/js/relations/taxonomy-relations.js");
    private static final Path QUALITY_MODULE = Path.of(
            "src/main/resources/static/js/relations/taxonomy-quality.js");

    @Test
    void loadedAdapterCapturesLegacyButtonsAndUsesIdentityCommands()
            throws Exception {
        String api = Files.readString(RELATION_API);
        String adapter = Files.readString(RELATION_ADAPTER);
        String loader = Files.readString(QUALITY_MODULE);

        assertThat(loader)
                .contains("var loader = document.currentScript;")
                .contains("window.TaxonomyRelationsApiReady")
                .contains("new URL('../api/relations-api.js', loader.src)")
                .contains("'taxonomy-relations-git-commands.js', loader.src")
                .doesNotContain("script.src = '/js/");
        assertThat(api)
                .contains("window.TaxonomyRelationsApi")
                .contains("/api/architecture/relations/")
                .contains("function readSnapshot(relationType)")
                .contains("function upsertRelation(")
                .contains("function deleteRelation(");
        assertThat(adapter)
                .contains("document.addEventListener('click', "
                        + "captureRelationCommand, true)")
                .contains("event.stopImmediatePropagation()")
                .contains("RelationsApi().readSnapshot(type)")
                .contains("RelationsApi().upsertRelation(")
                .contains("RelationsApi().deleteRelation(")
                .contains("'If-Match'")
                .contains("'If-None-Match'")
                .contains("'Idempotency-Key'")
                .contains("response.status === 202")
                .contains("response.status === 412")
                .doesNotContain("fetch(")
                .doesNotContain("'/api/relations/' + id")
                .doesNotContain("relationsById")
                .doesNotContain("function ensureSnapshot")
                .doesNotContain("addEventListener('toggle'")
                .doesNotContain("typeFilter.addEventListener");
    }

    @Test
    void relationBrowserFailsClosedInsteadOfRenderingUnavailableProjectionAsEmpty()
            throws Exception {
        String browser = Files.readString(RELATION_BROWSER);

        assertThat(browser)
                .contains("relationApiReady()")
                .contains("api.readSnapshot(type)")
                .contains("if (!response.ok)")
                .contains("throw projectionReadError(response)")
                .contains("X-Taxonomy-Relation-Projection-State")
                .contains("X-Taxonomy-Relation-Pending-Recovery")
                .contains("renderRelationsLoadError(content, error)")
                .contains("retry.addEventListener('click', loadRelations)")
                .contains("content.setAttribute('aria-busy', 'false')")
                .doesNotContain("r.ok ? r.json() : []")
                .doesNotContain(
                        "var url = type ? '/api/relations?type='");
    }

    @Test
    void createAndDeleteRefreshTheAuthoritativeSnapshotImmediately()
            throws Exception {
        String adapter = Files.readString(RELATION_ADAPTER);

        int createStart = adapter.indexOf(
                "    function createRelation() {");
        int createRefresh = adapter.indexOf(
                "refreshSnapshot()", createStart);
        int createCommand = adapter.indexOf(
                "return RelationsApi().upsertRelation(", createRefresh);
        int deleteStart = adapter.indexOf(
                "    function deleteRelation(button) {");
        int identityUse = adapter.indexOf(
                "var relation = relationIdentity(button);", deleteStart);
        int deleteRefresh = adapter.indexOf(
                "refreshSnapshot()", identityUse);
        int identityProof = adapter.indexOf(
                "sameIdentity(candidate, relation)", deleteRefresh);
        int deleteCommand = adapter.indexOf(
                "return RelationsApi().deleteRelation(", identityProof);

        assertThat(createStart).isGreaterThanOrEqualTo(0);
        assertThat(createRefresh).isGreaterThan(createStart);
        assertThat(createCommand).isGreaterThan(createRefresh);
        assertThat(deleteStart).isGreaterThan(createCommand);
        assertThat(identityUse).isGreaterThan(deleteStart);
        assertThat(deleteRefresh).isGreaterThan(identityUse);
        assertThat(identityProof).isGreaterThan(deleteRefresh);
        assertThat(deleteCommand).isGreaterThan(identityProof);
    }

    @Test
    void transportLivesInTheNamedApiLayer() throws Exception {
        String api = Files.readString(RELATION_API);
        String adapter = Files.readString(RELATION_ADAPTER);

        assertThat(api)
                .contains("return fetch(url, { cache: 'no-store' })")
                .contains("return fetch(commandUrl(")
                .contains("method: 'PUT'")
                .contains("method: 'DELETE'");
        assertThat(adapter).doesNotContain("fetch(");
    }

    @Test
    void deleteUsesIdentityFromTheSameVisibleTableRow() throws Exception {
        String adapter = Files.readString(RELATION_ADAPTER);

        int rowFunction = adapter.indexOf(
                "    function relationIdentity(button) {");
        int rowLookup = adapter.indexOf(
                "button.closest('tr')", rowFunction);
        int source = adapter.indexOf(
                "row.cells[0].textContent.trim()", rowLookup);
        int target = adapter.indexOf(
                "row.cells[1].textContent.trim()", rowLookup);
        int type = adapter.indexOf(
                "row.cells[2].textContent.trim()", rowLookup);
        int sameIdentity = adapter.indexOf(
                "    function sameIdentity(left, right) {", type);

        assertThat(rowFunction).isGreaterThanOrEqualTo(0);
        assertThat(rowLookup).isGreaterThan(rowFunction);
        assertThat(source).isGreaterThan(rowLookup);
        assertThat(target).isGreaterThan(source);
        assertThat(type).isGreaterThan(target);
        assertThat(sameIdentity).isGreaterThan(type);
    }

    @Test
    void browserRefreshDelegatesToTheExistingRendererWithoutOwnFetch()
            throws Exception {
        String adapter = Files.readString(RELATION_ADAPTER);
        int refreshStart = adapter.indexOf(
                "    function refreshBrowser() {");
        int refreshEnd = adapter.indexOf(
                "    function projectionError", refreshStart);
        String refreshBody = adapter.substring(refreshStart, refreshEnd);

        assertThat(refreshBody)
                .contains("window.TaxonomyRelations.loadRelations()")
                .doesNotContain("refreshSnapshot()")
                .doesNotContain("fetch(");
    }
}
