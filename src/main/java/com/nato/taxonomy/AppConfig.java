package com.nato.taxonomy;

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

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);  // 10 seconds
        factory.setReadTimeout(60_000);     // 60 seconds
        return new RestTemplate(factory);
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
