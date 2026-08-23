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
                .contains("name=\"expectedHead\"")
                .contains("th:value=\"${template.headCommit}\"")
                .contains("/test.docx")
                .contains("partRevision=${inspectRevision}");
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
