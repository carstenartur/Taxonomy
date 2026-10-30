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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class InventoryJournalTest {
    private final Scope scope=new Scope("repo-a","scope-a","draft","alice");
    @Test void retainedComparisonReviewReplayAndScopeSurviveNewStore() throws Exception {
        try(var fixture=new EditorPersistenceFixture("jdbc:hsqldb:mem:inventory-"+UUID.randomUUID(),com.taxonomy.workspace.model.SystemRepository.class,InventoryComparisonEntity.class,InventoryAssertionEntity.class,InventoryObservationEntity.class,InventoryReviewEntity.class)) {
            var store=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),new IntegrationJson(JsonMapper.builder().build()),com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo-a"));
            var demand=new Demand("repo-a","scope-a","draft",1L,2L,3L,"snapshot","catalog","demand-v1",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null));
            var comparison=new Comparison(UUID.randomUUID(),demand,"v1",Instant.now(),List.of(),List.of(),List.of(),"fingerprint");
            store.save(scope,comparison);
            var review=new Review(UUID.randomUUID(),"fingerprint","product:1","Reviewed evidence",null,false);
            var calls=new AtomicInteger();
            store.review(scope,comparison.id(),review,()->{calls.incrementAndGet();return null;});
            var restarted=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),new IntegrationJson(JsonMapper.builder().build()),com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo-a"));
            restarted.review(scope,comparison.id(),review,()->{calls.incrementAndGet();return null;});
            assertEquals(1,calls.get()); assertEquals(comparison,restarted.read(scope,comparison.id()));
            assertThrows(RuntimeException.class,()->restarted.read(new Scope("repo-b","scope-a","draft","alice"),comparison.id()));
            assertThrows(RuntimeException.class,()->restarted.review(scope,comparison.id(),new Review(review.id(),"fingerprint","product:1","Changed rationale",null,false),()->null));
        }
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void fileBackedComparisonAndReviewedRationaleSurviveFactoryRestart() throws Exception {
        String url="jdbc:hsqldb:file:"+directory.resolve("inventory").toAbsolutePath()+";shutdown=true";
        var demand=new Demand("repo-a","scope-a","draft",1L,2L,3L,"snapshot","catalog","demand-v1",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null));
        var comparison=new Comparison(UUID.randomUUID(),demand,"v1",Instant.now(),List.of(),List.of(),List.of(),"fingerprint");
        var review=new Review(UUID.randomUUID(),"fingerprint","product:1","Retain the documented exclusion for the next review",null,false);
        Class<?>[] entities={com.taxonomy.workspace.model.SystemRepository.class,InventoryComparisonEntity.class,InventoryReviewEntity.class};
        try(var fixture=new EditorPersistenceFixture(url,entities)) {
            var store=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),new IntegrationJson(JsonMapper.builder().build()),com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo-a"));
            store.save(scope,comparison);store.review(scope,comparison.id(),review,()->null);
        }
        try(var fixture=new EditorPersistenceFixture(url,entities)) {
            var store=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),new IntegrationJson(JsonMapper.builder().build()),com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo-a"));
            assertEquals(comparison,store.read(scope,comparison.id()));assertEquals(review,store.reviews(scope,comparison.id()).getFirst().review());
            store.review(scope,comparison.id(),review,()->{throw new AssertionError("A restarted review must not reapply");});
        }
    }

    @Test void assertionOverflowFailsClosedInsteadOfDroppingTheLastNegative() throws Exception {
        try(var fixture=new EditorPersistenceFixture("jdbc:hsqldb:mem:overflow-"+UUID.randomUUID(),com.taxonomy.workspace.model.SystemRepository.class,InventoryAssertionEntity.class)) {
            var json=new IntegrationJson(JsonMapper.builder().build());
            var transactions=new JpaTransactionManager(fixture.factory);
            var store=new InventoryJournal(fixture.factory,transactions,json,com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo-a"));
            var em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(fixture.factory);
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
                for(int i=0;i<1001;i++) {
                    var claim=new Assertion("claim-"+i,"product:1",Criterion.AVAILABILITY,null,i<1000,new Boundary("2029",Precision.YEAR),new Boundary("2032",Precision.YEAR),"north",null,com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind.DECLARED,"Explicit sourced assertion",Instant.EPOCH);
                    em.createNativeQuery("insert into inventory_assertion(id,repository_id,scope_key,branch_name,candidate_id,assertion_json) values (?,?,?,?,?,?)")
                        .setParameter(1,String.format("overflow-%04d",i)).setParameter(2,scope.repositoryId()).setParameter(3,scope.scopeKey()).setParameter(4,scope.branch()).setParameter(5,"product:1").setParameter(6,json.write(claim)).executeUpdate();
                }
            });
            var failure=assertThrows(com.taxonomy.interop.IntegrationProblem.class,()->store.assertions(scope,"product:1"));
            assertEquals("ASSERTION_LIMIT_EXCEEDED",failure.code());
        }
    }

}
