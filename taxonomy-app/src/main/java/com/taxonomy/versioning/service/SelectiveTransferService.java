package com.taxonomy.versioning.service;

import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.TransferConflict;
import com.taxonomy.dto.TransferSelection;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Performs safe selective element/relation transfers between architecture commits. */
@Service
public class SelectiveTransferService {

    private static final Logger log = LoggerFactory.getLogger(SelectiveTransferService.class);

    private final DslGitRepositoryFactory repositoryFactory;
    private final ContextNavigationService contextNavigationService;
    private final WorkspaceResolver workspaceResolver;
    private final ConditionalDslCommitter conditionalCommitter = new ConditionalDslCommitter();
    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final ModelToAstMapper modelToAstMapper = new ModelToAstMapper();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public SelectiveTransferService(DslGitRepositoryFactory repositoryFactory,
                                    ContextNavigationService contextNavigationService,
                                    WorkspaceResolver workspaceResolver) {
        this.repositoryFactory = repositoryFactory;
        this.contextNavigationService = contextNavigationService;
        this.workspaceResolver = workspaceResolver;
    }

    private DslGitRepository resolveRepository(WorkspaceContext context) {
        return repositoryFactory.resolveRepository(Objects.requireNonNull(context, "context"));
    }

    /** Preview in the explicitly supplied workspace without modifying data. */
    public List<TransferConflict> previewTransfer(TransferSelection selection,
                                                  WorkspaceContext context) throws IOException {
        validateSelection(selection);
        DslGitRepository repository = resolveRepository(context);
        CanonicalArchitectureModel source = loadModel(repository, selection.sourceContextId());
        CanonicalArchitectureModel target = loadModel(repository, selection.targetContextId());
        return detectConflicts(source, target, selection);
    }

    /**
     * Backward-compatible preview for explicit non-request callers. Request code
     * must use the overload with a validated context.
     */
    public List<TransferConflict> previewTransfer(TransferSelection selection) throws IOException {
        return previewTransfer(selection, workspaceResolver.resolveCurrentContext());
    }

    /**
     * Apply the selected subset to the current branch only when its HEAD still
     * equals {@code targetContextId}. This makes preview/apply fail closed under
     * concurrent changes.
     */
    public String applyTransfer(TransferSelection selection) throws IOException {
        validateSelection(selection);
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        DslGitRepository repository = resolveRepository(context);
        String username = workspaceResolver.resolveCurrentUsername();
        String targetBranch = contextNavigationService.getCurrentContext(username).branch();
        if (targetBranch == null || targetBranch.isBlank()) {
            throw new IllegalStateException("No explicit target branch is active");
        }

        String actualHead = repository.getHeadCommit(targetBranch);
        if (!selection.targetContextId().equals(actualHead)) {
            throw new ConcurrentModificationException(
                    "Target branch '" + targetBranch + "' moved from "
                            + selection.targetContextId() + " to " + actualHead);
        }

        CanonicalArchitectureModel source = loadModel(repository, selection.sourceContextId());
        CanonicalArchitectureModel target = loadModel(repository, selection.targetContextId());
        List<TransferConflict> conflicts = detectConflicts(source, target, selection);
        if (selection.mode() == TransferSelection.TransferMode.MERGE_SELECTED
                && !conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "MERGE_SELECTED requires conflict resolution before apply");
        }

        mergeSelected(source, target, selection);
        String targetDsl = repository.getDslAtCommit(selection.targetContextId());
        var originalDocument = parser.parse(targetDsl);
        String namespace = originalDocument.getMeta() != null
                ? originalDocument.getMeta().namespace() : "default";
        String mergedDsl = serializer.serialize(modelToAstMapper.toDocument(target, namespace));

        String commitId = conditionalCommitter.commit(
                repository,
                targetBranch,
                selection.targetContextId(),
                mergedDsl,
                context.username(),
                "Selective " + selection.mode() + ": "
                        + selection.selectedElementIds().size() + " elements, "
                        + selection.selectedRelationIds().size() + " relations");

