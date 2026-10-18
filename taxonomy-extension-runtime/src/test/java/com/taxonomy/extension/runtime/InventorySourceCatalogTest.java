package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.extension.api.inventory.InventorySourceExtension;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.shared.extension.ExtensionKind;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class InventorySourceCatalogTest {
    private static PluginDescriptor descriptor(String id, PluginMode mode, String range) {
        return new PluginDescriptor(new PluginIdentity(id, "1.0.0", "a".repeat(64)), range,
                List.of(), Set.of("inventory.read"), mode);
    }
    private static InventorySourceExtension source() {
        return new InventorySourceExtension() {
            private static final long serialVersionUID = 1L;
            public String id() { return "reference-inventory"; }
            public String displayName() { return "Reference inventory"; }
            public Observation read(Request request, ReadAccess access) { throw new AssertionError("Registration must not read"); }
        };
    }
    @Test void startupSourcesUseTheExistingExactArtifactLease() {
        var catalog = new PluginCatalog();
        var descriptor = descriptor("example.inventory", PluginMode.STARTUP, ">=1.1.0 & <2.0.0");
        var source = source();
        catalog.publish(descriptor, List.of(source));
        try (var lease = catalog.acquire(new ExtensionKey(ExtensionKind.INVENTORY_SOURCE, source.id()), InventorySourceExtension.class)) {
            assertSame(source, lease.extension()); assertEquals(descriptor.identity(), lease.plugin());
            assertEquals(1, catalog.activeLeases(descriptor.identity()));
        }
        assertEquals(0, catalog.activeLeases(descriptor.identity()));
    }
    @Test void aReadSourceCannotPretendToBeDynamicallyUnloadable() {
        var catalog = new PluginCatalog();
        assertThrows(IllegalArgumentException.class, () -> catalog.publish(
                descriptor("example.inventory", PluginMode.DYNAMIC, ">=1.1.0 & <2.0.0"), List.of(source())));
        assertTrue(catalog.snapshot().extensions().isEmpty());
    }
    @Test void duplicateSourceIdsLeaveThePublishedCatalogUnchanged() {
        var catalog = new PluginCatalog();
        catalog.publish(descriptor("example.first", PluginMode.STARTUP, ">=1.1.0 & <2.0.0"), List.of(source()));
        var before = catalog.snapshot();
        assertThrows(IllegalStateException.class, () -> catalog.publish(
                descriptor("example.second", PluginMode.STARTUP, ">=1.1.0 & <2.0.0"), List.of(source())));
        assertSame(before, catalog.snapshot());
    }
    @Test void aPluginLimitedToTheOldMinorApiIsRejected() {
        var catalog = new PluginCatalog();
        assertThrows(IllegalArgumentException.class, () -> catalog.publish(
                descriptor("example.old", PluginMode.STARTUP, ">=1.0.0 & <1.1.0"), List.of(source())));
        assertTrue(catalog.snapshot().extensions().isEmpty());
    }
}
