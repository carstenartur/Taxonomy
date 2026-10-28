package com.taxonomy.plugins.alfabet;
import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.taxonomy.extension.api.inventory.InventorySourceExtension;
import org.junit.jupiter.api.Test;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;
class AlfabetPluginTest {
    @Test void discoversTheSourceWithoutConfigurationOrCredentials() {
        var plugins = ServiceLoader.load(TaxonomyPlugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(1, plugins.size());
        var extensions = plugins.getFirst().extensions();
        assertEquals(1, extensions.size());
        assertInstanceOf(InventorySourceExtension.class, extensions.getFirst());
        assertEquals("alfabet", extensions.getFirst().id());
    }
}
