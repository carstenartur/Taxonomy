package com.taxonomy.workspace.service;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.workspace.storage.DefaultCommitAdoption;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import jakarta.persistence.*;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = IOException.class)
public class BranchAdoptionService {
    private static final int MAX_MODEL_BYTES = 4 * 1024 * 1024;
    private final EntityManager db;
    private final BranchAdoptionTargets targets;
    private final WorkspaceFetchAuthority actors;
    private final WorkspaceArchitectureVersionPort editor;
    private final WorkspacePortfolioGitPort portfolio;
    private final CatalogueDefinitionResolver catalogues;

    public BranchAdoptionService(EntityManager db, BranchAdoptionTargets targets, WorkspaceFetchAuthority actors,
            WorkspaceArchitectureVersionPort editor, WorkspacePortfolioGitPort portfolio,
            CatalogueDefinitionResolver catalogues) {
        this.db = db;
        this.targets = targets;
        this.actors = actors;
        this.editor = editor;
        this.portfolio = portfolio;
        this.catalogues = catalogues;
    }

    @Transactional(readOnly = true)
    public BranchAdoptionRule.View inspect(RepositoryContext c) {
        var rule = db.find(BranchAdoptionRule.class, BranchAdoptionRule.key(c));
        return (rule == null ? new BranchAdoptionRule(c, targets.defaultBranch(c)) : rule).view();
    }

    public record Event(long revision, long recordedAt, String actor, String sourceBranch, String sourceCommit,
            String targetBefore, String targetAfter, String status, String detail) {
    }

    @Transactional(readOnly = true)
    public List<Event> history(RepositoryContext c) {
        return db
                .createQuery("from BranchAdoptionEvent where ruleId=:id order by revision desc",
                        BranchAdoptionEvent.class)
                .setParameter("id", BranchAdoptionRule.key(c)).setMaxResults(50).getResultList().stream()
                .map(e -> new Event(e.revision, e.recordedAt, e.actor, e.sourceBranch, e.sourceCommit,
                        e.targetBefore, e.targetAfter, e.status, e.detail))
                .toList();
    }

    public BranchAdoptionRule.View configure(RepositoryContext c, boolean enabled, long revision) throws IOException {
        var grant = actors.authorize(c.username(), RepositoryScope.CENTRAL_WRITE);
        // Serializes first activation as well as subsequent updates in every application instance.
        var key = BranchAdoptionRule.key(c);
        if (!enabled) {
            var rule = locked(key);
            checkRevision(rule, revision);
            if (rule == null)
                return inspect(c);
            // Stopping a rule requires current control over this target. Its upstream policy may have changed.
            if (!rule.principalId.equals(grant.principalId()))
                throw BranchAdoptionTargets.denied();
            if (rule.enabled) {
                rule.enabled = false;
                record(rule, "DISABLED",
                        rule.prepared == null ? null : "A prepared operation is retained for reconciliation",
                        grant.principalId());
            }
            return rule.view();
        }
        targets.lockRepository(c.repositoryId());
        var rule = locked(key);
        var target = targets.lock(c, grant);
        checkRevision(rule, revision);
        if (rule != null && rule.enabled)
            return rule.view();
        boolean firstActivation = rule == null;
        if (firstActivation)
            rule = new BranchAdoptionRule(c, target.sourceBranch());
        if (rule.prepared != null && !publicationReachedTarget(rule, target))
            rule.prepared = null;
        if (rule.prepared != null) {
            if (!rule.principalId.equals(grant.principalId()) || !c.username().equals(rule.username)
                    || !rule.sourceBranch.equals(target.sourceBranch()))
                throw BranchAdoptionTargets.denied();
        } else {
            var tip = target.source().directBranchHead(target.sourceBranch());
            var local = target.local().directBranchHead(c.branch());
            if (tip == null || local == null)
                throw new IllegalArgumentException("Source and target must exist");
            rule.sourceBranch = target.sourceBranch();
            rule.cursor = tip;
            rule.sourceCommit = tip;
            rule.sourceTip = tip;
            rule.targetBefore = local;
            rule.targetAfter = local;
        }
        rule.bind(c.username(), grant);
        rule.enabled = true;
        rule.nextCheck = 0;
        if (firstActivation)
            db.persist(rule);
        record(rule, "ENABLED", null, grant.principalId());
        return rule.view();
    }

