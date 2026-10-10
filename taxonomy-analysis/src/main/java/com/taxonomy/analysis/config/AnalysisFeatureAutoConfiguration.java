package com.taxonomy.analysis.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Startup-only owned components; absent JAR means no feature registration. */
@AutoConfiguration
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(name = "taxonomyFeatureHost")
@Import(AnalysisFeatureAutoConfiguration.OwnedComponents.class)
public class AnalysisFeatureAutoConfiguration {

    /** Registration happens only after the host-marker condition, preserving each component's own conditions and bean name. */
    static final class OwnedComponents implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware, BeanFactoryAware {
        private Environment environment;
        private ResourceLoader resourceLoader;
        private BeanFactory beanFactory;
        @Override public void setBeanFactory(BeanFactory beanFactory) { this.beanFactory = beanFactory; }
        @Override public void setEnvironment(Environment environment) { this.environment = environment; }
        @Override public void setResourceLoader(ResourceLoader resourceLoader) { this.resourceLoader = resourceLoader; }
        @Override public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            var scanner = new ClassPathBeanDefinitionScanner(registry);
            scanner.setEnvironment(environment);
            scanner.setResourceLoader(resourceLoader);
            // Preserve the host's Boot test/slice exclusions in this feature-owned scan.
            var typeExcludeFilter = new TypeExcludeFilter();
            typeExcludeFilter.setBeanFactory(beanFactory);
            scanner.addExcludeFilter(typeExcludeFilter);
            scanner.addExcludeFilter(new AnnotationTypeFilter(AutoConfiguration.class));
            scanner.scan("com.taxonomy.analysis");
        }
    }
}
