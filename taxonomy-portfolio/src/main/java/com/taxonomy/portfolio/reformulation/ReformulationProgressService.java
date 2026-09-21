package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.Run;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.reformulation.NodeSynthesisResult;
import com.taxonomy.reformulation.ReconciliationResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only inspection of committed outputs; never publishes a draft or reconstructs one from mixed runs. */
@Service
@Transactional(readOnly = true)
public class ReformulationProgressService {
    public record CheckpointSummary(String checkpointId, String kind, Instant createdAt) {}
    public record Progress(String runId, long sourceVersionId, long sourceRevision, String status,
                           String failureCode, Instant createdAt, Instant lastCheckpointAt,
                           long runCheckpointCount, long proposalCheckpointCount, Map<String, Long> countsByKind,
                           List<CheckpointSummary> items, String nextAfter) {}
    public record Partial(String checkpointId, String runId, long sourceVersionId, long sourceRevision,
                          String kind, Instant createdAt, NodeSynthesisResult node, ReconciliationResult reconciliation) {}
    private record Authorized(String proposalId, String scope, long sourceVersionId, Run run) {}
    private static final String FILTER = "c.proposalId = :proposal and c.scopeKey = :scope and c.runId = :run";
    private static final Set<String> NODE_KINDS = Set.of("NODE", "REWORD", "NODE_GROUP", "NODE_AGGREGATE");
    private final ProjectPortfolioService projects;
    private final ObjectMapper json;
    @PersistenceContext private EntityManager em;

    public ReformulationProgressService(ProjectPortfolioService projects, ObjectMapper json) {
        this.projects = projects;
        this.json = json;
    }

    public Progress progress(Long projectId, Long requirementId, String proposalId, String runId,
                             int limit, String after, String actor, WorkspaceContext context) {
        var a = authorize(projectId, requirementId, proposalId, runId, actor, context);
        if (limit < 1 || limit > 50) throw PortfolioException.validation("Progress page size must be between 1 and 50");
        Instant afterTime = null;
        if (after != null && !after.isEmpty()) {
            requireId(after);
            afterTime = query("select c.createdAt from ReformulationNodeCheckpoint c where " + FILTER + " and c.id = :id", Instant.class, a)
                    .setParameter("id", after).getResultStream().findFirst()
                    .orElseThrow(() -> PortfolioException.notFound("Progress cursor not found in this run"));
        }
        String cursor = afterTime == null ? "" : " and (c.createdAt > :time or (c.createdAt = :time and c.id > :after))";
        var rows = query("select c.id, c.taskKind, c.createdAt from ReformulationNodeCheckpoint c where " + FILTER
                + cursor + " order by c.createdAt, c.id", Object[].class, a).setMaxResults(limit + 1);
        if (afterTime != null) rows.setParameter("time", afterTime).setParameter("after", after);
        var available = rows.getResultList();
        var page = available.stream().limit(limit).map(row -> new CheckpointSummary((String) row[0], (String) row[1], (Instant) row[2])).toList();
        var counts = new TreeMap<String, Long>();
        for (var row : query("select c.taskKind, count(c) from ReformulationNodeCheckpoint c where " + FILTER
                + " group by c.taskKind", Object[].class, a).getResultList()) counts.put((String) row[0], (Long) row[1]);
        long total = em.createQuery("select count(c) from ReformulationNodeCheckpoint c where c.proposalId = :proposal and c.scopeKey = :scope", Long.class)
                .setParameter("proposal", proposalId).setParameter("scope", a.scope()).getSingleResult();
        Instant last = query("select max(c.createdAt) from ReformulationNodeCheckpoint c where " + FILTER, Instant.class, a).getSingleResult();
        return new Progress(runId, a.sourceVersionId(), a.run().sourceRevision(), a.run().status(), a.run().failureCode(), a.run().createdAt(),
                last, counts.values().stream().mapToLong(Long::longValue).sum(), total, Map.copyOf(counts), page,
                available.size() > limit ? page.getLast().checkpointId() : null);
    }

    /** A full typed result is fetched only on demand; overview pages never load result LOBs. */
    public Partial partial(Long projectId, Long requirementId, String proposalId, String runId,
                           String checkpointId, String actor, WorkspaceContext context) {
        var a = authorize(projectId, requirementId, proposalId, runId, actor, context);
        requireId(checkpointId);
        var row = query("select c.taskKind, c.createdAt, c.resultPayload from ReformulationNodeCheckpoint c where " + FILTER + " and c.id = :id", Object[].class, a)
                .setParameter("id", checkpointId).getResultStream().findFirst()
                .orElseThrow(() -> PortfolioException.notFound("Partial result not found in this run"));
        String kind = (String) row[0];
        NodeSynthesisResult node = null;
        ReconciliationResult reconciliation = null;
        try {
            var tree = json.readTree((String) row[2]);
            if (!tree.isObject()) throw new IllegalArgumentException("Untyped output");
            if (NODE_KINDS.contains(kind)) {
                if (!tree.path("nodeId").isString() || !tree.path("summary").isString()) throw new IllegalArgumentException("Missing node text");
                node = json.treeToValue(tree, NodeSynthesisResult.class);
            } else if ("RECONCILE".equals(kind)) {
                if (!tree.path("affectedSectionIds").isArray() || !tree.path("sourceResolutions").isObject() || !tree.path("findings").isArray())
                    throw new IllegalArgumentException("Missing reconciliation contract");
                reconciliation = json.treeToValue(tree, ReconciliationResult.class);
            } else throw new IllegalArgumentException("Unsupported output kind");
        } catch (RuntimeException unreadable) {
            // Legacy/incompatible/corrupt data is not a reason to return arbitrary raw JSON or prompts.
            throw PortfolioException.conflict("PARTIAL_RESULT_UNAVAILABLE: stored output has an unsupported format");
        }
        return new Partial(checkpointId, runId, a.sourceVersionId(), a.run().sourceRevision(), kind, (Instant) row[1], node, reconciliation);
    }

    private Authorized authorize(Long projectId, Long requirementId, String proposalId, String runId, String actor, WorkspaceContext context) {
        // Reuse the same requirement/project/workspace authorization as ordinary offer reads.
        projects.requireRequirement(projectId, requirementId, actor, context);
        String scope = PortfolioScope.key(actor, context);
        Long sourceVersion = em.createQuery("select p.sourceVersionId from ReformulationProposal p where p.id = :proposal and p.projectId = :project and p.requirementId = :requirement and p.scopeKey = :scope", Long.class)
                .setParameter("proposal", proposalId).setParameter("project", projectId).setParameter("requirement", requirementId).setParameter("scope", scope)
                .getResultStream().findFirst().orElseThrow(() -> PortfolioException.notFound("Reformulation proposal not found"));
        String payload = em.createQuery("select r.payload from ReformulationRun r where r.id = :run and r.proposalId = :proposal and r.scopeKey = :scope", String.class)
                .setParameter("run", runId).setParameter("proposal", proposalId).setParameter("scope", scope).getResultStream().findFirst()
                .orElseThrow(() -> PortfolioException.notFound("Synthesis run not found"));
        return new Authorized(proposalId, scope, sourceVersion, json.readValue(payload, Run.class));
    }
    private <T> jakarta.persistence.TypedQuery<T> query(String jpql, Class<T> type, Authorized a) {
        return em.createQuery(jpql, type).setParameter("proposal", a.proposalId()).setParameter("scope", a.scope()).setParameter("run", a.run().id());
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw PortfolioException.validation("Invalid checkpoint identifier");
    }
}