    public BranchAdoptionRule.View decide(RepositoryContext c, String action, String commit, long revision)
            throws IOException {
        var grant = actors.authorize(c.username(), RepositoryScope.CENTRAL_WRITE);
        targets.lockRepository(c.repositoryId());
        var rule = locked(BranchAdoptionRule.key(c));
        var target = targets.lock(c, grant);
        checkRevision(rule, revision);
        if (rule == null || !rule.enabled || !rule.stopped() || !ObjectId.isId(commit)
                || !commit.equals(rule.sourceCommit) || !rule.principalId.equals(grant.principalId()))
            throw stale();
        if (!rule.sourceBranch.equals(target.sourceBranch()))
            throw stale();
        if ("SKIP".equals(action)) {
            if (rule.prepared != null && publicationReachedTarget(rule, target))
                throw new IllegalStateException("Reconcile the published candidate before skipping it");
            if (!commit.equals(DefaultCommitAdoption.next(target.source(), rule.cursor,
                    target.source().directBranchHead(rule.sourceBranch))))
                throw stale();
            rule.prepared = null;
            rule.cursor = commit;
            rule.targetBefore = target.local().directBranchHead(c.branch());
            rule.targetAfter = rule.targetBefore;
            record(rule, "SKIPPED", "Explicitly skipped the selected commit", grant.principalId());
        } else if ("RETRY".equals(action)) {
            if (rule.prepared != null && !publicationReachedTarget(rule, target))
                rule.prepared = null;
            rule.bind(c.username(), grant);
            record(rule, "ENABLED", "Retry the reviewed source commit", grant.principalId());
        } else
            throw new IllegalArgumentException("Choose RETRY or SKIP");
        rule.nextCheck = 0;
        return rule.view();
    }

    public record Due(String id, long revision) {
    }

    @Transactional(readOnly = true)
    public List<Due> due() {
        return db
                .createQuery("from BranchAdoptionRule where enabled=true and nextCheck <= :now order by nextCheck,id",
                        BranchAdoptionRule.class)
                .setParameter("now", System.currentTimeMillis()).setMaxResults(32).getResultList().stream()
                .map(r -> new Due(r.id, r.revision)).toList();
    }

    /** Each call is a separate transaction: a prepared candidate is durable before any branch CAS. */
    public void advance(Due observed) throws IOException {
        advance(observed, true, null);
    }

    public void advance(Due observed, boolean unattended, String reviewedCommit) throws IOException {
        var repositories = db
                .createQuery("select r.repositoryId from BranchAdoptionRule r where r.id=:id", String.class)
                .setParameter("id", observed.id()).getResultList();
        if (repositories.isEmpty())
            return;
        targets.lockRepository(repositories.getFirst());
        var rule = locked(observed.id());
        if (rule == null || rule.revision != observed.revision() || !rule.enabled || rule.stopped())
            return;
        rule.nextCheck = System.currentTimeMillis() + 30_000;
        Runnable authorize = () -> actors.checkPublication(rule.grant(), rule.username, RepositoryScope.CENTRAL_WRITE,
                unattended);
        authorize.run();
        RepositoryMembershipIdentity.RecordedActor.during(rule.username, rule.grant(), () -> {
            var target = targets.lock(rule.context(), rule.grant());
            if (!rule.sourceBranch.equals(target.sourceBranch()))
                throw stale();
            if (rule.prepared != null)
                publish(rule, target, authorize);
            else if ("SELECTED".equals(rule.status))
                prepare(rule, target);
            else
                selectNext(rule, target, reviewedCommit);
            return null;
        });
    }

