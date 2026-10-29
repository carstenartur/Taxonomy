package com.taxonomy.interop.inventory;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.interop.IntegrationJson;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryMappingTest {
    @Test void explicitMappingRetainsBothIdentitiesAndCannotCrossScopeOrChangeReusedId() throws Exception {
        try(var fixture=new EditorPersistenceFixture("jdbc:hsqldb:mem:mapping-"+UUID.randomUUID(),InventoryMappingEntity.class)) {
            var store=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),new IntegrationJson(JsonMapper.builder().build()));
            var scope=new Scope("repo","scope","draft","alice");
            var mapping=new Mapping(UUID.randomUUID(),"external:object",9L,"target-product-v1","source-profile","revision","source-object-v1","Verified identity by documented reference","alice",Instant.now());
            store.map(scope,mapping);store.map(scope,mapping);
            assertEquals(List.of(mapping),store.mappings(scope,"external:object"));
            assertTrue(store.mappings(new Scope("other","scope","draft","alice"),"external:object").isEmpty());
            assertThrows(RuntimeException.class,()->store.map(scope,new Mapping(mapping.id(),mapping.candidateId(),10L,"different",mapping.sourceProfile(),mapping.sourceRevision(),mapping.sourceIdentityFingerprint(),mapping.rationale(),mapping.actor(),mapping.mappedAt())));
        }
    }
}
