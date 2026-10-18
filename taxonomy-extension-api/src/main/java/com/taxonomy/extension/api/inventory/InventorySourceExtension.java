package com.taxonomy.extension.api.inventory;

import com.taxonomy.shared.extension.TaxonomyExtension;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;

/** STARTUP-only source contribution. The host owns authorization, leases and reviewed apply.
 * Neither loading the extension nor reading an observation may mutate architecture or remote state. */
public interface InventorySourceExtension extends TaxonomyExtension {
    @Override default ExtensionKind kind() { return ExtensionKind.INVENTORY_SOURCE; }
    Observation read(Request request, ReadAccess access);
}
