package com.taxonomy.architecture.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class JsonReportRendererExtension implements ReportRendererExtension {

    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "json",
            "JSON",
            "json",
            "application/json",
            false
    );

    private final ObjectMapper objectMapper;

    public JsonReportRendererExtension(ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.objectMapper = objectMapperProvider.getIfAvailable(
                () -> new ObjectMapper().findAndRegisterModules());
    }

    @Override
    public ReportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        try {
            return new ReportRenderResult(objectMapper.writeValueAsBytes(context.report()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to render JSON report", e);
        }
    }
}
