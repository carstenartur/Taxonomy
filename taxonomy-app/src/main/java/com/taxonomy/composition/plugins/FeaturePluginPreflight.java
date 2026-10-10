package com.taxonomy.composition.plugins;

import com.taxonomy.shared.features.FeatureAssembly;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/** Run before any bean, data source, migration, repository or HTTP listener is created. */
public final class FeaturePluginPreflight implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton("featureSet", FeatureAssembly.discover(context.getClassLoader()));
    }
}
