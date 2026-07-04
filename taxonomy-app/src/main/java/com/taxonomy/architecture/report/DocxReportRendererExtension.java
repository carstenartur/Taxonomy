package com.taxonomy.architecture.report;

import com.taxonomy.architecture.service.ArchitectureReportService;
import org.springframework.stereotype.Component;

@Component
public class DocxReportRendererExtension implements ReportRendererExtension {

    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "docx",
            "DOCX",
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            true
    );

    private final ArchitectureReportService reportService;

    public DocxReportRendererExtension(ArchitectureReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public ReportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        return new ReportRenderResult(reportService.renderDocx(context.report()));
    }
}
