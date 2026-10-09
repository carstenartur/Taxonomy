package com.taxonomy.composition.backup;

import com.taxonomy.backup.runtime.BackupFeaturePrerequisites;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.shared.features.FeatureAssembly;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bind backup admission to the actual preflight and initialized catalog, never a rehashed operator directory. */
@Configuration(proxyBeanMethods = false)
public class BackupPluginPrerequisitesConfiguration {
    @Bean BackupFeaturePrerequisites backupFeaturePrerequisites(FeatureAssembly.FeatureSet features, ExtensionCatalog catalog) {
        return new BackupFeaturePrerequisites(features, catalog.snapshot());
    }
}
