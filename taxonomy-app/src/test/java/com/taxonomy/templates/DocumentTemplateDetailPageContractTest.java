package com.taxonomy.templates;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateDetailPageContractTest {

    @Test
    void detailPageEscapesPartContentAndProtectsRestoreWithTheCurrentEtag() throws Exception {
        String page = resource("/templates/document-template-detail.html");
        assertThat(page)
                .contains("th:text=\"${part.textContent}\"")
                .doesNotContain("th:utext=\"${part.textContent}\"")
                .contains("/restore(id=${template.templateId},revision=${revision.commitId},expectedHead=${template.headCommit})")
                .doesNotContain("window.confirm(")
                .contains("/test.docx")
                .contains("partRevision=${inspectRevision}");
    }

    @Test
    void confirmationPreservesOriginalRevisionsAndRequiresAnExplicitNonStaleWrite() throws Exception {
        String page = resource("/templates/document-template-restore.html");
        assertThat(page)
                .contains("<form th:unless=\"${restoreConflict}\" id=\"restoreConfirmationForm\" method=\"post\"")
                .contains("<input type=\"hidden\" name=\"revision\" th:value=\"${restoreRevision}\"/>")
                .contains("<input type=\"hidden\" name=\"expectedHead\" th:value=\"${restoreExpectedHead}\"/>")
                .doesNotContain("name=\"expectedHead\" th:value=\"${template.headCommit}\"")
                .contains("name=\"confirmed\" value=\"true\" required")
                .contains("th:text=\"#{document.template.restore.confirm(${template.displayName},${restoreRevision})}\"")
                .contains("th:if=\"${restoreConflict}\" id=\"restoreConflict\"")
                .contains("id=\"restoreReviewCurrent\"");
    }

    @Test
    void templateListLinksToTheAdvancedVersionWorkspace() throws Exception {
        String script = resource(
                "/static/js/document-templates/document-templates.js");
        assertThat(script)
                .contains("manage: 'Compare and restore'")
                .contains("manage.href = detailUrl(template.templateId)")
                .contains("historyBody.appendChild(manage)");
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = DocumentTemplateDetailPageContractTest.class
                .getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
