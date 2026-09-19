package com.taxonomy.portfolio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded coordinator capacity for persisted Copilot operations.
 *
 * <p>Workers remain virtual threads because they mostly wait for durable analysis
 * jobs, but both concurrently active coordinators and queued operations are bounded
 * so a large import cannot create an unbounded stream of database polling tasks.</p>
 */
@Configuration
public class CopilotAutomationExecutorConfiguration {

    private static final int MAX_CONFIGURED_CONCURRENCY = 64;
    private static final int MAX_CONFIGURED_QUEUE = 10_000;

    @Bean(name = "copilotAutomationExecutor", destroyMethod = "shutdown")
    ExecutorService copilotAutomationExecutor(
            @Value("${taxonomy.ai.coordinator.max-concurrent-operations:4}")
            int maximumConcurrentOperations,
            @Value("${taxonomy.ai.coordinator.queue-capacity:100}")
            int queueCapacity) {
        int concurrency = bounded(
                maximumConcurrentOperations,
                1,
                MAX_CONFIGURED_CONCURRENCY,
                "taxonomy.ai.coordinator.max-concurrent-operations");
        int backlog = bounded(
                queueCapacity,
                1,
                MAX_CONFIGURED_QUEUE,
                "taxonomy.ai.coordinator.queue-capacity");
        ThreadFactory workers = Thread.ofVirtual()
                .name("taxonomy-copilot-coordinator-", 0)
                .factory();
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(backlog),
                workers,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static int bounded(int value, int minimum, int maximum, String property) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    property + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
