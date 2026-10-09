package com.taxonomy.reporting.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Import;
import com.taxonomy.templates.api.DocumentTemplates;

/** Explicit feature assembly. Import exact owned components; never trigger a scan in a partial context. */
@AutoConfiguration(afterName = "com.taxonomy.templates.config.TemplatesFeatureAutoConfiguration")
@ConditionalOnBean(DocumentTemplates.class)
@Import({
        com.taxonomy.reporting.render.decision.DecisionChapterDiagramRenderer.class,
        com.taxonomy.reporting.render.decision.DecisionRationaleDocxRenderer.class,
        com.taxonomy.reporting.render.decision.DecisionRationaleHtmlRenderer.class,
        com.taxonomy.reporting.render.decision.DecisionRationaleJsonRenderer.class,
        com.taxonomy.reporting.render.decision.DecisionRationaleTemplatePreviewService.class,
        com.taxonomy.reporting.render.decision.DecisionRationaleTemplateRenderer.class,
        com.taxonomy.reporting.render.decision.DecisionRationaleTemplateRendererDecorator.class,
        com.taxonomy.reporting.render.decision.DecisionReportTemplateExceptionHandler.class,
        com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer.class,
        com.taxonomy.reporting.render.document.ArchitectureReportTextRenderer.class,
        com.taxonomy.reporting.render.document.DocxReportRendererExtension.class,
        com.taxonomy.reporting.render.document.HtmlReportRendererExtension.class,
        com.taxonomy.reporting.render.document.JsonReportRendererExtension.class,
        com.taxonomy.reporting.render.document.MarkdownReportRendererExtension.class,
        com.taxonomy.reporting.render.document.ReformulationReportDocxRenderer.class,
        com.taxonomy.reporting.render.document.ReportRendererRegistry.class,
        com.taxonomy.reporting.templates.DecisionRationaleTemplateContract.class,
        com.taxonomy.reporting.templates.DecisionRationaleTemplateHealthIndicator.class,
        com.taxonomy.reporting.templates.DecisionTemplateConfiguration.class
})
public class ReportingAutoConfiguration {
}
