package com.taxonomy.relations.service;

import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Short database transactions fence competing builders and publish both native vector fields together. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class BranchGraphVectorStore {
    @PersistenceContext private EntityManager em;
    private final RelationDecisionProjectionCheckpointRepository checkpoints;

    BranchGraphVectorStore(RelationDecisionProjectionCheckpointRepository checkpoints) { this.checkpoints = checkpoints; }

    enum State { MISSING, BUILDING, READY, STALE, FAILED }
    record Status(State state, String generation, String sourceCommit, String modelKey, int nodeCount, int relationCount) { }
    record Generation(String id, BranchGraphVectorIndex.Archive index) { }

    String begin(RepositoryContext context, BranchGraphVectorSource source, String modelIdentity) {
        var checkpoint = locked(context, source.commit());
        var row = em.find(BranchGraphVectorGeneration.class, checkpoint.getId());
        boolean creating = row == null;
        if (creating) {
            row = new BranchGraphVectorGeneration();
            row.checkpointId = checkpoint.getId();
        }
        row.generation = UUID.randomUUID().toString();
        row.state = State.BUILDING.name();
        row.startedAt = java.time.Instant.now();
        row.startedBy = context.username();
        row.publishedAt = null;
        row.commit = source.commit();
        row.sourceKey = source.key();
        row.catalogueRevision = source.catalogueRevision();
        row.definitionKey = source.catalogueDefinition();
        row.modelKey = source.modelKey();
        row.modelIdentity = modelIdentity;
        row.textFormat = BranchGraphVectorSource.TEXT_FORMAT;
        row.indexFormat = BranchGraphVectorIndex.FORMAT;
        row.relationsKey = source.relationsHash();
        row.bytes = null;
        row.indexSha256 = null;
        row.nodes = 0;
        row.relations = 0;
        if (creating) em.persist(row);
        return row.generation;
    }

    void publish(RepositoryContext context, BranchGraphVectorSource source, String token, BranchGraphVectorIndex.Archive archive, Runnable verifySource) {
        var checkpoint = locked(context, source.commit());
        var row = em.find(BranchGraphVectorGeneration.class, checkpoint.getId());
        if (row == null || !State.BUILDING.name().equals(row.state) || !token.equals(row.generation)
                || !source.key().equals(row.sourceKey) || !row.sourceKey.equals(archive.sourceKey())
                || checkpoint.getRelationCount() != archive.relationCount() || source.relationCount() != archive.relationCount()) {
            throw new SearchUnavailableException();
        }
        verifySource.run();
        row.bytes = archive.bytes();
        row.indexSha256 = digest(row.bytes);
        row.nodes = archive.nodeCount();
        row.relations = archive.relationCount();
        row.state = State.READY.name();
        row.publishedAt = java.time.Instant.now();
    }

    void fail(RepositoryContext context, String token) {
        var checkpoint = checkpoints.findExactForUpdate(context.repositoryId(), scope(context), context.branch());
        if (checkpoint.isEmpty()) return;
        var row = em.find(BranchGraphVectorGeneration.class, checkpoint.get().getId());
        if (row != null && State.BUILDING.name().equals(row.state) && token.equals(row.generation)) row.state = State.FAILED.name();
    }

    void purge(RepositoryContext context, Runnable verifyHead) {
        verifyHead.run();
        var checkpoint = checkpoints.findExactForUpdate(context.repositoryId(), scope(context), context.branch());
        if (checkpoint.isEmpty()) return;
        var row = em.find(BranchGraphVectorGeneration.class, checkpoint.get().getId());
        verifyHead.run();
        if (row != null) em.remove(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Status status(RepositoryContext context, BranchGraphVectorSource source) {
        // The scalar projection avoids loading a potentially large Lucene index for a readiness request.
        var rows = em.createQuery("""
                select new com.taxonomy.relations.service.BranchGraphVectorStore$Header(
                    v.state, v.generation, v.commit, v.sourceKey, v.modelKey, v.nodes, v.relations)
                from BranchGraphVectorGeneration v join v.checkpoint p
                where p.repositoryId=:repo and p.workspaceScopeKey=:scope and p.branch=:branch
                """, Header.class).setParameter("repo", context.repositoryId()).setParameter("scope", scope(context))
                .setParameter("branch", context.branch()).getResultList();
        if (rows.isEmpty()) return new Status(State.MISSING, null, null, null, 0, 0);
        var row = rows.getFirst();
        var state = source != null && source.key().equals(row.sourceKey()) ? State.valueOf(row.state()) : State.STALE;
        return new Status(state, row.generation(), row.commit(), row.modelKey(), row.nodes(), row.relations());
    }

    record Header(String state, String generation, String commit, String sourceKey, String modelKey, int nodes, int relations) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Generation read(RepositoryContext context, BranchGraphVectorSource source) {
        var checkpoint = checkpoints.findByRepositoryIdAndWorkspaceScopeKeyAndBranch(context.repositoryId(), scope(context), context.branch())
                .orElseThrow(SearchUnavailableException::new);
        var row = em.find(BranchGraphVectorGeneration.class, checkpoint.getId());
        if (row == null || !State.READY.name().equals(row.state) || !source.commit().equals(checkpoint.getAuthoritativeCommitId())
                || !source.key().equals(row.sourceKey) || row.bytes == null || !digest(row.bytes).equals(row.indexSha256)) {
            throw new SearchUnavailableException();
        }
        return new Generation(row.generation, new BranchGraphVectorIndex.Archive(row.bytes, row.nodes, row.relations, row.sourceKey));
    }

    private RelationDecisionProjectionCheckpoint locked(RepositoryContext context, String commit) {
        return checkpoints.findExactForUpdate(context.repositoryId(), scope(context), context.branch())
                .filter(row -> commit.equals(row.getAuthoritativeCommitId())).orElseThrow(SearchUnavailableException::new);
    }

    private static String scope(RepositoryContext context) { return RelationDecisionProjection.scopeKeyFor(context.workspaceId()); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
