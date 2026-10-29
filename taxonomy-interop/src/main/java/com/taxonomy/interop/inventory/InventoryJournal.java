package com.taxonomy.interop.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.Observation;
import com.taxonomy.interop.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Inventory observations are companion evidence, never ExchangeDocument operation payloads. */
@Repository
public class InventoryJournal {
    private static final int ASSERTION_LIMIT=1000;
    private final EntityManager em; private final TransactionTemplate tx; private final IntegrationJson json;
    public InventoryJournal(EntityManagerFactory factory,PlatformTransactionManager transactions,IntegrationJson json) {
        this.em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);this.tx=new TransactionTemplate(transactions);this.json=json;
    }
    public List<Assertion> assertions(Scope scope,String candidate) {
        return tx.execute(s->{var rows=em.createQuery("select a from InventoryAssertionEntity a where a.repositoryId=:repo and a.scopeKey=:scope and a.branch=:branch and a.candidateId=:candidate order by a.id",InventoryAssertionEntity.class)
                .setParameter("repo",scope.repositoryId()).setParameter("scope",scope.scopeKey()).setParameter("branch",scope.branch()).setParameter("candidate",candidate).setMaxResults(ASSERTION_LIMIT+1).getResultList();
            if(rows.size()>ASSERTION_LIMIT)throw new IntegrationProblem("ASSERTION_LIMIT_EXCEEDED",409,"The product exceeds the immutable assertion limit; no evidence subset may authorize comparison or review");
            return rows.stream().map(a->json.read(a.assertionJson,Assertion.class)).toList();});
    }
    public void append(Scope scope,Assertion assertion) {
        tx.executeWithoutResult(s->{String id=json.fingerprint(List.of(scope.repositoryId(),scope.scopeKey(),scope.branch(),assertion.id()));
            var prior=em.find(InventoryAssertionEntity.class,id);
            if(prior!=null) { if(!prior.assertionJson.equals(json.write(assertion))) throw IntegrationProblem.conflict("ASSERTION_ID_REUSED");return; }
            var e=new InventoryAssertionEntity();e.id=id;e.repositoryId=scope.repositoryId();e.scopeKey=scope.scopeKey();e.branch=scope.branch();e.candidateId=assertion.candidateId();e.assertionJson=json.write(assertion);em.persist(e);});
    }
    public void save(Scope scope,Comparison comparison) {
        requireDemand(scope,comparison.demand());
        tx.executeWithoutResult(s->{var prior=em.find(InventoryComparisonEntity.class,comparison.id().toString());
            if(prior!=null) { requireScope(scope,prior.repositoryId,prior.scopeKey,prior.branch);if(!prior.comparisonJson.equals(json.write(comparison))) throw IntegrationProblem.conflict("COMPARISON_ID_REUSED");return; }
            var e=new InventoryComparisonEntity();e.id=comparison.id().toString();e.repositoryId=scope.repositoryId();e.scopeKey=scope.scopeKey();e.branch=scope.branch();e.projectId=comparison.demand().projectId();e.snapshotId=comparison.demand().snapshotId();e.actor=scope.actor();e.fingerprint=comparison.fingerprint();e.comparisonJson=json.write(comparison);em.persist(e);});
    }
    public Comparison read(Scope scope,UUID id) { return tx.execute(s->json.read(require(scope,id,false).comparisonJson,Comparison.class)); }
    public List<Comparison> list(Scope scope,Long project,String snapshot) {
        return tx.execute(s->em.createQuery("select c from InventoryComparisonEntity c where c.repositoryId=:repo and c.scopeKey=:scope and c.branch=:branch and c.projectId=:project and c.snapshotId=:snapshot order by c.id",InventoryComparisonEntity.class)
                .setParameter("repo",scope.repositoryId()).setParameter("scope",scope.scopeKey()).setParameter("branch",scope.branch()).setParameter("project",project).setParameter("snapshot",snapshot).setMaxResults(100).getResultList().stream().map(c->json.read(c.comparisonJson,Comparison.class)).toList());
    }
    public List<Reviewed> reviews(Scope scope,UUID comparisonId) {
        return tx.execute(s->{require(scope,comparisonId,false);return em.createQuery("select r from InventoryReviewEntity r where r.repositoryId=:repo and r.scopeKey=:scope and r.branch=:branch and r.comparisonId=:comparison order by r.id",InventoryReviewEntity.class)
            .setParameter("repo",scope.repositoryId()).setParameter("scope",scope.scopeKey()).setParameter("branch",scope.branch()).setParameter("comparison",comparisonId.toString()).setMaxResults(100).getResultList().stream().map(r->json.read(r.reviewJson,Reviewed.class)).toList();});
    }
    public Reviewed review(Scope scope,UUID id,Review review,Supplier<Void> apply) {
        return tx.execute(s->{var comparison=require(scope,id,true);
            if(!comparison.fingerprint.equals(review.comparisonFingerprint())) throw IntegrationProblem.conflict("COMPARISON_CHANGED");
            if(!comparison.actor.equals(scope.actor())) throw new IntegrationProblem("REVIEW_ACTOR",403,"Only the comparison actor may review it");
            var prior=em.find(InventoryReviewEntity.class,review.id().toString());String fp=json.fingerprint(review);
            if(prior!=null) { requireScope(scope,prior.repositoryId,prior.scopeKey,prior.branch);if(!prior.comparisonId.equals(id.toString()) || !prior.reviewFingerprint.equals(fp)) throw IntegrationProblem.conflict("REVIEW_ID_REUSED");return json.read(prior.reviewJson,Reviewed.class); }
            apply.get();var reviewed=new Reviewed(review,Instant.now(),scope.actor());var e=new InventoryReviewEntity();e.id=review.id().toString();e.repositoryId=scope.repositoryId();e.scopeKey=scope.scopeKey();e.branch=scope.branch();e.comparisonId=id.toString();e.reviewFingerprint=fp;e.reviewJson=json.write(reviewed);em.persist(e);em.flush();return reviewed;});
    }
    public void observation(Scope scope,String profile,String revision,String organization,UUID id,Observation observation,String failureCode) {
        tx.executeWithoutResult(s->{var e=new InventoryObservationEntity();e.id=id.toString();e.repositoryId=scope.repositoryId();e.scopeKey=scope.scopeKey();e.branch=scope.branch();e.profileId=profile;e.profileRevision=revision;e.actor=scope.actor();e.organization=organization;e.createdAt=Instant.now().toString();e.status=failureCode==null?"SUCCEEDED":"FAILED";e.failureCode=failureCode;e.observationJson=observation==null?null:json.write(observation);em.persist(e);});
    }
    public String latestSuccessful(Scope scope,String profile,String organization) {
        return tx.execute(s->em.createQuery("select e.id from InventoryObservationEntity e where e.repositoryId=:repo and e.scopeKey=:scope and e.branch=:branch and e.profileId=:profile and e.organization=:org and e.status='SUCCEEDED' order by e.createdAt desc,e.id desc",String.class)
            .setParameter("repo",scope.repositoryId()).setParameter("scope",scope.scopeKey()).setParameter("branch",scope.branch()).setParameter("profile",profile).setParameter("org",organization).setMaxResults(1).getResultStream().findFirst().orElse(null));
    }
    public void map(Scope scope,Mapping mapping) {
        tx.executeWithoutResult(s->{
            var prior=em.find(InventoryMappingEntity.class,mapping.id().toString());
            if(prior!=null) {requireScope(scope,prior.repositoryId,prior.scopeKey,prior.branch);if(!prior.mappingJson.equals(json.write(mapping)))throw IntegrationProblem.conflict("MAPPING_ID_REUSED");return;}
            if(!mapping.actor().equals(scope.actor()))throw new IntegrationProblem("MAPPING_ACTOR",403,"Mapping actor differs");
            var e=new InventoryMappingEntity();e.id=mapping.id().toString();e.repositoryId=scope.repositoryId();e.scopeKey=scope.scopeKey();e.branch=scope.branch();e.candidateId=mapping.candidateId();e.mappingJson=json.write(mapping);em.persist(e);
        });
    }
    public List<Mapping> mappings(Scope scope,String candidate) {
        return tx.execute(s->em.createQuery("select m from InventoryMappingEntity m where m.repositoryId=:repo and m.scopeKey=:scope and m.branch=:branch and m.candidateId=:candidate order by m.id",InventoryMappingEntity.class)
            .setParameter("repo",scope.repositoryId()).setParameter("scope",scope.scopeKey()).setParameter("branch",scope.branch()).setParameter("candidate",candidate).setMaxResults(100).getResultList().stream().map(m->json.read(m.mappingJson,Mapping.class)).toList());
    }
    private InventoryComparisonEntity require(Scope scope,UUID id,boolean lock) {
        var e=em.find(InventoryComparisonEntity.class,id.toString(),lock?LockModeType.PESSIMISTIC_WRITE:LockModeType.NONE);if(e==null)throw IntegrationProblem.missing();requireScope(scope,e.repositoryId,e.scopeKey,e.branch);return e;
    }
    private void requireDemand(Scope scope,Demand d) { requireScope(scope,d.repositoryId(),d.scopeKey(),d.branch()); }
    private void requireScope(Scope scope,String repo,String key,String branch) { if(!scope.repositoryId().equals(repo)||!scope.scopeKey().equals(key)||!scope.branch().equals(branch))throw IntegrationProblem.missing(); }
}
