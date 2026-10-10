package com.taxonomy;

import com.taxonomy.setup.SetupCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** Main Spring Boot application. */
@SpringBootApplication
@org.springframework.context.annotation.ComponentScan(basePackages = "com.taxonomy", excludeFilters = {
        @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.CUSTOM,
                classes = org.springframework.boot.context.TypeExcludeFilter.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.CUSTOM,
                classes = org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "com\\.taxonomy\\.(templates|reporting|architecture|analysis|portfolio|interop)\\..*")
})
@EntityScan(basePackages = {
        "com.taxonomy",
        "io.github.carstenartur.jgit.storage.hibernate.entity"
})
@EnableAsync
public class TaxonomyApplication {
    /** Explicit feature opt-in; unrelated partial Spring contexts never start application features. */
    @org.springframework.context.annotation.Bean(name = "taxonomyFeatureHost")
    static Object featureHost() { return new Object(); }

    public static void main(String[] args) {
        com.taxonomy.shared.features.FeatureAssembly.discover(Thread.currentThread().getContextClassLoader());
        int setupExitCode = SetupCommand.execute(args, System.out);
        if (setupExitCode >= 0) {
            System.exit(setupExitCode);
            return;
        }
        SpringApplication.run(TaxonomyApplication.class, SetupCommand.applicationArguments(args));
    }
}
