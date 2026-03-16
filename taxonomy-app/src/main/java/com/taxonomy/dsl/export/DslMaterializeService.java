package com.taxonomy.dsl.export;

import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.diff.ModelDiffer;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.validation.DslValidationResult;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.model.*;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;

/**
 * Materializes a parsed DSL document into the database.
 *
 * <p>The materialization pipeline:
 * <ol>
 *   <li>Parse DSL text into AST</li>
 *   <li>Map AST to canonical model</li>
 *   <li>Validate the model</li>
 *   <li>Materialize relations:
 *       <ul>
 *         <li>status=accepted → create {@link TaxonomyRelation}</li>
 *         <li>status=proposed/provisional → create {@link RelationHypothesis}</li>
 *       </ul>
 *   </li>
 *   <li>Store the DSL document as {@link ArchitectureDslDocument}</li>
 * </ol>
 *
 * <p>Supports incremental materialization via {@link ModelDiffer}: only the delta
 * between two models is applied to the database.
 */
@Service
public class DslMaterializeService {

    private static final Logger log = LoggerFactory.getLogger(DslMaterializeService.class);

    private final TaxonomyRelationService relationService;
    private final RelationHypothesisRepository hypothesisRepository;
    private final ArchitectureDslDocumentRepository documentRepository;
    @Nullable
    private final RepositoryStateService repositoryStateService;
    @Nullable
    private final WorkspaceResolver workspaceResolver;

    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();
    private final ModelDiffer differ = new ModelDiffer();

    public DslMaterializeService(TaxonomyRelationService relationService,
                                  RelationHypothesisRepository hypothesisRepository,
                                  ArchitectureDslDocumentRepository documentRepository,
                                  @Nullable RepositoryStateService repositoryStateService,
                                  @Nullable WorkspaceResolver workspaceResolver) {
        this.relationService = relationService;
        this.hypothesisRepository = hypothesisRepository;
        this.documentRepository = documentRepository;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Result of a materialization operation.
     */
    public record MaterializeResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            int relationsCreated,
            int hypothesesCreated,
            Long documentId
    ) {}

    /**
     * Parse, validate, and materialize a DSL document into the database.
     *
     * @param dslText    the DSL source text
     * @param path       optional file path for the document
     * @param branch     optional Git branch name
     * @param commitId   optional Git commit SHA
     * @return the materialization result
     */
    @Transactional
    public MaterializeResult materialize(String dslText, String path, String branch, String commitId) {
        // 1. Parse
        var doc = parser.parse(dslText, path);
        CanonicalArchitectureModel model = astMapper.map(doc);

        // 2. Validate
        DslValidationResult validation = validator.validate(model);
        if (!validation.isValid()) {
            return new MaterializeResult(false, validation.getErrors(), validation.getWarnings(),
                    0, 0, null);
        }

        // 3. Materialize relations
        int relationsCreated = 0;
        int hypothesesCreated = 0;

        for (ArchitectureRelation rel : model.getRelations()) {
            String status = rel.getStatus() != null ? rel.getStatus().toLowerCase() : "accepted";

            if ("accepted".equals(status)) {
                try {
                    RelationType type = RelationType.valueOf(rel.getRelationType());
                    relationService.createRelation(
                            rel.getSourceId(), rel.getTargetId(), type,
                            "Materialized from DSL", "dsl-materialize");
                    relationsCreated++;
                } catch (IllegalArgumentException e) {
                    log.warn("Skipped relation {} → {}: {}", rel.getSourceId(), rel.getTargetId(), e.getMessage());
                }
            } else if ("proposed".equals(status) || "provisional".equals(status)) {
                try {
                    RelationType type = RelationType.valueOf(rel.getRelationType());
                    HypothesisStatus hStatus = "proposed".equals(status)
                            ? HypothesisStatus.PROPOSED : HypothesisStatus.PROVISIONAL;

                    RelationHypothesis hypothesis = new RelationHypothesis();
                    hypothesis.setSourceNodeId(rel.getSourceId());
                    hypothesis.setTargetNodeId(rel.getTargetId());
                    hypothesis.setRelationType(type);
                    hypothesis.setStatus(hStatus);
                    hypothesis.setConfidence(rel.getConfidence());
                    hypothesis.setAnalysisSessionId("dsl-materialize");

                    hypothesisRepository.save(hypothesis);
                    hypothesesCreated++;
                } catch (IllegalArgumentException e) {
                    log.warn("Skipped hypothesis {} → {}: {}", rel.getSourceId(), rel.getTargetId(), e.getMessage());
                }
            }
        }

        // 4. Store the DSL document
        String namespace = doc.getMeta() != null ? doc.getMeta().namespace() : null;
        String dslVersion = doc.getMeta() != null ? doc.getMeta().version() : null;

        ArchitectureDslDocument document = new ArchitectureDslDocument();
        document.setPath(path != null ? path : "inline");
        document.setBranch(branch);
        document.setCommitId(commitId);
        document.setNamespace(namespace);
        document.setDslVersion(dslVersion);
        document.setRawContent(dslText);

        ArchitectureDslDocument saved = documentRepository.save(document);

        log.info("Materialized DSL document '{}': {} relations, {} hypotheses",
                path, relationsCreated, hypothesesCreated);

        // Track projection state for staleness detection
        if (repositoryStateService != null && commitId != null) {
            String username = workspaceResolver != null
                    ? workspaceResolver.resolveCurrentUsername() : "anonymous";
            repositoryStateService.recordProjection(username, commitId, branch);
        }

        return new MaterializeResult(true, List.of(), validation.getWarnings(),
                relationsCreated, hypothesesCreated, saved.getId());
    }

