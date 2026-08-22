package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DefaultDocumentTemplateBootstrapIntegrationTest {

    @Autowired
    private DocumentTemplateService templates;

    @Autowired
    private DefaultDocumentTemplateBootstrap bootstrap;

    @Test
    void requiredTemplateExistsImmediatelyAndRepeatedBootstrapDoesNotOverwriteIt()
            throws Exception {
        TemplateFile first = templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID);
        String firstCommit = first.commitId();
        int historySize = templates.history(
                DecisionRationaleTemplateContract.TEMPLATE_ID).size();
        assertThat(historySize).isPositive();

        bootstrap.seedIfMissing();

        TemplateFile second = templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID);
        assertThat(second.commitId()).isEqualTo(firstCommit);
        assertThat(second.manifest().updatedBy()).isEqualTo("taxonomy-bootstrap");
        assertThat(templates.history(DecisionRationaleTemplateContract.TEMPLATE_ID))
                .hasSize(historySize);
    }
}
