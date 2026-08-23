package com.taxonomy.architecture.decision;

import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.templates.DecisionRationaleTemplateContract;
import com.taxonomy.templates.DocumentTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionReportTemplateUnavailableExceptionTest {

    @Mock
    private DocumentTemplateService templates;

    @Test
    void exceptionIsMappedToServiceUnavailable() {
        ResponseStatus status = DecisionReportTemplateUnavailableException.class
                .getAnnotation(ResponseStatus.class);

        assertThat(status).isNotNull();
        assertThat(status.code()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void decoratedRendererClassifiesMissingTemplateAsOperationalDependencyFailure()
            throws Exception {
        when(templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenThrow(new IOException("stored template is unavailable"));
        DecisionRationaleTemplateRenderer templateRenderer =
                new DecisionRationaleTemplateRenderer(
                        templates,
                        new DecisionRationaleTemplateContract());
        DecisionRationaleDocxRenderer delegate =
                new DecisionRationaleDocxRenderer(
                        new DecisionChapterDiagramRenderer());
        ReportRendererExtension decorated =
                new DecisionRationaleTemplateRendererDecorator(templateRenderer)
                        .decorate(delegate);
        DecisionRationaleReport report = mock(DecisionRationaleReport.class);

        assertThatThrownBy(() -> decorated.render(
                ReportRenderContext.ofPayload(report)))
                .isInstanceOf(DecisionReportTemplateUnavailableException.class)
                .hasMessageContaining("decision-rationale-report")
                .hasRootCauseMessage("stored template is unavailable");
    }
}
