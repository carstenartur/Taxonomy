package com.taxonomy.composition.analysis;

import com.taxonomy.preferences.storage.PromptOverrideStorageBeans;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Installs the preferences-owned prompt store only when the analysis feature is present. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFeature("analysis")
@Import(PromptOverrideStorageBeans.class)
public class PromptTemplateStorageConfiguration {
}
