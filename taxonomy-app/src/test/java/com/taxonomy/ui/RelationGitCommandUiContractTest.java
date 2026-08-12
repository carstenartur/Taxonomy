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
                .doesNotContain("'/api/relations/' + id")
                .doesNotContain("relationsById");
    }

    @Test
    void deleteUsesIdentityFromTheSameVisibleTableRow() throws Exception {
        String adapter = Files.readString(RELATION_ADAPTER);

        int deleteStart = adapter.indexOf(
                "    function deleteRelation(button) {");
        int identityUse = adapter.indexOf(
                "var relation = relationIdentity(button);", deleteStart);
        int deleteCommand = adapter.indexOf(
                "return fetch(commandUrl(", identityUse);
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

        assertThat(deleteStart).isGreaterThanOrEqualTo(0);
        assertThat(identityUse).isGreaterThan(deleteStart);
        assertThat(deleteCommand).isGreaterThan(identityUse);
        assertThat(rowFunction).isGreaterThan(deleteCommand);
        assertThat(rowLookup).isGreaterThan(rowFunction);
        assertThat(source).isGreaterThan(rowLookup);
        assertThat(target).isGreaterThan(source);
        assertThat(type).isGreaterThan(target);
    }
}
