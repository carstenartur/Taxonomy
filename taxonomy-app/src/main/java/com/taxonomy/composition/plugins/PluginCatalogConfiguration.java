package com.taxonomy.composition.plugins;

import com.taxonomy.extension.runtime.PluginCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Application-wide composition; feature-owned adapters keep their own component owners. */
@Configuration(proxyBeanMethods = false)
@Import(PluginRuntimeConfiguration.class)
public class PluginCatalogConfiguration {
    @Bean PluginCatalog pluginCatalog(PluginRuntimeConfiguration.Installation installation) {
        return installation.catalog();
    }
}
