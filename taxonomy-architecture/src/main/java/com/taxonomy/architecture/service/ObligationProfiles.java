package com.taxonomy.architecture.service;

import com.taxonomy.dto.ObligationProfile;
import java.util.List;
import java.util.Set;

/** Built-in authored profiles. Compatibility imposes no business obligations. */
public final class ObligationProfiles {
    public static final ObligationProfile COMPATIBILITY = new ObligationProfile("compatibility", "1.0.0",
            "Relation legality only; no business obligations are implied.", List.of());
    public static final ObligationProfile SERVICE_SUPPORT = new ObligationProfile("service-support", "1.0.0",
            "Opt-in business assumption: every Core Service in the selected model must support at least one Business Process or Business Role.",
            List.of(new ObligationProfile.Rule("core-service-support", Set.of("CR"), "SUPPORTS", Set.of("BP", "BR"),
                    1, null, ObligationProfile.Severity.WARNING, "Accepted outgoing relations and canonical element types at the selected revision")));
    private ObligationProfiles() {}
    public static List<ObligationProfile> all() { return List.of(COMPATIBILITY, SERVICE_SUPPORT); }
    public static ObligationProfile require(String id, String version) {
        return all().stream().filter(p -> p.id().equals(id) && p.version().equals(version)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown obligation profile or version"));
    }
}
