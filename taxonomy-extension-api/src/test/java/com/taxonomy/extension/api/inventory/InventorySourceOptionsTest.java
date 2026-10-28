package com.taxonomy.extension.api.inventory;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventorySourceOptionsTest {
    @Test void oldConstructorAndCredentialOnlyGrantRemainUsable() {
        Request request = legacy();
        assertTrue(request.sourceOptions().isEmpty());
        ReadAccess access = () -> "bound-secret".toCharArray();
        assertNull(access.principal());
        assertArrayEquals("bound-secret".toCharArray(), access.credential());
    }

    @Test void callerCannotChangeTheConfigurationRetainedWithAnObservation() {
        var options = new HashMap<>(Map.of("currentProfile", "Reader"));
        Request request = configured(options);
        options.put("currentProfile", "Administrator");
        assertEquals("Reader", request.sourceOptions().get("currentProfile"));
        assertThrows(UnsupportedOperationException.class, () -> request.sourceOptions().clear());
    }

    @Test void unboundedOrControlBearingConfigurationIsRejected() {
        for (var options : List.of(Map.of("", "Reader"), Map.of("profile", "a\nb"),
                Map.of("profile", "x".repeat(257)))) {
            assertThrows(IllegalArgumentException.class, () -> configured(options));
        }
        var options = new HashMap<String,String>();
        for (int i = 0; i < 129; i++) options.put("field" + i, "value");
        assertThrows(IllegalArgumentException.class, () -> configured(options));
    }

    private static Request configured(Map<String,String> options) {
        Request r = legacy();
        return new Request(r.context(), r.instanceId(), r.configurationRevision(), r.origin(),
                r.projections(), r.selectedIds(), r.readUsageRelations(), r.limits(), options);
    }
    private static Request legacy() {
        var context = new IntegrationContext(UUID.randomUUID(), AuthorityMode.LINK_ONLY,
                new ExternalScope("fixture", "instance", null),
                new InternalState("repository", "scope", "main", null, 0, null, null), "actor", "fixture-read", "1");
        return new Request(context, "instance", "profile-1", URI.create("https://example.invalid/"),
                List.of(new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of())),
                Set.of(), false, Limits.defaults());
    }
}
