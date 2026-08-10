package com.taxonomy.dsl.export;

import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.diff.ModelDiffer;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.validation.DslValidationResult;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Materializes a parsed DSL document into repository-scoped relational projections.
 *
 * <p>The materialization pipeline parses and validates the DSL, creates accepted
 * {@link TaxonomyRelation} projections or provisional {@link RelationHypothesis}
 * projections in the exact selected repository/workspace, stores the source
 * document and updates projection-state diagnostics.</p>
 */
@Service
public class DslMaterializeService {

    private static final Logger log = LoggerFactory.getLogger(DslMaterializeService.class);
    private static final String TEST_REPOSITORY_ID = "test-primary";

    private final TaxonomyRelationService relationService;
    private final RelationHypothesisRepository hypothesisRepository;
    private final ArchitectureDslDocumentRepository documentRepository;
    @Nullable
    private final RepositoryStateService repositoryStateService;
    @Nullable
    private final WorkspaceResolver workspaceResolver;
    @Nullable
    private final WorkspaceContextResolver contextResolver;

    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();
    private final ModelDiffer differ = new ModelDiffer();

    @Autowired
    public DslMaterializeService(TaxonomyRelationService relationService,
                                 RelationHypothesisRepository hypothesisRepository,
                                 ArchitectureDslDocumentRepository documentRepository,
                                 @Nullable RepositoryStateService repositoryStateService,
                                 @Nullable WorkspaceResolver workspaceResolver,
                                 @Nullable WorkspaceContextResolver contextResolver) {
        this.relationService = relationService;
        this.hypothesisRepository = hypothesisRepository;
        this.documentRepository = documentRepository;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.contextResolver = contextResolver;
    }

    /**
     * Compatibility constructor for focused unit tests that still provide only
     * the historic workspace resolver. Productive Spring wiring uses the
     * six-argument constructor and therefore the request-stable
     * {@link RepositoryContext} path.
     */
    public DslMaterializeService(TaxonomyRelationService relationService,
                                 RelationHypothesisRepository hypothesisRepository,
                                 ArchitectureDslDocumentRepository documentRepository,
                                 @Nullable RepositoryStateService repositoryStateService,
                                 @Nullable WorkspaceContextResolver contextResolver) {
        this(relationService, hypothesisRepository, documentRepository,
                repositoryStateService, null, contextResolver);
    }

