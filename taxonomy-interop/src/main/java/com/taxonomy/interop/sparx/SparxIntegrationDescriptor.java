package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.util.Set;

/** Capabilities describe implemented behavior; unverified PCS writes are deliberately absent. */
public final class SparxIntegrationDescriptor {
    private SparxIntegrationDescriptor() {}
    public static IntegrationDescriptor xmi() {
        return new IntegrationDescriptor(SparxMappingProfile.PROFILE, SparxMappingProfile.VERSION,
                "Sparx EA XMI 2.1 (experimental)",
                Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT, Capability.ARCHITECTURE_MODEL),
                Set.of("application/xmi+xml", "application/xml"));
    }
}
