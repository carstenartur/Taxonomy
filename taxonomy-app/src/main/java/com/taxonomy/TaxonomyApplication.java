package com.taxonomy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** Main Spring Boot application. */
@SpringBootApplication
@EntityScan(basePackages = {
        "com.taxonomy",
        "io.github.carstenartur.jgit.storage.hibernate.entity"
})
@EnableAsync
public class TaxonomyApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaxonomyApplication.class, args);
    }
}
