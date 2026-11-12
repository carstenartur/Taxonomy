package com.taxonomy.backup.restore;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.cluster.ClusterAnalysisBackupRecords.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import com.taxonomy.analysis.operation.AnalysisAdapterBackup;
import com.taxonomy.analysis.operation.AnalysisAdapterStore;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.backup.BackupComponentId;
import com.taxonomy.backup.BackupRepositoryKey;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Executes the public analysis-owner boundary against an actual verified archive and isolated generation. */
final class AnalysisArchiveRoundTrip {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEXT = "Freeze and inspect the scope of the restored transport requirement";
    private static final String INPUT = AnalysisAdapterStore.digest(TEXT);
    private static final String NATIVE = """
            {"fileName":"notice.pdf","totalPages":1,"inputCharacters":12,"inputTruncated":false,
             "matches":[{"nodeCode":"CP","confidence":0.9}]}
            """;
    private final String observer = UUID.randomUUID().toString();
    private final String sourceRun = UUID.randomUUID().toString();
    private final String expiredClusterId = UUID.randomUUID().toString();
    private long clusterExpiredAt;
    private AnalysisAdapterStore sourceStore;
    private AnalysisAdapterStore.Scope sourceScope;
    private String sourceCommit, completedId, pendingId, expiredId, sourceOwner;

