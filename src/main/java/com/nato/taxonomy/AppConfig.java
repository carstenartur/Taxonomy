package com.nato.taxonomy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(10);
    }
}
