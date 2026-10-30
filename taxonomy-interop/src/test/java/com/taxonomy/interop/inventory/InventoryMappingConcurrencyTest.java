package com.taxonomy.interop.inventory;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.interop.IntegrationJson;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMappingConcurrencyTest {
    @Test void replacementInAnotherProjectCannotCommitBetweenReviewValidationAndApplication() throws Exception {
        try(var fixture=new EditorPersistenceFixture("jdbc:hsqldb:mem:mapping-race-"+UUID.randomUUID()+";hsqldb.tx=mvcc",com.taxonomy.workspace.model.SystemRepository.class,InventoryComparisonEntity.class,InventoryReviewEntity.class,InventoryMappingEntity.class)) {
            var store=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),new IntegrationJson(JsonMapper.builder().build()),com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo"));
            var scope=new Scope("repo","scope","main","alice");
            var demand=new Demand("repo","scope","main",1L,2L,3L,"snapshot","taxonomy","snapshot-fp",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.of(2030,1,1),null,"org",null));
            var first=new Comparison(UUID.randomUUID(),demand,"v1",Instant.now(),List.of(),List.of(),List.of(),"first");
            var secondDemand=new Demand("repo","scope","main",99L,2L,3L,"snapshot-2","taxonomy","snapshot-fp",List.of("fixture:node"),demand.question());
            store.save(scope,first);store.save(scope,new Comparison(UUID.randomUUID(),secondDemand,"v1",Instant.now(),List.of(),List.of(),List.of(),"second"));
            // Establish the real scope anchor before racing the second write.
            store.map(scope,new Mapping(UUID.randomUUID(),"remote:object",1L,"product-fp","source","1","source-fp","Reviewed identity","alice",Instant.now()));
            var started=new CountDownLatch(1);var submitted=new CountDownLatch(1);
            try(var executor=Executors.newSingleThreadExecutor()) {
                final Future<?>[] replacement=new Future<?>[1];
                store.review(scope,first.id(),new Review(UUID.randomUUID(),"first","remote:object","Reviewed all evidence",null,false),()->{
                    replacement[0]=executor.submit(()->{started.countDown();store.map(scope,new Mapping(UUID.randomUUID(),"remote:object",2L,"new-product","source","1","source-fp","Replace identity","alice",Instant.now()));submitted.countDown();});
                    try {assertTrue(started.await(10,TimeUnit.SECONDS));assertFalse(submitted.await(300,TimeUnit.MILLISECONDS),"Mapping replacement committed while reviewed evidence was being applied");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                    return null;
                });
                // A competing optimistic writer may fail closed instead of waiting then committing.
                try {replacement[0].get(10,TimeUnit.SECONDS);} catch(ExecutionException conflict) {assertNotNull(conflict.getCause());}
                assertEquals(1,store.reviews(scope,first.id()).size());
            }
        }
    }
}
