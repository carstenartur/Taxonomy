package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RelationGitCommandUiContractTest {

    private static final Path RELATION_ADAPTER = Path.of(
            "src/main/resources/static/js/relations/"
                    + "taxonomy-relations-git-commands.js");
    private static final Path QUALITY_MODULE = Path.of(
            "src/main/resources/static/js/relations/taxonomy-quality.js");

    @Test
    void loadedAdapterCapturesLegacyButtonsAndUsesIdentityCommands()
            throws Exception {
        String adapter = Files.readString(RELATION_ADAPTER);
        String loader = Files.readString(QUALITY_MODULE);

        assertThat(loader).contains(
                "/js/relations/taxonomy-relations-git-commands.js");
        assertThat(adapter)
                .contains("document.addEventListener('click', "
                        + "captureRelationCommand, true)")
                .contains("event.stopImmediatePropagation()")
                .contains("/api/architecture/relations/")
                .contains("'If-Match'")
                .contains("'If-None-Match'")
                .contains("'Idempotency-Key'")
                .contains("response.status === 202")
                .contains("response.status === 412")
                .doesNotContain("fetch('/api/relations', {\n"
                        + "            method: 'POST'")
                .doesNotContain("'/api/relations/' + id");
    }

    @Test
    void deleteResolvesTheDisplayedProjectionIdToStableIdentity()
            throws Exception {
        String adapter = Files.readString(RELATION_ADAPTER);

        int lookup = adapter.indexOf(
                "relationsById.get(String(id))");
        int source = adapter.indexOf(
                "relation.sourceCode", lookup);
        int type = adapter.indexOf(
                "relation.relationType", lookup);
        int target = adapter.indexOf(
                "relation.targetCode", lookup);

        assertThat(lookup).isGreaterThanOrEqualTo(0);
        assertThat(source).isGreaterThan(lookup);
        assertThat(type).isGreaterThan(source);
        assertThat(target).isGreaterThan(type);
    }
}
