package com.taxonomy.versioning.service;

import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.TransferConflict;
import com.taxonomy.dto.TransferSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.taxonomy.workspace.service.WorkspaceResolver;

/**
 * Performs selective element/relation transfers between architecture contexts.
 *
 * <p>Unlike a full Git merge or cherry-pick, this service allows the user to
 * pick individual elements and relations from one version and apply them to
 * another. Conflict detection and preview are provided before any changes
 * are committed.
 */
@Service
public class SelectiveTransferService {

    private static final Logger log = LoggerFactory.getLogger(SelectiveTransferService.class);

    private final DslGitRepository gitRepository;
    private final ContextNavigationService contextNavigationService;
    private final WorkspaceResolver workspaceResolver;
    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public SelectiveTransferService(DslGitRepositoryFactory repositoryFactory,
                                    ContextNavigationService contextNavigationService,
                                    WorkspaceResolver workspaceResolver) {
        this.gitRepository = repositoryFactory.getSystemRepository();
        this.contextNavigationService = contextNavigationService;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Preview a selective transfer without modifying any data.
     *
     * <p>Returns the list of conflicts that would occur if the transfer
     * were applied.
     *
     * @param selection the transfer selection
     * @return list of conflicts (empty if no conflicts)
     * @throws IOException if Git operations fail
     */
    public List<TransferConflict> previewTransfer(TransferSelection selection) throws IOException {
        CanonicalArchitectureModel sourceModel = loadModel(selection.sourceContextId());
        CanonicalArchitectureModel targetModel = loadModel(selection.targetContextId());

        return detectConflicts(sourceModel, targetModel, selection);
    }

    /**
     * Apply a selective transfer, merging selected elements and relations
     * from the source context into the target context.
     *
     * @param selection the transfer selection
     * @return the commit ID of the resulting commit
     * @throws IOException if Git operations fail
     */
    public String applyTransfer(TransferSelection selection) throws IOException {
        CanonicalArchitectureModel sourceModel = loadModel(selection.sourceContextId());
        CanonicalArchitectureModel targetModel = loadModel(selection.targetContextId());

        // Merge selected elements
        Map<String, ArchitectureElement> targetElements = targetModel.getElements().stream()
                .collect(Collectors.toMap(ArchitectureElement::getId, Function.identity()));

        for (ArchitectureElement sourceEl : sourceModel.getElements()) {
            if (selection.selectedElementIds().contains(sourceEl.getId())) {
                targetElements.put(sourceEl.getId(), sourceEl);
            }
        }

        // Merge selected relations
        Map<String, ArchitectureRelation> targetRelations = targetModel.getRelations().stream()
                .collect(Collectors.toMap(this::relationKey, Function.identity()));

        for (ArchitectureRelation sourceRel : sourceModel.getRelations()) {
            String key = relationKey(sourceRel);
            if (selection.selectedRelationIds().contains(key)) {
                targetRelations.put(key, sourceRel);
            }
        }

        // Build merged model
        targetModel.getElements().clear();
        targetModel.getElements().addAll(targetElements.values());
        targetModel.getRelations().clear();
        targetModel.getRelations().addAll(targetRelations.values());

        // Serialize back to DSL and commit
        var doc = parser.parse(gitRepository.getDslAtCommit(selection.targetContextId()));
        String mergedDsl = serializer.serialize(doc);

        String targetBranch = contextNavigationService.getCurrentContext(
                workspaceResolver.resolveCurrentUsername()).branch();
        String commitId = gitRepository.commitDsl(
                targetBranch, mergedDsl, "system",
                "Selective transfer: " + selection.selectedElementIds().size() + " elements, "
                        + selection.selectedRelationIds().size() + " relations");

        log.info("Selective transfer applied: {} elements, {} relations → commit '{}'",
                selection.selectedElementIds().size(),
                selection.selectedRelationIds().size(),
                commitId.substring(0, Math.min(7, commitId.length())));

        return commitId;
    }

    // ── Internal helpers ────────────────────────────────────────────

    private CanonicalArchitectureModel loadModel(String commitId) throws IOException {
        String dsl = gitRepository.getDslAtCommit(commitId);
        if (dsl == null) {
            throw new IOException("No DSL content at commit: " + commitId);
        }
        var doc = parser.parse(dsl);
        return astMapper.map(doc);
    }

    List<TransferConflict> detectConflicts(
            CanonicalArchitectureModel sourceModel,
            CanonicalArchitectureModel targetModel,
            TransferSelection selection) {

        List<TransferConflict> conflicts = new ArrayList<>();

        Map<String, ArchitectureElement> targetElements = targetModel.getElements().stream()
                .collect(Collectors.toMap(ArchitectureElement::getId, Function.identity()));

        for (ArchitectureElement sourceEl : sourceModel.getElements()) {
            if (!selection.selectedElementIds().contains(sourceEl.getId())) {
                continue;
            }
            ArchitectureElement existing = targetElements.get(sourceEl.getId());
            if (existing != null && !elementsEqual(existing, sourceEl)) {
                conflicts.add(new TransferConflict(
                        sourceEl.getId(),
                        existing.getTitle(),
                        sourceEl.getTitle(),
                        findViewsReferencing(targetModel, sourceEl.getId())));
            }
        }

        Map<String, ArchitectureRelation> targetRelations = targetModel.getRelations().stream()
                .collect(Collectors.toMap(this::relationKey, Function.identity()));

        for (ArchitectureRelation sourceRel : sourceModel.getRelations()) {
            String key = relationKey(sourceRel);
            if (!selection.selectedRelationIds().contains(key)) {
                continue;
            }
            ArchitectureRelation existing = targetRelations.get(key);
            if (existing != null && !relationsEqual(existing, sourceRel)) {
                conflicts.add(new TransferConflict(
                        key,
                        existing.getStatus(),
                        sourceRel.getStatus(),
                        List.of()));
            }
        }

        return conflicts;
    }

    private boolean elementsEqual(ArchitectureElement a, ArchitectureElement b) {
        return java.util.Objects.equals(a.getTitle(), b.getTitle())
            && java.util.Objects.equals(a.getDescription(), b.getDescription())
            && java.util.Objects.equals(a.getType(), b.getType());
    }

    private boolean relationsEqual(ArchitectureRelation a, ArchitectureRelation b) {
        return java.util.Objects.equals(a.getStatus(), b.getStatus())
            && java.util.Objects.equals(a.getConfidence(), b.getConfidence());
    }

    private String relationKey(ArchitectureRelation rel) {
        return rel.getSourceId() + " " + rel.getRelationType() + " " + rel.getTargetId();
    }

    private List<String> findViewsReferencing(CanonicalArchitectureModel model, String elementId) {
        return model.getViews().stream()
                .filter(v -> {
                    List<String> includes = v.getIncludes();
                    return includes != null && includes.contains(elementId);
                })
                .map(v -> v.getTitle())
                .limit(5)
                .toList();
    }
}
