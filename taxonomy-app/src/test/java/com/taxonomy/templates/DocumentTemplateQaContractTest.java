package com.taxonomy.templates;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateQaContractTest {

    @Test
    void directWordCapabilityIsRenderedAndDisabledByDefaultForKeycloak()
            throws Exception {
        ConcurrentModel model = new ConcurrentModel();
        DocumentTemplatePageController controller =
                new DocumentTemplatePageController();

        assertThat(controller.documentTemplates(model))
                .isEqualTo("document-templates");
        assertThat(model.getAttribute("directWordEnabled")).isEqualTo(true);

        assertThat(resource("/templates/document-templates.html"))
                .contains("data-direct-word-enabled=${directWordEnabled}")
                .contains("document.templates.direct-word.disabled");
        assertThat(resource("/application-keycloak.properties"))
                .contains("taxonomy.document-templates.direct-word-enabled="
                        + "${TAXONOMY_DIRECT_WORD_ENABLED:false}");
    }

    @Test
    void failedPostUploadRefreshIsNotOverwrittenByASuccessMessage()
            throws Exception {
        String workspace = resource(
                "/static/js/document-templates/document-templates.js");

        assertThat(workspace)
                .contains("const directWordEnabled =")
                .contains("return false;")
                .contains("const refreshed = await loadTemplates();")
                .contains("if (refreshed) {")
                .contains("wordAuthenticationUnavailable")
                .doesNotContain("await loadTemplates();\n            showSuccess(");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = DocumentTemplateQaContractTest.class
                .getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
