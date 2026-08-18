package com.taxonomy.portfolio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate virtual-thread coordinator so waiting for persisted jobs never occupies worker capacity. */
@Configuration
public class CopilotAutomationExecutorConfiguration {

    @Bean(name = "copilotAutomationExecutor", destroyMethod = "close")
    ExecutorService copilotAutomationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
