package com.nato.taxonomy.dsl.export;

import com.nato.taxonomy.dsl.mapper.AstToModelMapper;
import com.nato.taxonomy.dsl.model.*;
import com.nato.taxonomy.dsl.parser.TaxDslParser;
import com.nato.taxonomy.dsl.validation.DslValidationResult;
import com.nato.taxonomy.dsl.validation.DslValidator;
import com.nato.taxonomy.model.*;
import com.nato.taxonomy.repository.ArchitectureDslDocumentRepository;
import com.nato.taxonomy.repository.RelationHypothesisRepository;
import com.nato.taxonomy.service.TaxonomyRelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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
 */
@Service
public class DslMaterializeService {

    private static final Logger log = LoggerFactory.getLogger(DslMaterializeService.class);

    private final TaxonomyRelationService relationService;
    private final RelationHypothesisRepository hypothesisRepository;
    private final ArchitectureDslDocumentRepository documentRepository;

    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();

    public DslMaterializeService(TaxonomyRelationService relationService,
                                  RelationHypothesisRepository hypothesisRepository,
                                  ArchitectureDslDocumentRepository documentRepository) {
        this.relationService = relationService;
        this.hypothesisRepository = hypothesisRepository;
        this.documentRepository = documentRepository;
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

        return new MaterializeResult(true, List.of(), validation.getWarnings(),
                relationsCreated, hypothesesCreated, saved.getId());
    }
}