    private void selectNext(BranchAdoptionRule rule, BranchAdoptionTargets.Target target, String reviewedCommit)
            throws IOException {
        var tip = target.source().directBranchHead(rule.sourceBranch);
        var next = DefaultCommitAdoption.next(target.source(), rule.cursor, tip);
        if (next == null)
            return;
        if (reviewedCommit != null && !reviewedCommit.equals(next))
            throw stale();
        rule.sourceTip = tip;
        rule.sourceCommit = next;
        rule.targetBefore = target.local().directBranchHead(rule.branch);
        rule.targetAfter = rule.targetBefore;
        rule.nextCheck = 0;
        record(rule, "SELECTED", null, rule.principalId);
    }

    private void prepare(BranchAdoptionRule rule, BranchAdoptionTargets.Target target) throws IOException {
        var next = rule.sourceCommit;
        if (!Objects.equals(rule.sourceTip, target.source().directBranchHead(rule.sourceBranch)))
            throw stale();
        if (rule.targetBefore == null) {
            stop(rule, "PREREQUISITE", "The target branch is unavailable");
            return;
        }
        if (target.source() != target.local())
            GitHistoryTransfer.copyCommitClosure(target.source(), target.local(), next, List.of(rule.targetBefore));
        if (!DefaultCommitAdoption.related(target.local(), rule.targetBefore, next)) {
            stop(rule, "PREREQUISITE", "Source and target do not have a unique common history");
            return;
        }
        var change = target.local().prepareDefaultCommit(rule.repositoryId, rule.sourceBranch, next, rule.branch,
                rule.targetBefore, rule.username);
        if ("ALREADY_CONTAINED".equals(change.result())) {
            rule.cursor = next;
            rule.nextCheck = 0;
            record(rule, change.result(), change.detail(), rule.principalId);
            return;
        }
        if (!"PREPARED".equals(change.result())) {
            stop(rule, change.result(), change.detail());
            return;
        }
        var local = target.local();
        try {
            var before = local.getDslAtCommit(rule.cursor, MAX_MODEL_BYTES);
            var source = local.getDslAtCommit(next, MAX_MODEL_BYTES);
            var onto = local.getDslAtCommit(rule.targetBefore, MAX_MODEL_BYTES);
            if (overlappingBlocks(before, source, onto)) {
                stop(rule, "CONFLICT", "Both versions changed the same architecture object");
                return;
            }
            validate(local.getDslAtCommit(change.candidate(), MAX_MODEL_BYTES));
        } catch (IllegalArgumentException invalid) {
            stop(rule, "PREREQUISITE", "The combined model has unresolved dependencies or invalid structure");
            return;
        }
        DefaultCommitAdoption.retain(local, rule.id, change.candidate());
        rule.prepared = change.candidate();
        rule.nextCheck = 0;
        record(rule, "PREPARED", "The exact single-commit change is ready for final checks", rule.principalId);
    }

    private static boolean overlappingBlocks(String base, String source, String target) {
        var changed = ArchitectureSemanticPatch.between(base, source).stream()
                .map(ArchitectureSemanticPatch.BlockChange::id)
                .collect(Collectors.toSet());
        if (changed.isEmpty())
            return false;
        var different = ArchitectureSemanticPatch.between(source, target).stream()
                .map(ArchitectureSemanticPatch.BlockChange::id)
                .collect(Collectors.toSet());
        return ArchitectureSemanticPatch.between(base, target).stream()
                .anyMatch(x -> changed.contains(x.id()) && different.contains(x.id()));
    }

    private void validate(String source) {
        var model = new ArchitectureDslCommands().model(source);
        new ArchitectureDslCommands(catalogues.forModel(model), code -> catalogues.assignmentTarget(model, code))
                .validateReconciledSource(source);
        if (!new com.taxonomy.dsl.validation.DslValidator(catalogues).validate(model).isValid())
            throw new IllegalArgumentException("Invalid combined model");
    }

