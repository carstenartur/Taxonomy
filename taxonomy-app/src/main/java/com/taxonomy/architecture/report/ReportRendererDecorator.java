package com.taxonomy.architecture.report;

import com.taxonomy.extension.api.report.ReportRendererExtension;

/**
 * Application-layer hook that can add infrastructure concerns around a renderer
 * without contaminating the Spring-free renderer extension API.
 */
public interface ReportRendererDecorator {

    boolean supports(ReportRendererExtension renderer);

    ReportRendererExtension decorate(ReportRendererExtension renderer);
}