    public record MaterializeResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            int relationsCreated,
            int hypothesesCreated,
            Long documentId
    ) {}

    /** Parse, validate and materialize a complete DSL document. */
    @Transactional
    public MaterializeResult materialize(
            String dslText, String path, String branch, String commitId) {
        var documentAst = parser.parse(dslText, path);
        CanonicalArchitectureModel model = astMapper.map(documentAst);

        DslValidationResult validation = validator.validate(model);
        if (!validation.isValid()) {
            return new MaterializeResult(false, validation.getErrors(), validation.getWarnings(),
                    0, 0, null);
        }

        RepositoryContext context = currentRepositoryContext();
        int relationsCreated = 0;
        int hypothesesCreated = 0;

        for (ArchitectureRelation relation : model.getRelations()) {
            String status = relation.getStatus() != null
                    ? relation.getStatus().toLowerCase() : "accepted";

            if ("accepted".equals(status)) {
                try {
                    RelationType type = RelationType.valueOf(relation.getRelationType());
                    relationService.createRelationInContext(
                            relation.getSourceId(), relation.getTargetId(), type,
                            "Materialized from DSL", "dsl-materialize", context);
                    relationsCreated++;
                } catch (IllegalArgumentException error) {
                    log.warn("Skipped relation {} → {}: {}",
                            relation.getSourceId(), relation.getTargetId(), error.getMessage());
                }
            } else if ("proposed".equals(status) || "provisional".equals(status)) {
                try {
                    hypothesisRepository.save(toHypothesis(
                            relation,
                            "proposed".equals(status)
                                    ? HypothesisStatus.PROPOSED
                                    : HypothesisStatus.PROVISIONAL,
                            "dsl-materialize",
                            context));
                    hypothesesCreated++;
                } catch (IllegalArgumentException error) {
                    log.warn("Skipped hypothesis {} → {}: {}",
                            relation.getSourceId(), relation.getTargetId(), error.getMessage());
                }
            }
        }

        String namespace = documentAst.getMeta() != null
                ? documentAst.getMeta().namespace() : null;
        String dslVersion = documentAst.getMeta() != null
                ? documentAst.getMeta().version() : null;

        ArchitectureDslDocument document = new ArchitectureDslDocument();
        document.setPath(path != null ? path : "inline");
        document.setBranch(branch);
        document.setCommitId(commitId);
        document.setNamespace(namespace);
        document.setDslVersion(dslVersion);
        document.setRawContent(dslText);

        ArchitectureDslDocument saved = documentRepository.save(document);

        log.info("Materialized DSL document '{}' in repository {} workspace {}: "
                        + "{} relations, {} hypotheses",
                path, context.repositoryId(), context.workspaceId(),
                relationsCreated, hypothesesCreated);

        if (repositoryStateService != null && commitId != null) {
            repositoryStateService.recordProjection(context.username(), commitId, branch);
        }

        return new MaterializeResult(true, List.of(), validation.getWarnings(),
                relationsCreated, hypothesesCreated, saved.getId());
    }

    public ModelDiff diffDocuments(Long beforeDocId, Long afterDocId) {
        CanonicalArchitectureModel before = null;
        if (beforeDocId != null) {
            ArchitectureDslDocument beforeDocument = documentRepository.findById(beforeDocId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Before document not found: " + beforeDocId));
            before = parseToModel(
                    beforeDocument.getRawContent(), beforeDocument.getPath());
        }

        ArchitectureDslDocument afterDocument = documentRepository.findById(afterDocId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "After document not found: " + afterDocId));
        CanonicalArchitectureModel after = parseToModel(
                afterDocument.getRawContent(), afterDocument.getPath());

        return differ.diff(before, after);
    }

    /** Materialize only the delta between two stored DSL versions. */
    @Transactional
    public MaterializeResult materializeIncremental(Long beforeDocId, Long afterDocId) {
        ModelDiff diff = diffDocuments(beforeDocId, afterDocId);

        int relationsCreated = 0;
        int hypothesesCreated = 0;
        List<String> warnings = new ArrayList<>();
        RepositoryContext context = currentRepositoryContext();

        for (ArchitectureRelation relation : diff.addedRelations()) {
            String status = relation.getStatus() != null
                    ? relation.getStatus().toLowerCase() : "accepted";
            if ("accepted".equals(status)) {
                try {
                    RelationType type = RelationType.valueOf(relation.getRelationType());
                    relationService.createRelationInContext(
                            relation.getSourceId(), relation.getTargetId(), type,
                            "Materialized incrementally from DSL", "dsl-incremental", context);
                    relationsCreated++;
                } catch (IllegalArgumentException error) {
                    warnings.add("Skipped relation " + relation.getSourceId() + " → "
                            + relation.getTargetId() + ": " + error.getMessage());
                }
            } else if ("proposed".equals(status) || "provisional".equals(status)) {
                try {
                    hypothesisRepository.save(toHypothesis(
                            relation,
                            "proposed".equals(status)
                                    ? HypothesisStatus.PROPOSED
                                    : HypothesisStatus.PROVISIONAL,
                            "dsl-incremental",
                            context));
                    hypothesesCreated++;
                } catch (IllegalArgumentException error) {
                    warnings.add("Skipped hypothesis " + relation.getSourceId() + " → "
                            + relation.getTargetId() + ": " + error.getMessage());
                }
            }
        }

        for (ModelDiff.RelationChange change : diff.changedRelations()) {
            String newStatus = change.after().getStatus() != null
                    ? change.after().getStatus().toLowerCase() : "accepted";
            String oldStatus = change.before().getStatus() != null
                    ? change.before().getStatus().toLowerCase() : "accepted";

            if ("accepted".equals(newStatus) && !newStatus.equals(oldStatus)) {
                try {
                    RelationType type = RelationType.valueOf(
                            change.after().getRelationType());
                    relationService.createRelationInContext(
                            change.after().getSourceId(),
                            change.after().getTargetId(),
                            type,
                            "Promoted from " + oldStatus + " via DSL",
                            "dsl-incremental",
                            context);
                    relationsCreated++;
                } catch (IllegalArgumentException error) {
                    warnings.add("Skipped promotion " + change.after().getSourceId()
                            + " → " + change.after().getTargetId() + ": "
                            + error.getMessage());
                }
            }
        }

        log.info("Incremental materialization in repository {} workspace {}: "
                        + "{} relations, {} hypotheses, {} changes total",
                context.repositoryId(), context.workspaceId(),
                relationsCreated, hypothesesCreated, diff.totalChanges());

        return new MaterializeResult(true, List.of(), warnings,
                relationsCreated, hypothesesCreated, afterDocId);
    }

    private RelationHypothesis toHypothesis(
            ArchitectureRelation relation,
            HypothesisStatus status,
            String sessionId,
            RepositoryContext context) {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setRepositoryId(context.repositoryId());
        hypothesis.setSourceNodeId(relation.getSourceId());
        hypothesis.setTargetNodeId(relation.getTargetId());
        hypothesis.setRelationType(RelationType.valueOf(relation.getRelationType()));
        hypothesis.setStatus(status);
        hypothesis.setConfidence(relation.getConfidence());
        hypothesis.setAnalysisSessionId(sessionId);
        hypothesis.setWorkspaceId(context.workspaceId());
        hypothesis.setOwnerUsername(context.username());
        return hypothesis;
    }

    private RepositoryContext currentRepositoryContext() {
        if (workspaceResolver != null) {
            return workspaceResolver.resolveCurrentRepositoryContext();
        }

        // Isolated unit tests may construct this service without the request
        // resolver. Keep that compatibility explicitly test-local rather than
        // silently using the catalog primary in production.
        WorkspaceContext legacy = contextResolver != null
                ? contextResolver.resolveCurrentContext() : WorkspaceContext.SHARED;
        String username = legacy.username() == null || legacy.username().isBlank()
                ? "system" : legacy.username().strip();
        String branch = legacy.currentBranch() == null || legacy.currentBranch().isBlank()
                ? "draft" : legacy.currentBranch().strip();
        return legacy.workspaceId() == null
                ? RepositoryContext.centralWrite(TEST_REPOSITORY_ID, branch, username)
                : RepositoryContext.workspace(
                        TEST_REPOSITORY_ID,
                        legacy.workspaceId(),
                        branch,
                        username);
    }

    private CanonicalArchitectureModel parseToModel(String dslText, String path) {
        var document = parser.parse(dslText, path);
        return astMapper.map(document);
    }
}
