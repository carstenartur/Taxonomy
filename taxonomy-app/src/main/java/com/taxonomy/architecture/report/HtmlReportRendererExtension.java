package com.taxonomy.architecture.report;

import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class HtmlReportRendererExtension implements ReportRendererExtension {

    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "html", "HTML", "html", "text/html; charset=UTF-8", false);

    private final ArchitectureReportService reportService;

    public HtmlReportRendererExtension(ArchitectureReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public ReportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        String html = reportService.renderHtml(context.report());
        return new ReportRenderResult(html.getBytes(StandardCharsets.UTF_8));
    }
}