    void seed(SessionFactory source, PrincipalId principal, RepositoryContext repository, String commit) {
        sourceCommit = commit;
        sourceOwner = repository.username();
        sourceScope = new AnalysisAdapterStore.Scope(principal.value().toString(),repository.repositoryId(),repository.workspaceId(),repository.branch());
        var em = SharedEntityManagerCreator.createSharedEntityManager(source);
        var transactions = new JpaTransactionManager(source);
        sourceStore = new AnalysisAdapterStore(em,transactions);
        var completed = sourceStore.admit(sourceScope,"REGULATION_MAPPING",commit,INPUT,"map-command",true,observer).operation();
        completedId = completed.id();
        sourceStore.settle(sourceScope,completedId,completed.revision(),NATIVE,null);
        var historical = new AnalysisAdapterStore(em,transactions,Clock.fixed(Instant.now().minus(Duration.ofDays(45)),ZoneOffset.UTC));
        var expired = historical.admit(sourceScope,"REGULATION_MAPPING",commit,INPUT,"expired-command",true).operation();
        expiredId = expired.id();
        historical.settle(sourceScope,expiredId,expired.revision(),NATIVE,null);
        sourceStore.sweepScope(sourceScope,System.currentTimeMillis());
        assertThat(sourceStore.read(sourceScope,expiredId).state()).isEqualTo("EXPIRED");
        pendingId = sourceStore.admit(sourceScope,"DOCUMENT_EXTRACTION",commit,INPUT,"pending-command",true).operation().id();
        var publications = new AtomicInteger();
        var clusters = clusterStore(em,transactions,publications);
        var workspace = new WorkspaceContext(sourceOwner,repository.workspaceId(),repository.branch(),repository.repositoryId());
        var old = context(expiredClusterId,workspace,commit);
        clusters.admit(old,command(workspace),null,Map.of(TaxonomyShardRoot.of("CP"),JSON.writeValueAsString(root(old))));
        clusters.cancel(old);
        clusterExpiredAt = System.currentTimeMillis() - Duration.ofDays(2).toMillis();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                em.createQuery("update ClusterAnalysisRun r set r.createdAt=:old, r.updatedAt=:old where r.id=:id")
                        .setParameter("old",clusterExpiredAt - Duration.ofDays(31).toMillis())
                        .setParameter("id",expiredClusterId).executeUpdate());
        assertThat(clusters.retention().sweepScope(workspace,clusterExpiredAt)).isOne();
        var context = context(sourceRun,workspace,commit);
        clusters.admit(context,command(workspace),null,Map.of(TaxonomyShardRoot.of("CP"),JSON.writeValueAsString(root(context))));
        assertThat(publications.get()).isEqualTo(2);
    }

    void verify(VerifiedBackup archive, RestoreTargetGeneration generation, RestoreIdentityImporter.Result identity,
                GitRestoreImporter.Result git, BackupRepositoryKey sourceKey) throws IOException {
        generation.requireUnchanged(() -> {});
        var sourceActor = new PrincipalId(UUID.fromString(sourceScope.actor()));
        var actor = identity.targetPrincipals().get(sourceActor);
        assertThat(actor).isNotNull().isNotEqualTo(sourceActor);
        assertThat(identity.privatePrincipals()).doesNotContain(actor);
        var proof = git.repositories().get(sourceKey);
        var target = generation.allocations().get(sourceKey).targetKey();
        assertThat(proof.targetKey()).isEqualTo(target);
        assertThat(proof.commitMappings()).containsEntry(sourceCommit,sourceCommit);
        var restoredOwner = identity.sourceScopes().get(sourceOwner);
        assertThat(restoredOwner).isNotBlank().isNotEqualTo(sourceOwner);
        var scope = new AnalysisAdapterStore.Scope(actor.value().toString(),target.repositoryId(),target.workspaceId(),sourceScope.branch());
        var em = SharedEntityManagerCreator.createSharedEntityManager(generation.sessionFactory());
        var transactions = new JpaTransactionManager(generation.sessionFactory());
        var targetStore = new AnalysisAdapterStore(em,transactions);
        var receipts = records(archive,"adapter-operations",AnalysisAdapterBackup.Receipt.class);
        assertThat(receipts).extracting(row -> row.sourceId().value()).containsExactlyInAnyOrder(completedId,pendingId,expiredId);
        for (var receipt : receipts) {
            assertThat(receipt.scope()).isEqualTo(sourceScope);
            assertThat(receipt.sourceCommit()).isEqualTo(proof.commitMappings().get(sourceCommit));
            var targetId = UUID.randomUUID().toString();
            AnalysisAdapterBackup.restore(em,transactions,receipt,targetId,scope);
            boolean completed = receipt.sourceId().value().equals(completedId);
            boolean expired = receipt.sourceId().value().equals(expiredId);
            String commandKey = completed ? "map-command" : expired ? "expired-command" : "pending-command";
            var replay = targetStore.admit(scope,receipt.kind(),sourceCommit,INPUT,commandKey,true);
            assertThat(replay.claimed()).isFalse();
            assertThat(replay.operation().id()).isEqualTo(targetId);
            assertThat(replay.operation().scope()).isEqualTo(scope);
            if (completed) {
                var result = JSON.readTree(replay.operation().resultJson());
                assertThat(result.path("matches").get(0).path("nodeCode").asString()).isEqualTo("CP");
                assertThat(replay.operation().state()).isEqualTo("COMPLETED");
            } else if (expired) {
                assertThat(replay.operation().state()).isEqualTo("EXPIRED");
                assertThat(replay.operation().updatedAt()).isEqualTo(receipt.updatedAt());
                assertThat(replay.operation().resultJson()).isNull();
            } else {
                assertThat(replay.operation().state()).isEqualTo("FAILED");
                assertThat(replay.operation().failureCode()).isEqualTo("OUTCOME_UNKNOWN");
                assertThat(replay.operation().resultJson()).isNull();
            }
            assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> targetStore.read(sourceScope,targetId))
                    .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        }
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> targetStore.observe(scope,observer))
                .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(sourceStore.read(sourceScope,pendingId).state()).isEqualTo("RUNNING");
        assertThat(sourceStore.read(sourceScope,completedId).resultJson()).isEqualTo(NATIVE);

        var runs = records(archive,"cluster-runs",Run.class);
        assertThat(runs).extracting(run -> run.sourceId().value()).containsExactlyInAnyOrder(sourceRun,expiredClusterId);
        var active = runs.stream().filter(row -> row.sourceId().value().equals(sourceRun)).findFirst().orElseThrow();
        var marker = runs.stream().filter(row -> row.sourceId().value().equals(expiredClusterId)).findFirst().orElseThrow();
        assertThat(marker.sourceState()).isEqualTo("EXPIRED");
        assertThat(marker.updatedAt()).isEqualTo(clusterExpiredAt);
        assertThat(marker.commandJson()).isNull();
        assertThat(marker.resultJson()).isNull();
        var archived = new Archive(active,records(archive,"cluster-work",Work.class),
                records(archive,"cluster-inputs",Input.class),records(archive,"cluster-events",Event.class),
                records(archive,"cluster-calls",Call.class),records(archive,"cluster-attempts",Attempt.class));
        assertThat(archived.run().context().operationId()).isEqualTo(sourceRun);
        assertThat(archived.run().context().authority().sourceCommit()).isEqualTo(sourceCommit);
        var workspace = new WorkspaceContext(restoredOwner,target.workspaceId(),scope.branch(),target.repositoryId());
        var targetContext = context(UUID.randomUUID().toString(),workspace,proof.commitMappings().get(sourceCommit));
        var targetPublications = new AtomicInteger();
        var targetCluster = clusterStore(em,transactions,targetPublications);
        var restorer = new ClusterAnalysisBackupRestorer(em,transactions,JSON);
        restorer.restore(archived,targetContext,command(workspace));
        var expiredContext = context(UUID.randomUUID().toString(),workspace,proof.commitMappings().get(sourceCommit));
        restorer.restoreExpired(new Archive(marker,List.of(),List.of(),List.of(),List.of(),List.of()),
                expiredContext,workspace,Map.of());
        var sql = new org.springframework.jdbc.core.JdbcTemplate(generation.dataSource());
        assertThat(sql.queryForObject("select updated_at from analysis_cluster_run where id=?",Long.class,expiredContext.operationId()))
                .isEqualTo(clusterExpiredAt);
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() ->
                targetCluster.admit(expiredContext,command(workspace),null,
                        Map.of(TaxonomyShardRoot.of("CP"),JSON.writeValueAsString(root(expiredContext)))))
                .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(410));
        var reread = targetCluster.snapshot(targetContext);
        assertThat(reread.state()).isEqualTo(ClusterAnalysisState.CANCELLED);
        try (var observation = new DurableClusterAnalysisObservation(targetCluster,new ClusterAnalysisSignals(),AnalysisEventPublisher.NONE)) {
            assertThat(observation.snapshot(targetContext.operationId(),restoredOwner,workspace)).isPresent();
            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> observation.snapshot(expiredContext.operationId(),restoredOwner,workspace))
                    .satisfies(error -> {
                        assertThat(error.getStatusCode().value()).isEqualTo(410);
                        assertThat(error.getBody().getProperties()).containsEntry("code","ANALYSIS_EXPIRED");
                    });
            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> observation.snapshot(expiredContext.operationId(),sourceOwner,workspace))
                    .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> observation.snapshot(targetContext.operationId(),sourceOwner,workspace))
                    .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        }
        assertThat(targetPublications.get()).isZero();
        assertThat(new org.springframework.jdbc.core.JdbcTemplate(generation.dataSource())
                .queryForObject("select count(*) from analysis_dispatch_intent",Long.class)).isZero();
    }

    private static <T extends Record> List<T> records(VerifiedBackup archive,String kind,Class<T> type) throws IOException {
        var result = new ArrayList<T>();
        RestoreArchiveRecords.read(archive,new BackupComponentId("analysis"),"data/analysis/"+kind+".ndjson",kind,type,500,() -> {},result::add);
        return result;
    }

    private static ClusterAnalysisStore clusterStore(EntityManager em,JpaTransactionManager tx,AtomicInteger publications) {
        var dispatch = new AnalysisDispatchService(new AnalysisDispatchStore(em,tx),task -> publications.incrementAndGet(),10,20);
        return new ClusterAnalysisStore(em,tx,JSON,dispatch);
    }

    private static AnalysisOperationContext context(String id,WorkspaceContext w,String commit) {
        return new AnalysisOperationContext(id,new AnalysisSourceAuthority(w.repositoryId(),w.workspaceId(),w.currentBranch(),commit),
                RequirementReference.adHoc(TEXT),id);
    }

    private static AnalyzeRequirementCommand command(WorkspaceContext scope) {
        return new AnalyzeRequirementCommand(TEXT,false,20,"MOCK",scope.username(),scope,null,
                new AnalysisScope(Set.of("CP"),AnalysisMode.TAXONOMIES_ONLY));
    }

    private static RootCatalogueSnapshot root(AnalysisOperationContext context) {
        var original = context.authority();
        var source = new CatalogueSourceIdentity(original.repositoryId(),original.workspaceId(),original.branch(),original.sourceCommit());
        var node = new TaxonomyNode(); node.setCode("CP"); node.setTaxonomyRoot("CP"); node.setNameEn("Frozen capability");
        var absent = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED,null,0);
        var disabled = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED,null,0);
        var provenance = new CatalogueSourceJournal.Snapshot(UUID.randomUUID().toString(),Instant.EPOCH,absent,disabled,absent);
        var leaf = RootCatalogueSnapshot.Node.capture(node,new CatalogueOverlayService.NodeMetadata("CATEGORY",List.of(),1,false,null),false);
        return new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION,source,"CP",List.of(leaf),
                new CatalogueOverlayService.OverlayMetadata(false,"fixture",null,null,null,0),provenance);
    }
}
