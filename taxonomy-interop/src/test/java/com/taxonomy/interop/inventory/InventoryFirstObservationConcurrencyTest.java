package com.taxonomy.interop.inventory;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.interop.IntegrationJson;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryFirstObservationConcurrencyTest {
    @Test void firstComparisonCannotReviewAnOlderSourceWhileAnEarlierWriterHasNotYetRetainedItsObservation() throws Exception {
        try(var fixture=new EditorPersistenceFixture("jdbc:hsqldb:mem:first-observation-"+UUID.randomUUID()+";hsqldb.tx=mvcc",com.taxonomy.workspace.model.SystemRepository.class,InventoryComparisonEntity.class,InventoryReviewEntity.class,InventoryObservationEntity.class)) {
            var json=new IntegrationJson(JsonMapper.builder().build());var scope=new Scope("repo","scope","main","alice");
            var store=new InventoryJournal(fixture.factory,new JpaTransactionManager(fixture.factory),json,com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(fixture.factory,"repo"));
            UUID original=UUID.randomUUID(),replacement=UUID.randomUUID();
            store.observation(scope,"source","1","org",original,null,null);
            var reachedEmptyScope=new CountDownLatch(1);var releaseWriter=new CountDownLatch(1);
            var intercepted=afterEmptyComparisonQuery(fixture.factory,reachedEmptyScope,releaseWriter);
            var writerStore=new InventoryJournal(intercepted,new JpaTransactionManager(intercepted),json,com.taxonomy.workspace.repository.RepositoryMutationFenceFixture.repositoryFence(intercepted,"repo"));
            var demand=new Demand("repo","scope","main",1L,2L,3L,"snapshot","catalog","snapshot-fp",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.of(2030,1,1),null,"org",null));
            var source=new SourceEvidence("source","1",original.toString(),Instant.EPOCH,"selector","SUCCEEDED",Set.of());
            var comparison=new Comparison(UUID.randomUUID(),demand,"v3",Instant.now(),List.of(),List.of(),List.of(source),"comparison-fp");
            try(var executor=Executors.newFixedThreadPool(2)) {
                var writer=executor.submit(()->writerStore.observation(scope,"source","1","org",replacement,null,null));
                assertTrue(reachedEmptyScope.await(10,TimeUnit.SECONDS),"Writer has actually checked the absent first comparison before its created_at is assigned");
                var applying=new CountDownLatch(1);var releaseApplication=new CountDownLatch(1);
                var decision=executor.submit(()->{
                    store.save(scope,comparison);
                    return store.review(scope,comparison.id(),new Review(UUID.randomUUID(),comparison.fingerprint(),"product:1","Review retained source",null,false),()->{
                        if(!source.observationId().equals(store.latestSuccessful(scope,"source","org")))throw com.taxonomy.interop.IntegrationProblem.conflict("INVENTORY_OBSERVATION_CHANGED");
                        applying.countDown();
                        try {if(!releaseApplication.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Review application pause not released");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                        return null;
                    });
                });
                try {
                    // With the old missing-anchor behavior this is reached on the exact old authority.
                    // A real repository fence keeps save/review behind the unfinished writer instead.
                    boolean reached=applying.await(10,TimeUnit.SECONDS);
                    if(reached)assertEquals(original.toString(),store.latestSuccessful(scope,"source","org"));
                    releaseWriter.countDown();writer.get(10,TimeUnit.SECONDS);
                } finally {releaseWriter.countDown();releaseApplication.countDown();}
                assertEquals(replacement.toString(),store.latestSuccessful(scope,"source","org"),"The late-retained observation is actually the current source authority");
                var failure=assertThrows(ExecutionException.class,()->decision.get(10,TimeUnit.SECONDS));
                assertInstanceOf(com.taxonomy.interop.IntegrationProblem.class,failure.getCause());
                assertEquals(409,((com.taxonomy.interop.IntegrationProblem)failure.getCause()).status());
                assertTrue(store.reviews(scope,comparison.id()).isEmpty());
            } finally {releaseWriter.countDown();}
        }
    }
    /** Test-only scheduling around the actual completed HSQL query, before retention timestamps/inserts. */
    private EntityManagerFactory afterEmptyComparisonQuery(EntityManagerFactory factory,CountDownLatch reached,CountDownLatch release) {
        return (EntityManagerFactory)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EntityManagerFactory.class},(proxy,method,args)->{
            Object result=invoke(method,factory,args);
            if(!method.getName().equals("createEntityManager"))return result;
            var delegate=(EntityManager)result;
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EntityManager.class},(p,m,a)->{
                Object query=invoke(m,delegate,a);
                if(!m.getName().equals("createQuery") || a==null || !(a[0] instanceof String sql) || !sql.contains("InventoryComparisonEntity c where c.repositoryId") || !sql.contains("order by c.id"))return query;
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{TypedQuery.class},(qp,qm,qa)->{
                    Object rows=invoke(qm,query,qa);
                    if(qm.getName().equals("getResultList") && rows instanceof List<?> list && list.isEmpty()) {
                        reached.countDown();if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("First observation pause not released");
                    }
                    return rows==query?qp:rows;
                });
            });
        });
    }
    private static Object invoke(Method method,Object target,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
}
