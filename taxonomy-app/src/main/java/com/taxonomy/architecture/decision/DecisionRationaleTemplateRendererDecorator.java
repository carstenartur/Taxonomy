package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.report.ReportRendererDecorator;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Makes the decision-rationale DOCX renderer template-backed when registered.
 */
@Component
public final class DecisionRationaleTemplateRendererDecorator
        implements ReportRendererDecorator {

    private final DecisionRationaleTemplateRenderer templateRenderer;

    public DecisionRationaleTemplateRendererDecorator(
            DecisionRationaleTemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(
                templateRenderer, "templateRenderer");
    }

    @Override
    public boolean supports(ReportRendererExtension renderer) {
        return renderer instanceof DecisionRationaleDocxRenderer;
    }

    @Override
    public ReportRendererExtension decorate(ReportRendererExtension renderer) {
        if (!(renderer instanceof DecisionRationaleDocxRenderer delegate)) {
            throw new IllegalArgumentException(
                    "Decision-rationale template decorator received an unsupported renderer");
        }
        return new TemplateBackedExtension(delegate, templateRenderer);
    }

    private record TemplateBackedExtension(
            DecisionRationaleDocxRenderer delegate,
            DecisionRationaleTemplateRenderer templateRenderer)
            implements ReportRendererExtension {

        private TemplateBackedExtension {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(templateRenderer, "templateRenderer");
        }

        @Override
        public String reportTypeId() {
            return delegate.reportTypeId();
        }

        @Override
        public Class<?> reportModelType() {
            return delegate.reportModelType();
        }

        @Override
        public ReportFormatDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public ReportRenderResult render(ReportRenderContext context) {
            DecisionRationaleReport report =
                    context.payloadAs(DecisionRationaleReport.class);
            try {
                return templateRenderer.renderArtifact(delegate, report);
            } catch (DecisionReportTemplateUnavailableException exception) {
                throw exception;
            } catch (IllegalStateException exception) {
                // The template renderer normalizes missing, invalid, and failed template
                // materialization states to IllegalStateException. Reclassify that
                // operational dependency failure at the HTTP boundary as 503.
                throw new DecisionReportTemplateUnavailableException(
                        exception.getMessage(), exception);
            }
        }
    }
}
