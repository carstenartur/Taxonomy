package com.taxonomy.reporting.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Explicit feature assembly; the runtime never scans architecture or workspace services. */
@AutoConfiguration
@ComponentScan(basePackages = {"com.taxonomy.reporting.render", "com.taxonomy.reporting.templates"})
public class ReportingAutoConfiguration {
}
