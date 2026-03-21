package com.taxonomy.versioning.service;

import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextComparison.DiffSummary;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.SemanticChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares two architecture contexts and produces a semantic diff.
 *
 * <p>Builds on the existing {@link DslGitRepository#diffBetween(String, String)}
 * infrastructure and enriches the result with human-readable change descriptions.
 */
@Service
public class ContextCompareService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompareService.class);

    private final DslGitRepository gitRepository;

    public ContextCompareService(DslGitRepositoryFactory repositoryFactory) {
        this.gitRepository = repositoryFactory.getSystemRepository();
    }

    /**
     * Compare two contexts identified by their commit IDs.
     *
     * @param left  the left (source/older) context
     * @param right the right (target/newer) context
     * @return the comparison result
     * @throws IOException if Git operations fail
     */
    public ContextComparison compareContexts(ContextRef left, ContextRef right) throws IOException {
        String leftCommit = resolveCommit(left);
        String rightCommit = resolveCommit(right);

        if (leftCommit == null || rightCommit == null) {
            return new ContextComparison(left, right,
                    new DiffSummary(0, 0, 0, 0, 0, 0),
                    List.of(), null);
        }

        ModelDiff diff = gitRepository.diffBetween(leftCommit, rightCommit);
        DiffSummary summary = buildDiffSummary(diff);
        List<SemanticChange> changes = buildSemanticChanges(diff);

        String rawDiff = null;
        try {
            rawDiff = gitRepository.textDiff(leftCommit, rightCommit);
        } catch (IOException e) {
            log.warn("Could not generate raw text diff: {}", e.getMessage());
        }

        return new ContextComparison(left, right, summary, changes, rawDiff);
    }

    /**
     * Compare two branches at their HEAD commits.
     *
     * @param left  the left context
     * @param right the right context
     * @return the comparison result
     * @throws IOException if Git operations fail
     */
    public ContextComparison compareBranches(ContextRef left, ContextRef right) throws IOException {
        ModelDiff diff = gitRepository.diffBranches(left.branch(), right.branch());
        DiffSummary summary = buildDiffSummary(diff);
        List<SemanticChange> changes = buildSemanticChanges(diff);

        String rawDiff = null;
        try {
            String leftCommit = gitRepository.getHeadCommit(left.branch());
            String rightCommit = gitRepository.getHeadCommit(right.branch());
            if (leftCommit != null && rightCommit != null) {
                rawDiff = gitRepository.textDiff(leftCommit, rightCommit);
            }
        } catch (Exception e) {
            log.debug("Could not generate raw diff for branch compare: {}", e.getMessage());
        }

        return new ContextComparison(left, right, summary, changes, rawDiff);
    }

    // ── Internal helpers ────────────────────────────────────────────

    DiffSummary buildDiffSummary(ModelDiff diff) {
        return new DiffSummary(
                diff.addedElements().size(),
                diff.changedElements().size(),
                diff.removedElements().size(),
                diff.addedRelations().size(),
                diff.changedRelations().size(),
                diff.removedRelations().size()
        );
    }

    List<SemanticChange> buildSemanticChanges(ModelDiff diff) {
        List<SemanticChange> changes = new ArrayList<>();

        for (ArchitectureElement el : diff.addedElements()) {
            changes.add(new SemanticChange(
                    "ADD", "ELEMENT", el.getId(),
                    "Element " + el.getId() + " added (" + el.getType() + ")",
                    null, el.getTitle()));
        }

        for (ArchitectureElement el : diff.removedElements()) {
            changes.add(new SemanticChange(
                    "REMOVE", "ELEMENT", el.getId(),
                    "Element " + el.getId() + " removed (" + el.getType() + ")",
                    el.getTitle(), null));
        }

        for (ModelDiff.ElementChange change : diff.changedElements()) {
            changes.add(new SemanticChange(
                    "MODIFY", "ELEMENT", change.after().getId(),
                    "Element " + change.after().getId() + " modified",
                    change.before().getTitle(),
                    change.after().getTitle()));
        }

        for (ArchitectureRelation rel : diff.addedRelations()) {
            String key = rel.getSourceId() + " " + rel.getRelationType() + " " + rel.getTargetId();
            changes.add(new SemanticChange(
                    "ADD", "RELATION", key,
                    "Relation added: " + key,
                    null, key));
        }

        for (ArchitectureRelation rel : diff.removedRelations()) {
            String key = rel.getSourceId() + " " + rel.getRelationType() + " " + rel.getTargetId();
            changes.add(new SemanticChange(
                    "REMOVE", "RELATION", key,
                    "Relation removed: " + key,
                    key, null));
        }

        for (ModelDiff.RelationChange change : diff.changedRelations()) {
            String key = change.after().getSourceId() + " "
                    + change.after().getRelationType() + " " + change.after().getTargetId();
            changes.add(new SemanticChange(
                    "MODIFY", "RELATION", key,
                    "Relation modified: " + key,
                    change.before().getStatus(),
                    change.after().getStatus()));
        }

        return changes;
    }

    private String resolveCommit(ContextRef ctx) {
        if (ctx.commitId() != null) {
            return ctx.commitId();
        }
        try {
            return gitRepository.getHeadCommit(ctx.branch());
        } catch (IOException e) {
            log.warn("Could not resolve HEAD for branch '{}': {}", ctx.branch(), e.getMessage());
            return null;
        }
    }
}