    /**
     * Compute a diff between two stored DSL documents by their IDs.
     *
     * @param beforeDocId the ID of the "before" document (may be {@code null} for initial commit)
     * @param afterDocId  the ID of the "after" document
     * @return the model diff
     * @throws IllegalArgumentException if a document ID is not found
     */
    public ModelDiff diffDocuments(Long beforeDocId, Long afterDocId) {
        CanonicalArchitectureModel before = null;
        if (beforeDocId != null) {
            ArchitectureDslDocument beforeDoc = documentRepository.findById(beforeDocId)
                    .orElseThrow(() -> new IllegalArgumentException("Before document not found: " + beforeDocId));
            before = parseToModel(beforeDoc.getRawContent(), beforeDoc.getPath());
        }

        ArchitectureDslDocument afterDoc = documentRepository.findById(afterDocId)
                .orElseThrow(() -> new IllegalArgumentException("After document not found: " + afterDocId));
        CanonicalArchitectureModel after = parseToModel(afterDoc.getRawContent(), afterDoc.getPath());

        return differ.diff(before, after);
    }

    /**
     * Incrementally materialize only the delta between two DSL document versions.
     *
     * <p>Instead of materializing the full model, this method computes a
     * {@link ModelDiff} and only creates/removes the changed relations.
     *
     * @param beforeDocId the "before" document ID (may be {@code null} for initial)
     * @param afterDocId  the "after" document ID
     * @return the materialization result
     */
    @Transactional
    public MaterializeResult materializeIncremental(Long beforeDocId, Long afterDocId) {
        ModelDiff diff = diffDocuments(beforeDocId, afterDocId);

        int relationsCreated = 0;
        int hypothesesCreated = 0;
        List<String> warnings = new ArrayList<>();

        // Process added relations
        for (ArchitectureRelation rel : diff.addedRelations()) {
            String status = rel.getStatus() != null ? rel.getStatus().toLowerCase() : "accepted";
            if ("accepted".equals(status)) {
                try {
                    RelationType type = RelationType.valueOf(rel.getRelationType());
                    relationService.createRelation(
                            rel.getSourceId(), rel.getTargetId(), type,
                            "Materialized incrementally from DSL", "dsl-incremental");
                    relationsCreated++;
                } catch (IllegalArgumentException e) {
                    warnings.add("Skipped relation " + rel.getSourceId() + " → " + rel.getTargetId() + ": " + e.getMessage());
                }
            } else if ("proposed".equals(status) || "provisional".equals(status)) {
                try {
                    RelationType type = RelationType.valueOf(rel.getRelationType());
                    HypothesisStatus hStatus = "proposed".equals(status)
                            ? HypothesisStatus.PROPOSED : HypothesisStatus.PROVISIONAL;

                    RelationHypothesis hypothesis = new RelationHypothesis();
                    hypothesis.setSourceNodeId(rel.getSourceId());
                    hypothesis.setTargetNodeId(rel.getTargetId());
                    hypothesis.setRelationType(type);
                    hypothesis.setStatus(hStatus);
                    hypothesis.setConfidence(rel.getConfidence());
                    hypothesis.setAnalysisSessionId("dsl-incremental");

                    hypothesisRepository.save(hypothesis);
                    hypothesesCreated++;
                } catch (IllegalArgumentException e) {
                    warnings.add("Skipped hypothesis " + rel.getSourceId() + " → " + rel.getTargetId() + ": " + e.getMessage());
                }
            }
        }

        // Process changed relations (status changes, e.g. provisional → accepted)
        for (ModelDiff.RelationChange change : diff.changedRelations()) {
            String newStatus = change.after().getStatus() != null ? change.after().getStatus().toLowerCase() : "accepted";
            String oldStatus = change.before().getStatus() != null ? change.before().getStatus().toLowerCase() : "accepted";

            // If status changed from provisional/proposed to accepted, create relation
            if ("accepted".equals(newStatus) && !newStatus.equals(oldStatus)) {
                try {
                    RelationType type = RelationType.valueOf(change.after().getRelationType());
                    relationService.createRelation(
                            change.after().getSourceId(), change.after().getTargetId(), type,
                            "Promoted from " + oldStatus + " via DSL", "dsl-incremental");
                    relationsCreated++;
                } catch (IllegalArgumentException e) {
                    warnings.add("Skipped promotion " + change.after().getSourceId() + " → " + change.after().getTargetId() + ": " + e.getMessage());
                }
            }
        }

        log.info("Incremental materialization: {} relations created, {} hypotheses created, {} changes total",
                relationsCreated, hypothesesCreated, diff.totalChanges());

        return new MaterializeResult(true, List.of(), warnings,
                relationsCreated, hypothesesCreated, afterDocId);
    }

    private CanonicalArchitectureModel parseToModel(String dslText, String path) {
        var doc = parser.parse(dslText, path);
        return astMapper.map(doc);
    }
}