        log.info("Selective transfer applied by '{}' in workspace '{}': mode={}, branch={}, commit={}",
                context.username(), context.workspaceId(), selection.mode(), targetBranch,
                commitId.substring(0, Math.min(7, commitId.length())));
        return commitId;
    }

    private void mergeSelected(CanonicalArchitectureModel source,
                               CanonicalArchitectureModel target,
                               TransferSelection selection) {
        Map<String, ArchitectureElement> targetElements = target.getElements().stream()
                .collect(Collectors.toMap(ArchitectureElement::getId, Function.identity()));
        for (ArchitectureElement element : source.getElements()) {
            if (selection.selectedElementIds().contains(element.getId())) {
                targetElements.put(element.getId(), element);
            }
        }

        Map<String, ArchitectureRelation> targetRelations = target.getRelations().stream()
                .collect(Collectors.toMap(this::relationKey, Function.identity()));
        for (ArchitectureRelation relation : source.getRelations()) {
            String key = relationKey(relation);
            if (selection.selectedRelationIds().contains(key)) {
                targetRelations.put(key, relation);
            }
        }

        target.getElements().clear();
        target.getElements().addAll(targetElements.values());
        target.getRelations().clear();
        target.getRelations().addAll(targetRelations.values());
    }

    private void validateSelection(TransferSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("Transfer selection is required");
        }
        if (selection.sourceContextId() == null || selection.sourceContextId().isBlank()
                || selection.targetContextId() == null || selection.targetContextId().isBlank()) {
            throw new IllegalArgumentException("Source and target commit IDs are required");
        }
        if (selection.sourceContextId().equals(selection.targetContextId())) {
            throw new IllegalArgumentException("Source and target commits must differ");
        }
        if (selection.mode() == null) {
            throw new IllegalArgumentException("Transfer mode is required");
        }
        Set<String> elementIds = selection.selectedElementIds();
        Set<String> relationIds = selection.selectedRelationIds();
        if (elementIds == null || relationIds == null) {
            throw new IllegalArgumentException("Selected ID sets are required");
        }
        if (elementIds.isEmpty() && relationIds.isEmpty()) {
            throw new IllegalArgumentException("At least one element or relation must be selected");
        }
    }

    private CanonicalArchitectureModel loadModel(DslGitRepository repository, String commitId)
            throws IOException {
        String dsl = repository.getDslAtCommit(commitId);
        if (dsl == null) {
            throw new IOException("No DSL content at commit: " + commitId);
        }
        return astMapper.map(parser.parse(dsl));
    }

    List<TransferConflict> detectConflicts(
            CanonicalArchitectureModel sourceModel,
            CanonicalArchitectureModel targetModel,
            TransferSelection selection) {
        List<TransferConflict> conflicts = new ArrayList<>();

        Map<String, ArchitectureElement> targetElements = targetModel.getElements().stream()
                .collect(Collectors.toMap(ArchitectureElement::getId, Function.identity()));
        for (ArchitectureElement sourceElement : sourceModel.getElements()) {
            if (!selection.selectedElementIds().contains(sourceElement.getId())) {
                continue;
            }
            ArchitectureElement existing = targetElements.get(sourceElement.getId());
            if (existing != null && !elementsEqual(existing, sourceElement)) {
                conflicts.add(new TransferConflict(
                        sourceElement.getId(),
                        existing.getTitle(),
                        sourceElement.getTitle(),
                        findViewsReferencing(targetModel, sourceElement.getId())));
            }
        }

        Map<String, ArchitectureRelation> targetRelations = targetModel.getRelations().stream()
                .collect(Collectors.toMap(this::relationKey, Function.identity()));
        for (ArchitectureRelation sourceRelation : sourceModel.getRelations()) {
            String key = relationKey(sourceRelation);
            if (!selection.selectedRelationIds().contains(key)) {
                continue;
            }
            ArchitectureRelation existing = targetRelations.get(key);
            if (existing != null && !relationsEqual(existing, sourceRelation)) {
                conflicts.add(new TransferConflict(
                        key,
                        existing.getStatus(),
                        sourceRelation.getStatus(),
                        List.of()));
            }
        }
        return conflicts;
    }

    private boolean elementsEqual(ArchitectureElement left, ArchitectureElement right) {
        return Objects.equals(left.getTitle(), right.getTitle())
                && Objects.equals(left.getDescription(), right.getDescription())
                && Objects.equals(left.getType(), right.getType());
    }

    private boolean relationsEqual(ArchitectureRelation left, ArchitectureRelation right) {
        return Objects.equals(left.getStatus(), right.getStatus())
                && Objects.equals(left.getConfidence(), right.getConfidence());
    }

    private String relationKey(ArchitectureRelation relation) {
        return relation.getSourceId() + " " + relation.getRelationType() + " "
                + relation.getTargetId();
    }

    private List<String> findViewsReferencing(CanonicalArchitectureModel model, String elementId) {
        return model.getViews().stream()
                .filter(view -> view.getIncludes() != null
                        && view.getIncludes().contains(elementId))
                .map(view -> view.getTitle())
                .limit(5)
                .toList();
    }
}
