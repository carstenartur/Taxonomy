package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SparxIdentityConflictTest {
    @Test void anotherExternalGuidCannotClaimAnActiveOrRemovedHistoricalUuid() {
        var diff = new IntegrationDiff(new IntegrationJson(JsonMapper.builder().build()));
        var before = new Artifact("guid-a", ArtifactKind.ELEMENT, "Component", "A", "", Map.of(), Map.of("internalIdentity", "uuid-a"));
        var after = new Artifact("guid-b", ArtifactKind.ELEMENT, "Component", "B", "", Map.of(), Map.of("internalIdentity", "uuid-a"));
        var document = new ExchangeDocument("sparx-xmi-2.1", "1", "v2", true, "", List.of(after), List.of(), List.of(), Map.of(), List.of());
        for (boolean removed : List.of(false, true)) {
            var mapping = new Identity("ELEMENT:guid-a", "arch-a", null, "v1", "fp", before, before, UUID.randomUUID(), removed);
            var change = diff.compare(document, AuthorityMode.BIDIRECTIONAL, List.of(mapping), removed ? Map.of() : Map.of("ELEMENT:guid-a", before)).stream()
                    .filter(c -> c.externalId().equals("ELEMENT:guid-b")).findFirst().orElseThrow();
            assertEquals(ChangeKind.CONFLICT, change.kind());
            assertTrue(change.conflicts().contains("INTERNAL_IDENTITY_REUSED"));
        }
    }

    @Test void rebindingTheSameExternalGuidToAnotherInternalUuidIsAConflict() {
        var diff = new IntegrationDiff(new IntegrationJson(JsonMapper.builder().build()));
        var before = new Artifact("guid", ArtifactKind.ELEMENT, "Class", "Name", "", Map.of(), Map.of("internalIdentity", "uuid-a"));
        var after = new Artifact("guid", ArtifactKind.ELEMENT, "Class", "Name", "", Map.of(), Map.of("internalIdentity", "uuid-b"));
        var document = new ExchangeDocument("sparx-xmi-2.1", "1", "v2", true, "", List.of(after), List.of(), List.of(), Map.of(), List.of());
        for (boolean removed : List.of(false, true)) {
            var mapping = new Identity("ELEMENT:guid", "arch-a", null, "v1", "fp", before, before, UUID.randomUUID(), removed);
            var change = diff.compare(document, AuthorityMode.BIDIRECTIONAL, List.of(mapping), removed ? Map.of() : Map.of("ELEMENT:guid", before)).stream()
                    .filter(c -> c.externalId().equals("ELEMENT:guid")).findFirst().orElseThrow();
            assertEquals(ChangeKind.CONFLICT, change.kind());
            assertTrue(change.conflicts().contains("INTERNAL_IDENTITY_REUSED"));
        }
    }
}
