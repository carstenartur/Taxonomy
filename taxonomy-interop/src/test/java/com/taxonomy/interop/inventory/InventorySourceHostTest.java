package com.taxonomy.interop.inventory;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.interop.IntegrationJson;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InventorySourceHostTest {
    @Test void configuredRawRelationsReachTheFrozenCandidateWithExactDirectionAndUnknownDates() throws Exception {
        var json=new IntegrationJson(JsonMapper.builder().build());var profiles=new InventorySourceProfiles();
        var type=new com.taxonomy.extension.api.inventory.InventoryReadContracts.TypeProjection("Application",com.taxonomy.extension.api.inventory.InventoryReadContracts.ObjectKind.APPLICATION,null,Map.of(),Map.of());
        var rule=new InventorySourceProfiles.RelationshipProjection("USES",RelationshipKind.DEPENDENCY,"runtime",com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind.OBSERVED);
        profiles.setProfiles(Map.of("approved",new InventorySourceProfiles.Profile("v1","source","instance",java.net.URI.create("https://inventory.example"),"repo-a","north",Set.of("alice"),Set.of("one","two"),List.of(type),true,com.taxonomy.extension.api.inventory.InventoryReadContracts.Limits.defaults(),Map.of(),"SOURCE_SECRET",null,false,List.of(),List.of(rule))));
        var catalog=mock(ExtensionCatalog.class);var journal=mock(InventoryJournal.class);
        @SuppressWarnings("unchecked") var lease=(com.taxonomy.extension.api.plugin.ExtensionLease<com.taxonomy.extension.api.inventory.InventorySourceExtension>)mock(com.taxonomy.extension.api.plugin.ExtensionLease.class);
        var extension=mock(com.taxonomy.extension.api.inventory.InventorySourceExtension.class);when(lease.extension()).thenReturn(extension);when(lease.plugin()).thenReturn(new com.taxonomy.extension.api.plugin.PluginIdentity("fixture","1","0".repeat(64)));when(catalog.acquire(any(),eq(com.taxonomy.extension.api.inventory.InventorySourceExtension.class))).thenReturn(lease);
        var source=new com.taxonomy.extension.api.inventory.InventoryReadContracts.ExternalKey("instance","Application","one");var target=new com.taxonomy.extension.api.inventory.InventoryReadContracts.ExternalKey("instance","Application","two");
        when(extension.read(any(),any())).thenAnswer(call->{
            var objects=List.of(source,target).stream().map(key->new com.taxonomy.extension.api.inventory.InventoryReadContracts.ObservedObject(key,"Application",com.taxonomy.extension.api.inventory.InventoryReadContracts.Field.missing(com.taxonomy.extension.api.inventory.InventoryReadContracts.State.ABSENT,"subtype"),com.taxonomy.extension.api.inventory.InventoryReadContracts.ObjectKind.APPLICATION,"v1",Map.of(),com.taxonomy.extension.api.inventory.InventoryReadContracts.State.ABSENT,List.of())).toList();
            var edge=new com.taxonomy.extension.api.inventory.InventoryReadContracts.ObservedRelation("edge",null,source,target,"Relation","USES",Map.of());
            return new com.taxonomy.extension.api.inventory.InventoryReadContracts.Observation(call.getArgument(0),Instant.EPOCH,Instant.EPOCH,com.taxonomy.extension.api.inventory.InventoryReadContracts.Completeness.SELECTED_SCOPE_READ_NON_ATOMIC,2,10,objects,List.of(edge),Set.of());
        });
        var demand=new Demand("repo-a","scope-a","draft",1L,2L,3L,"snapshot","catalog","v1",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.now(),null,"north",null));
        var read=new InventorySourceHost(profiles,catalog,journal,json,new MockEnvironment()).read(new Scope("repo-a","scope-a","draft","alice"),demand,List.of("approved"));
        var first=read.candidates().stream().filter(c->c.id().equals("external:"+json.fingerprint(source))).findFirst().orElseThrow();
        assertEquals(1,first.assertions().size());var claim=first.assertions().getFirst();
        assertEquals(new Relationship("external:"+json.fingerprint(target),RelationshipKind.DEPENDENCY,"runtime"),claim.relationship());assertEquals("north",claim.organization());assertNull(claim.start());assertNull(claim.endExclusive());
        assertTrue(read.candidates().stream().filter(c->c.id().equals("external:"+json.fingerprint(target))).findFirst().orElseThrow().assertions().isEmpty());
    }
    @Test void unconfiguredAndUnauthorizedProfilesNeverAcquirePluginLease() {
        var catalog=mock(ExtensionCatalog.class);var journal=mock(InventoryJournal.class);
        var host=new InventorySourceHost(new InventorySourceProfiles(),catalog,journal,new IntegrationJson(JsonMapper.builder().build()),new MockEnvironment());
        var demand=new Demand("repo-a","scope-a","draft",1L,2L,3L,"snapshot","catalog","v1",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.now(),null,"north",null));
        assertThrows(RuntimeException.class,()->host.read(new Scope("repo-a","scope-a","draft","alice"),demand,List.of("unconfigured")));
        verifyNoInteractions(catalog,journal);
        assertTrue(host.read(new Scope("repo-a","scope-a","draft","alice"),demand,List.of()).candidates().isEmpty());
    }
    @Test void authorizedLeaseReadRetainsSuccessAndFailureCannotReplaceItOrLeakCredentials() throws Exception {
        String url="jdbc:hsqldb:mem:source-host-"+UUID.randomUUID();
        try(var fixture=new com.taxonomy.editor.EditorPersistenceFixture(url,InventoryObservationEntity.class,com.taxonomy.workspace.model.SystemRepository.class,InventoryComparisonEntity.class)) {
            var json=new IntegrationJson(JsonMapper.builder().build());
            var journal=new InventoryJournal(fixture.factory,new org.springframework.orm.jpa.JpaTransactionManager(fixture.factory),json,com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo-a"));
            var profiles=new InventorySourceProfiles();
            var projection=new com.taxonomy.extension.api.inventory.InventoryReadContracts.TypeProjection("Application",com.taxonomy.extension.api.inventory.InventoryReadContracts.ObjectKind.APPLICATION,null,Map.of(),Map.of("name","name"));
            profiles.setProfiles(Map.of("approved",new InventorySourceProfiles.Profile("v1","source","instance",java.net.URI.create("https://inventory.example"),"repo-a","north",Set.of("alice"),Set.of("object-a"),List.of(projection),false,com.taxonomy.extension.api.inventory.InventoryReadContracts.Limits.defaults(),Map.of(),"INVENTORY_CREDENTIAL",null,false,List.of())));
            var catalog=mock(ExtensionCatalog.class);
            @SuppressWarnings("unchecked") var lease=(com.taxonomy.extension.api.plugin.ExtensionLease<com.taxonomy.extension.api.inventory.InventorySourceExtension>)mock(com.taxonomy.extension.api.plugin.ExtensionLease.class);
            var extension=mock(com.taxonomy.extension.api.inventory.InventorySourceExtension.class);when(lease.extension()).thenReturn(extension);when(lease.plugin()).thenReturn(new com.taxonomy.extension.api.plugin.PluginIdentity("fixture","1","0".repeat(64)));when(catalog.acquire(any(),eq(com.taxonomy.extension.api.inventory.InventorySourceExtension.class))).thenReturn(lease);
            when(extension.read(any(),any())).thenAnswer(call->{
                var request=(com.taxonomy.extension.api.inventory.InventoryReadContracts.Request)call.getArgument(0);
                var access=(com.taxonomy.extension.api.inventory.InventoryReadContracts.ReadAccess)call.getArgument(1);
                assertEquals("private-fixture-credential",new String(access.credential()));
                return new com.taxonomy.extension.api.inventory.InventoryReadContracts.Observation(request,Instant.EPOCH,Instant.EPOCH,com.taxonomy.extension.api.inventory.InventoryReadContracts.Completeness.SELECTED_SCOPE_READ_NON_ATOMIC,2,10,List.of(),List.of(),Set.of("NON_ATOMIC"));
            });
            var host=new InventorySourceHost(profiles,catalog,journal,json,new MockEnvironment().withProperty("INVENTORY_CREDENTIAL","private-fixture-credential"));
            var scope=new Scope("repo-a","scope-a","draft","alice");
            var demand=new Demand("repo-a","scope-a","draft",1L,2L,3L,"snapshot","catalog","v1",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.now(),null,"north",null));
            var read=host.read(scope,demand,List.of("approved"));assertEquals(1,read.sources().size());verify(lease).close();
            doThrow(new com.taxonomy.extension.api.inventory.InventoryReadContracts.ReadFailure("ACCESS_DENIED")).when(extension).read(any(),any());
            assertThrows(com.taxonomy.extension.api.inventory.InventoryReadContracts.ReadFailure.class,()->host.read(scope,demand,List.of("approved")));
            var em=fixture.factory.createEntityManager();try {
                assertEquals(1,((Number)em.createNativeQuery("select count(*) from inventory_observation where status='SUCCEEDED'").getSingleResult()).intValue());
                assertEquals(1,((Number)em.createNativeQuery("select count(*) from inventory_observation where status='FAILED'").getSingleResult()).intValue());
                Object payload=em.createNativeQuery("select observation_json from inventory_observation where status='SUCCEEDED'").getSingleResult();
                String encoded=payload instanceof java.sql.Clob clob?clob.getSubString(1,(int)clob.length()):payload.toString();
                assertFalse(encoded.contains("private-fixture-credential"));assertFalse(encoded.contains("INVENTORY_CREDENTIAL"));
            } finally {em.close();}
            assertThrows(RuntimeException.class,()->host.authorize(new Scope("repo-a","scope-a","draft","bob"),demand,read.sources()));
            verify(lease,times(2)).close();
        }
    }

}
