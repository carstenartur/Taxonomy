package com.taxonomy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    /**
     * Exposes the HTTP request factory as a bean so that {@code LlmService} can update
     * the read timeout at runtime when the {@code llm.timeout.seconds} preference changes.
     *
     * <p>The initial value is taken from the {@code taxonomy.llm.timeout-seconds} property
     * (default: 30 seconds). The value may be overridden at runtime via the Preferences API.
     */
    @Bean
    public SimpleClientHttpRequestFactory llmRequestFactory(
            @Value("${taxonomy.llm.timeout-seconds:30}") int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return factory;
    }

    @Bean
    public RestTemplate restTemplate(SimpleClientHttpRequestFactory llmRequestFactory) {
        return new RestTemplate(llmRequestFactory);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService analysisExecutor() {
        // Scale to available CPUs; cap at 4 to avoid over-threading on constrained hosts
        // (e.g. Render Free Tier has 1 CPU — uses 2 threads, which is still useful for I/O waits)
        int poolSize = Math.min(Runtime.getRuntime().availableProcessors() + 1, 4);
        return Executors.newFixedThreadPool(poolSize);
    }

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("taxonomy-async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
