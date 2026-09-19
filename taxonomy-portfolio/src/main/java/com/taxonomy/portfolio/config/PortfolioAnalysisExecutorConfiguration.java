package com.taxonomy.portfolio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded, lifecycle-managed executor for persisted portfolio analysis jobs. */
@Configuration(proxyBeanMethods = false)
public class PortfolioAnalysisExecutorConfiguration {

    @Bean(name = "portfolioAnalysisExecutor")
    public AsyncTaskExecutor portfolioAnalysisExecutor(
            @Value("${taxonomy.portfolio.analysis-worker-concurrency:1}") int configuredConcurrency,
            @Value("${taxonomy.portfolio.analysis-worker-queue-capacity:100}") int configuredQueueCapacity,
            @Value("${taxonomy.portfolio.analysis-worker-shutdown-seconds:30}") int configuredShutdownSeconds) {
        int concurrency = Math.max(1, configuredConcurrency);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("portfolio-analysis-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(0, configuredQueueCapacity));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, configuredShutdownSeconds));
        return executor;
    }
}
