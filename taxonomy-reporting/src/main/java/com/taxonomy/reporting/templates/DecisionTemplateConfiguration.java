package com.taxonomy.reporting.templates;

import com.taxonomy.reporting.render.decision.DecisionRationaleTemplatePreviewService;
import com.taxonomy.templates.api.TemplateContribution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** The reporting feature owns its default template and optional preview. */
@Configuration(proxyBeanMethods = false)
public class DecisionTemplateConfiguration {
    @Bean
    TemplateContribution decisionRationaleTemplateContribution(DecisionRationaleTemplateContract contract,
            ObjectProvider<DecisionRationaleTemplatePreviewService> previews) {
        return new TemplateContribution(DecisionRationaleTemplateContract.TEMPLATE_ID,
                DecisionRationaleTemplateContract.DISPLAY_NAME,
                () -> new ClassPathResource(DecisionRationaleTemplateContract.DEFAULT_RESOURCE).getInputStream(),
                contract, true, () -> previews.getObject().renderPreview(), "decision-rationale-template-test.docx");
    }
}
