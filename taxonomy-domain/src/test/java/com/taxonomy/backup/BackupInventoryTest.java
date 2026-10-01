package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupInventoryTest {
    @Test void refusesFullCoverageForUnclassifiedPersistentData() {
        var inventory = new BackupInventory(List.of(new BackupInventory.Category("known", new BackupComponentId("workspace"),
                BackupStorageRule.PORTABLE_PRIMARY, "Contains saved editor content")));
        assertThrows(IllegalStateException.class, () -> inventory.requireClassified(Set.of("known", "new-persistent-type")));
        assertDoesNotThrow(() -> inventory.requireClassified(Set.of("known")));
    }
    @Test void refusesDuplicateCategoriesAndUnexplainedOmissions() {
        var category = new BackupInventory.Category("known", new BackupComponentId("workspace"), BackupStorageRule.TRANSIENT, "Runtime leases cannot be restored");
        assertThrows(IllegalArgumentException.class, () -> new BackupInventory(List.of(category, category)));
        assertThrows(IllegalArgumentException.class, () -> new BackupInventory.Category("unknown", new BackupComponentId("workspace"), BackupStorageRule.REBUILDABLE, ""));
    }
}
