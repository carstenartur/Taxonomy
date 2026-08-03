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
            @Value("${taxonomy.portfolio.analysis-worker-core-pool-size:1}") int configuredCorePoolSize,
            @Value("${taxonomy.portfolio.analysis-worker-max-pool-size:4}") int configuredMaxPoolSize,
            @Value("${taxonomy.portfolio.analysis-worker-queue-capacity:100}") int configuredQueueCapacity,
            @Value("${taxonomy.portfolio.analysis-worker-shutdown-seconds:30}") int configuredShutdownSeconds) {
        int corePoolSize = Math.max(1, configuredCorePoolSize);
        int maxPoolSize = Math.max(corePoolSize, configuredMaxPoolSize);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("portfolio-analysis-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(Math.max(0, configuredQueueCapacity));
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, configuredShutdownSeconds));
        executor.initialize();
        return executor;
    }
}
