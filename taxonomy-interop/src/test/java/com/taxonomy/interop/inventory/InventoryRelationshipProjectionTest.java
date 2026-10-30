package com.taxonomy.interop.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.interop.IntegrationJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRelationshipProjectionTest {
    private final IntegrationJson json=new IntegrationJson(JsonMapper.builder().build());
    private final ExternalKey source=new ExternalKey("instance","Application","one"),target=new ExternalKey("instance","Application","two");
    @Test void onlyConfiguredExactDirectedMeaningProducesAnAssertionWithRealEndpoints() {
        var rule=new InventorySourceProfiles.RelationshipProjection("USES",RelationshipKind.DEPENDENCY,"runtime",AssertionKind.OBSERVED);
        var relation=new ObservedRelation("edge",null,source,target,"ApplicationRelation","USES",Map.of());
        var projection=new InventoryRelationshipProjection(json);
        var assertions=projection.project(List.of(relation),Set.of(source,target),List.of(rule),UUID.fromString("00000000-0000-0000-0000-000000000001"),Instant.EPOCH,"org");
        assertEquals(1,assertions.size());var assertion=assertions.getFirst();
        assertEquals("external:"+json.fingerprint(source),assertion.candidateId());
        assertEquals(new Relationship("external:"+json.fingerprint(target),RelationshipKind.DEPENDENCY,"runtime"),assertion.relationship());
        assertTrue(assertion.positive());assertEquals(AssertionKind.OBSERVED,assertion.kind());
        assertTrue(assertion.citation().contains("edge"));
    }
    @Test void unknownMeaningOrMissingEndpointCannotFabricateARelationship() {
        var rule=new InventorySourceProfiles.RelationshipProjection("USES",RelationshipKind.DEPENDENCY,"runtime",AssertionKind.OBSERVED);
        var relation=new ObservedRelation("edge",null,source,target,"ApplicationRelation","UNRECOGNIZED",Map.of());
        var projection=new InventoryRelationshipProjection(json);
        assertTrue(projection.project(List.of(relation),Set.of(source,target),List.of(rule),UUID.randomUUID(),Instant.EPOCH,"org").isEmpty());
        var recognized=new ObservedRelation("edge",null,source,target,"ApplicationRelation","USES",Map.of());
        assertTrue(projection.project(List.of(recognized),Set.of(source),List.of(rule),UUID.randomUUID(),Instant.EPOCH,"org").isEmpty());
    }
}
