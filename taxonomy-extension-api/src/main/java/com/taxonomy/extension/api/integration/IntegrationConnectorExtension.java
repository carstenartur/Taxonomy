package com.taxonomy.extension.api.integration;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * STARTUP contribution to the host's reviewed exchange workflow (host SPI 1.3).
 * A profile version is a distinct registration. The host owns authorization,
 * canonical projection, persistence and exact plugin-artifact review binding.
 * Parsing and selection validation must not mutate canonical or remote state.
 */
public interface IntegrationConnectorExtension extends TaxonomyExtension, LifecycleIntegrationConnector {
    @Override default ExtensionKind kind() { return ExtensionKind.INTEGRATION_CONNECTOR; }
    @Override default String id() { return descriptor().id() + "@" + descriptor().version(); }
    @Override default String displayName() { return descriptor().title(); }
}