    private void publish(BranchAdoptionRule rule, BranchAdoptionTargets.Target target, Runnable authorize)
            throws IOException {
        var local = target.local();
        var head = local.directBranchHead(rule.branch);
        boolean published = rule.prepared.equals(head);
        if (!published && !Objects.equals(rule.targetBefore, head))
            throw stale();
        var c = rule.context();
        var workspace = WorkspaceResolver.workspaceContext(c);
        String candidateDsl = local.getDslAtCommit(rule.prepared, MAX_MODEL_BYTES);
        if (target.workspace() != null) {
            portfolio.requireAvailable();
            portfolio.lockedPublicationDigest(c.username(), workspace);
            String beforeDsl = local.getDslAtCommit(rule.targetBefore, MAX_MODEL_BYTES);
            if (!portfolio.matchesCheckpoint(beforeDsl, c.username(), workspace)
                    && !(published && portfolio.matchesCheckpoint(candidateDsl, c.username(), workspace)))
                throw new IllegalStateException("Uncommitted portfolio changes require a decision");
            portfolio.validatePortfolio(beforeDsl, candidateDsl, c.username(), workspace);
        }
        WorkspaceArchitectureVersionPort.GitAction<String> publish = () -> {
            authorize.run();
            // Once Git has committed, a retry may reconcile only that exact result.
            if (published)
                return local.verifyExpectedHead(rule.branch, rule.prepared);
            if (!rule.sourceTip.equals(target.source().directBranchHead(rule.sourceBranch)))
                throw stale();
            return local.publishDefaultCommit(rule.branch, rule.targetBefore, rule.prepared, rule.username,
                    "adopt " + rule.repositoryId + "/" + rule.sourceBranch + " " + rule.sourceCommit);
        };
        if (target.workspace() == null)
            publish.run();
        else {
            var row = target.workspace();
            if (row.getCurrentCommit() != null && !row.getCurrentCommit().equals(rule.targetBefore)
                    && !(published && rule.prepared.equals(row.getCurrentCommit())))
                throw stale();
            editor.applyPreparedWorkspaceRebase(c, rule.targetBefore, rule.prepared, "Adopt " + rule.sourceCommit,
                    publish);
            portfolio.materializePortfolio(candidateDsl, c.username(), workspace);
            row.setCurrentCommit(rule.prepared);
        }
        rule.targetAfter = rule.prepared;
        rule.prepared = null;
        rule.cursor = rule.sourceCommit;
        rule.nextCheck = 0;
        record(rule, "APPLIED", null, rule.principalId);
    }

    private boolean publicationReachedTarget(BranchAdoptionRule rule, BranchAdoptionTargets.Target target)
            throws IOException {
        var head = target.local().directBranchHead(rule.branch);
        return head != null && (rule.prepared.equals(head) || target.local().isAncestor(rule.prepared, head));
    }

    public void failed(Due observed, boolean denied) {
        var rule = locked(observed.id());
        if (rule != null && rule.revision == observed.revision() && rule.enabled)
            stop(rule, denied ? "AUTHORIZATION" : "PREREQUISITE",
                    denied ? "Current publishing authority must be reviewed"
                            : "Source, target, editor or portfolio changed; review before retrying");
    }

    private void stop(BranchAdoptionRule rule, String status, String detail) {
        rule.nextCheck = Long.MAX_VALUE;
        record(rule, status, detail, rule.principalId);
    }

    private void record(BranchAdoptionRule rule, String status, String detail, String actor) {
        rule.revision++;
        rule.status = status;
        rule.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 255));
        db.persist(new BranchAdoptionEvent(rule, actor));
    }

    private BranchAdoptionRule locked(String key) {
        return db.find(BranchAdoptionRule.class, key, LockModeType.PESSIMISTIC_WRITE);
    }

    private static void checkRevision(BranchAdoptionRule rule, long expected) {
        if ((rule == null ? 0 : rule.revision) != expected)
            throw stale();
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("The reviewed source, target or rule revision changed");
    }
}
