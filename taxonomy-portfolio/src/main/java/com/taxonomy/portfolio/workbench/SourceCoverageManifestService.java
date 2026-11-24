package com.taxonomy.portfolio.workbench;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.workbench.SourceCoverageManifest.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.taxonomy.portfolio.workbench.SourceCoverageManifest.GapCode.*;
import static com.taxonomy.portfolio.workbench.SourceCoverageManifest.GapState.MISSING;
import static com.taxonomy.portfolio.workbench.SourceCoverageManifest.GapState.STALE;
import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.ANCESTOR;
import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.SAME;

/** Resolves source and review evidence against an exact, authorized Git checkpoint. */
@Service
public class SourceCoverageManifestService {
    private final PortfolioAnalysisPersistenceService snapshots;
    private final ProjectPortfolioService projects;
    private final WorkspaceDslReadPort repositories;
    private final PortfolioJsonCodec json;

    public SourceCoverageManifestService(PortfolioAnalysisPersistenceService snapshots,
                                         ProjectPortfolioService projects,
                                         WorkspaceDslReadPort repositories, PortfolioJsonCodec json) {
        this.snapshots = snapshots;
        this.projects = projects;
        this.repositories = repositories;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public SourceCoverageManifest load(Long projectId, String snapshotId, String requestedCommit,
                                       RepositoryContext selected) {
        var workspace = WorkspaceResolver.workspaceContext(selected);
        var detail = snapshots.getSnapshot(projectId, snapshotId, selected.username(), workspace);
        var summary = detail.summary();
        if (!projectId.equals(summary.projectId()) || !snapshotId.equals(summary.id())
                || !Objects.equals(summary.workspaceId(), selected.workspaceId())
                || present(summary.branchName()) && !summary.branchName().equals(selected.branch())) {
            throw PortfolioException.notFound("Analysis snapshot not found in the selected source");
        }

        String projectKey = projects.getProject(projectId, selected.username(), workspace).projectKey();
        var version = projects.findRequirementVersion(projectId, summary.requirementId(), summary.requirementVersionId(),
                selected.username(), workspace).orElse(null);
        var gaps = new ArrayList<Gap>();
        var snapshot = snapshotEvidence(detail, gaps);
        var checkpoint = readCheckpoint(summary, requestedCommit, selected, gaps);
        List<Passage> passages = List.of();
        List<Decision> decisions = List.of();
        String semanticRequirement = null;
        boolean versionRetained = version != null && version.id().equals(summary.requirementVersionId())
                && version.versionNumber() == summary.requirementVersionNumber();
        if (!versionRetained) {
            gaps.add(new Gap(MISSING, REQUIREMENT_VERSION_UNAVAILABLE, string(summary.requirementVersionId()), null));
        } else {
            passages = passages(version, gaps);
            if (checkpoint.blocks() != null) {
                semanticRequirement = semanticRequirement(projectKey, summary.requirementKey(), version, checkpoint.blocks(), gaps);
                if (semanticRequirement != null) {
                    decisions = decisions(snapshot, semanticRequirement, checkpoint.blocks(), gaps);
                }
            }
        }
        return new SourceCoverageManifest(1,
                new Scope(selected.repositoryId(), summary.workspaceId(), summary.branchName(), projectId, projectKey),
                new RequirementVersion(summary.requirementId(), summary.requirementKey(), summary.requirementVersionId(),
                        summary.requirementVersionNumber(), versionRetained ? version.contentHash() : null, semanticRequirement),
                snapshot,
                checkpoint.evidence(), passages, decisions, gaps);
    }

    private CheckpointRead readCheckpoint(SnapshotSummary snapshot, String requested, RepositoryContext context,
                                          List<Gap> gaps) {
        var selection = requested == null ? CheckpointSelection.ANALYSIS_RECORD : CheckpointSelection.EXPLICIT;
        String candidate = requested == null ? snapshot.commitSha() : requested;
        if (!present(candidate) && requested == null) {
            return unavailable(selection, null, MISSING, CHECKPOINT_NOT_RECORDED, gaps);
        }
        final String commit;
        try {
            commit = WorkspaceDslReadPort.normalizeCommitId(candidate);
        } catch (IllegalArgumentException invalid) {
            if (requested != null) throw PortfolioException.validation("checkpoint must be a full Git object ID");
            return unavailable(selection, candidate, STALE, CHECKPOINT_INVALID_ID, gaps);
        }
        if (!present(snapshot.branchName())) {
            return unavailable(selection, commit, MISSING, CHECKPOINT_SCOPE_NOT_RECORDED, gaps);
        }
        try {
            var repository = repositories.openRead(context);
            // A branch tip is used only to prove visibility for the selected commit.
            String head = repository.currentHead();
            if (head != null) {
                var relationship = repository.relationshipsTo(head, List.of(commit)).getFirst();
                if (relationship == SAME || relationship == ANCESTOR) {
                    var document = repository.dslAtCommit(commit);
                    if (document.isEmpty()) return unavailable(selection, commit, MISSING, CHECKPOINT_DOCUMENT_MISSING, gaps);
                    return new CheckpointRead(new Checkpoint(selection, commit, StableIdentityHash.sha256(document.get())),
                            ArchitectureSemanticPatch.index(document.get()));
                }
            }
        } catch (CommandProblem ambiguous) {
            return unavailable(selection, commit, STALE, CHECKPOINT_DOCUMENT_AMBIGUOUS, gaps);
        } catch (IOException unavailable) {
            return unavailable(selection, commit, MISSING, CHECKPOINT_UNAVAILABLE, gaps);
        }
        return unavailable(selection, commit, MISSING, CHECKPOINT_UNAVAILABLE, gaps);
    }

    private static CheckpointRead unavailable(CheckpointSelection selection, String commit, GapState state,
                                              GapCode code, List<Gap> gaps) {
        gaps.add(new Gap(state, code, commit, null));
        return new CheckpointRead(new Checkpoint(selection, commit, null), null);
    }

    private static List<Passage> passages(RequirementVersionView version, List<Gap> gaps) {
        SourceReference source = version.source();
        List<Long> fragmentIds = source == null ? List.of() : fragments(source);
        String reference = version.id().toString();
        if (source == null || fragmentIds.isEmpty() && !present(source.originalText())) {
            gaps.add(new Gap(MISSING, SOURCE_PASSAGE_NOT_RECORDED, reference, null));
            return List.of();
        }
        if (source.sourceArtifactId() == null || source.sourceVersionId() == null) {
            gaps.add(new Gap(MISSING, SOURCE_VERSION_NOT_RECORDED, reference, null));
        }
        if (!fragmentIds.isEmpty()) {
            return fragmentIds.stream().map(id -> new Passage(PassageEvidence.RECORDED_FRAGMENT,
                    source.sourceArtifactId(), source.sourceVersionId(), id, source.sectionReference(), source.pageNumber(), null)).toList();
        }
        if (!present(source.sectionReference()) && source.pageNumber() == null) {
            gaps.add(new Gap(MISSING, SOURCE_PASSAGE_LOCATOR_NOT_RECORDED, reference, null));
        }
        return List.of(new Passage(PassageEvidence.RECORDED_ORIGINAL, source.sourceArtifactId(), source.sourceVersionId(),
                null, source.sectionReference(), source.pageNumber(), StableIdentityHash.sha256(source.originalText())));
    }

    private String semanticRequirement(String project, String requirement, RequirementVersionView version,
                                       Map<String, BlockAst> blocks, List<Gap> gaps) {
        String versionKey = PortfolioGitService.VERSION_BLOCK + ":" + project + " " + requirement + " " + version.versionNumber();
        var recordedVersion = blocks.get(versionKey);
        if (recordedVersion == null) {
            gaps.add(new Gap(MISSING, REQUIREMENT_VERSION_NOT_CHECKPOINTED, versionKey, null));
            return null;
        }
        if (!matchesProperty(recordedVersion, PortfolioGitService.MANAGED_PROPERTY, "true")
                || !matchesVersion(recordedVersion, "contentHash", version)) {
            gaps.add(new Gap(STALE, REQUIREMENT_VERSION_MISMATCH, versionKey, recordedVersion.property("contentHash")));
            return null;
        }
        if (!matchesPassages(recordedVersion, version.source())) {
            gaps.add(new Gap(STALE, SOURCE_PASSAGE_MISMATCH, versionKey, null));
        }

        var matches = blocks.values().stream().filter(block -> "requirement".equals(block.getKind())
                && matchesProperty(block, "x-project-key", project) && matchesProperty(block, "x-requirement-key", requirement)).toList();
        if (matches.isEmpty()) {
            gaps.add(new Gap(MISSING, SEMANTIC_REQUIREMENT_NOT_CHECKPOINTED, versionKey, null));
            return null;
        }
        if (matches.size() != 1) {
            gaps.add(new Gap(STALE, SEMANTIC_REQUIREMENT_AMBIGUOUS, versionKey, null));
            return null;
        }
        var block = matches.getFirst();
        String id = block.getHeaderTokens().getFirst();
        if (!matchesProperty(block, PortfolioGitService.MANAGED_PROPERTY, "true")
                || !matchesProperty(block, "x-version", Integer.toString(version.versionNumber()))
                || !matchesVersion(block, "x-content-hash", version)) {
            gaps.add(new Gap(STALE, SEMANTIC_REQUIREMENT_VERSION_MISMATCH, id, block.property("x-version")));
            return null;
        }
        return id;
    }

    private boolean matchesPassages(BlockAst block, SourceReference source) {
        try {
            if (block.propertyValues("sourceFragmentIds").size() > 1) return false;
            var checkpointFragments = json.readLongList(block.property("sourceFragmentIds")).stream().sorted().toList();
            if (source == null) {
                return checkpointFragments.isEmpty() && List.of("sourceArtifactId", "sourceVersionId", "sectionReference", "pageNumber", "originalText")
                        .stream().allMatch(property -> block.property(property) == null);
            }
            return matchesProperty(block, "sourceArtifactId", string(source.sourceArtifactId()))
                    && matchesProperty(block, "sourceVersionId", string(source.sourceVersionId()))
                    && checkpointFragments.equals(fragments(source))
                    && matchesProperty(block, "sectionReference", source.sectionReference())
                    && matchesProperty(block, "pageNumber", string(source.pageNumber()))
                    // Current-only projections may keep the original passage only in the immutable version row.
                    && (block.property("originalText") == null || matchesProperty(block, "originalText", source.originalText()));
        } catch (PortfolioException malformed) {
            return false;
        }
    }

    private static boolean matchesVersion(BlockAst block, String hashProperty, RequirementVersionView version) {
        return version.contentHash() != null && matchesProperty(block, hashProperty, version.contentHash())
                && matchesProperty(block, "text", version.text()) && version.text() != null
                && version.contentHash().equals(StableIdentityHash.sha256(version.text()));
    }

    private static boolean matchesProperty(BlockAst block, String property, String expected) {
        return block.propertyValues(property).size() <= 1 && Objects.equals(block.property(property), expected);
    }

    private static Snapshot snapshotEvidence(SnapshotDetail detail, List<Gap> gaps) {
        var summary = detail.summary();
        var view = detail.analysis() == null ? null : detail.analysis().getArchitectureView();
        var elements = view == null || view.getIncludedElements() == null ? List.<String>of()
                : view.getIncludedElements().stream().map(RequirementElementView::getNodeCode)
                        .filter(SourceCoverageManifestService::present).distinct().sorted().toList();
        var relations = view == null || view.getIncludedRelationships() == null ? List.<String>of()
                : view.getIncludedRelationships().stream()
                        .filter(relation -> present(relation.getSourceCode()) && present(relation.getTargetCode()) && present(relation.getRelationType()))
                        .map(relation -> "relation:" + relation.getSourceCode() + " " + relation.getRelationType() + " " + relation.getTargetCode())
                        .distinct().sorted().toList();
        if (elements.isEmpty()) gaps.add(new Gap(MISSING, SNAPSHOT_ARCHITECTURE_NOT_RECORDED, summary.id(), null));
        return new Snapshot(summary.id(), summary.status(), summary.provider(), summary.commitSha(),
                summary.taxonomyFingerprint(), elements, relations);
    }

    private static List<Decision> decisions(Snapshot snapshot, String requirement,
                                             Map<String, BlockAst> blocks, List<Gap> gaps) {
        var decisions = new ArrayList<Decision>();
        for (String target : snapshot.candidateArchitectureIds()) {
            String key = "mapping:" + requirement + " -> " + target;
            var mapping = blocks.get(key);
            if (mapping == null || !matchesProperty(mapping, PortfolioGitService.MANAGED_PROPERTY, "true")) {
                gaps.add(new Gap(MISSING, DECISION_NOT_CHECKPOINTED, key, null));
            } else if (!matchesProperty(mapping, "source", snapshot.id())) {
                gaps.add(new Gap(STALE, DECISION_SNAPSHOT_MISMATCH, key, mapping.property("source")));
            } else {
                var decision = reviewedDecision(mapping, key, target);
                if (decision == null) {
                    gaps.add(new Gap(MISSING, DECISION_EVIDENCE_INCOMPLETE, key, mapping.property("x-review-status")));
                } else if (!blocks.containsKey("element:" + target)) {
                    gaps.add(new Gap(MISSING, SEMANTIC_ELEMENT_NOT_CHECKPOINTED, target, key));
                } else {
                    decisions.add(decision);
                }
            }
        }
        // The portable portfolio currently checkpoints element mapping reviews only.
        snapshot.candidateRelationKeys().forEach(relation -> gaps.add(new Gap(MISSING, RELATION_DECISION_NOT_CHECKPOINTED, relation, null)));
        return decisions;
    }

    private static Decision reviewedDecision(BlockAst mapping, String key, String target) {
        String status = mapping.property("x-review-status");
        String actor = mapping.property("x-decision-by");
        String decidedAt = mapping.property("x-decision-at");
        if (!"CONFIRMED".equals(status) && !"REJECTED".equals(status) || !present(actor) || !present(decidedAt)
                || !matchesProperty(mapping, "x-review-status", status) || !matchesProperty(mapping, "x-decision-by", actor)
                || !matchesProperty(mapping, "x-decision-at", decidedAt)) return null;
        try {
            return new Decision(key, target, ReviewStatus.valueOf(status), mapping.property("x-action-status"), actor, Instant.parse(decidedAt));
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    private static List<Long> fragments(SourceReference source) {
        return source.sourceFragmentIds() == null ? List.of() : source.sourceFragmentIds().stream().distinct().sorted().toList();
    }

    private static String string(Object value) { return Objects.toString(value, null); }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private record CheckpointRead(Checkpoint evidence, Map<String, BlockAst> blocks) {}
}
