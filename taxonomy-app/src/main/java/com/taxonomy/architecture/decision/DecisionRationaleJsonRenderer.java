package com.taxonomy.architecture.decision;

import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** JSON renderer extension for the format-neutral decision report model. */
@Component
@SuppressWarnings("serial")
public class DecisionRationaleJsonRenderer implements ReportRendererExtension {

    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "json", "JSON", "json", "application/json", false);

    private final ObjectMapper objectMapper;

    public DecisionRationaleJsonRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String reportTypeId() {
        return DecisionRationaleReportPlugin.REPORT_TYPE_ID;
    }

    @Override
    public Class<?> reportModelType() {
        return DecisionRationaleReport.class;
    }

    @Override
    public ReportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        try {
            DecisionRationaleReport report =
                    context.payloadAs(DecisionRationaleReport.class);
            return new ReportRenderResult(objectMapper.writeValueAsBytes(report));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not generate decision rationale JSON", exception);
        }
    }
}
