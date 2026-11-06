package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class SparxPluginTest {
    @Test void discoversTheOfflineConnectorThroughThePublicPluginBoundary() {
        var plugins = ServiceLoader.load(TaxonomyPlugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(1, plugins.size(), "An installed JAR must expose its entry point without opening a database");
        var extensions = plugins.getFirst().extensions();
        assertEquals(1, extensions.size());
        var connector = assertInstanceOf(LifecycleIntegrationConnector.class, extensions.getFirst());
        assertEquals("sparx-qea", connector.descriptor().id());
        assertEquals("1", connector.descriptor().version());
        assertEquals("sparx-qea@1", extensions.getFirst().id());
    }
}
