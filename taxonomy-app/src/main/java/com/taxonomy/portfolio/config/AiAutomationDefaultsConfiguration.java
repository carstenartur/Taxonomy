package com.taxonomy.portfolio.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** Loads documented Copilot/Autopilot defaults while preserving environment overrides. */
@Configuration
@PropertySource("classpath:ai-automation-defaults.properties")
public class AiAutomationDefaultsConfiguration {
}
