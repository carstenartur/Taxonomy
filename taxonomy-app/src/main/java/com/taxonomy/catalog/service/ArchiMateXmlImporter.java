package com.taxonomy.catalog.service;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.export.ArchiMateSchema;
import com.taxonomy.export.ArchiMateExchangeReader;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dsl.mapping.profiles.ArchiMateMappingProfile;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.RepositoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses ArchiMate 3.x Model Exchange XML and materializes matched relations in
 * an explicit workspace.
 *
 * <p>Parsing/matching and persistence are deliberately separated. Preview uses
 * the same parser and duplicate policy but performs no writes. Import runs in a
 * single transaction; a fatal materialization error therefore rolls back every
 * relation created by that request.</p>
 */
@Service
public class ArchiMateXmlImporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiMateXmlImporter.class);
    private static final ArchiMateMappingProfile PROFILE = new ArchiMateMappingProfile();

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationService relationService;

    public ArchiMateXmlImporter(TaxonomyNodeRepository nodeRepository,
                                TaxonomyRelationService relationService) {
        this.nodeRepository = nodeRepository;
        this.relationService = relationService;
    }

    /** Parses and validates the model without mutating architecture state. */
    @Transactional(readOnly = true)
    public ArchiMateImportResult previewXml(InputStream inputStream, WorkspaceContext context) {
        return execute(inputStream, requireContext(context), false);
    }

    /**
     * Parses, validates, and atomically creates relations in the exact active
     * workspace. Shared baseline relations are visible to personal workspaces
     * and therefore count as duplicates rather than being copied locally.
     */
    @Transactional
    public ArchiMateImportResult importXml(InputStream inputStream, WorkspaceContext context) {
        return execute(inputStream, requireContext(context), true);
    }

    /** Backward-compatible shared-scope overload for non-request callers. */
    @Transactional
    public ArchiMateImportResult importXml(InputStream inputStream) {
        return importXml(inputStream, WorkspaceContext.SHARED);
    }

    private ArchiMateImportResult execute(InputStream inputStream,
                                          WorkspaceContext context,
                                          boolean materialize) {
        ParsedModel model = parseModel(inputStream);
        List<String> notes = new ArrayList<>();
        notes.add("Parsed " + model.elements().size() + " elements and "
                + model.relationships().size() + " relationships from XML");

        Map<String, TaxonomyNode> matchedNodes = matchElements(model.elements(), notes);

        ArchiMateImportResult result = new ArchiMateImportResult();
        result.setPreview(!materialize);
        if (model.exchange() != null) {
            result.setMappingProfile(com.taxonomy.export.ArchiMateExchangeProfile.VERSION);
            result.setLosses(model.exchange().losses().stream().map(loss ->
                    new ArchiMateImportResult.ImportLoss(loss.scope(), loss.id(), loss.field(), loss.kind(), loss.rationale())).toList());
            List<ArchiMateImportResult.ImportLoss> importLosses = new ArrayList<>(result.getLosses());
            for (var element : model.exchange().elements()) {
                importLosses.add(new ArchiMateImportResult.ImportLoss("element", element.id(), "properties", "OMITTED",
                        "Catalogue import resolves exact existing identities; it does not overwrite catalogue labels, selection, or review properties."));
            }
            for (var relation : model.exchange().relationships()) {
                importLosses.add(new ArchiMateImportResult.ImportLoss("relationship", relation.id(), "externalIdAndProperties", "OMITTED",
                        "Relation materialization deduplicates by exact endpoints and original type; it does not restore snapshot IDs, scores or decisions."));
            }
            for (var view : model.exchange().views()) {
                importLosses.add(new ArchiMateImportResult.ImportLoss("view", view.id(), "view", "OMITTED",
                        "The catalogue relation import does not persist views; the exchange reader preserves their complete membership and supported layout."));
            }
            result.setLosses(importLosses);
            notes.add("Taxonomy exchange profile: exact catalogue identities and original relationship types; no fuzzy matching.");
        }
        result.setElementsImported(model.elements().size());
        result.setElementsMatched(matchedNodes.size());
        result.setElementsUnmatched(model.elements().size() - matchedNodes.size());
        result.setRelationsParsed(model.relationships().size());

        int created = 0;
        int skipped = 0;
        int rejected = 0;

        for (ParsedRelationship relation : model.relationships()) {
            TaxonomyNode sourceNode = matchedNodes.get(relation.sourceId());
            TaxonomyNode targetNode = matchedNodes.get(relation.targetId());
            if (sourceNode == null || targetNode == null) {
                rejected++;
                continue;
            }

            String mappedType = relation.taxonomyType() != null ? relation.taxonomyType()
                    : PROFILE.mapRelationType(relation.type());
            RelationType relationType;
            try {
                relationType = mappedType != null ? RelationType.valueOf(mappedType) : RelationType.RELATED_TO;
            } catch (IllegalArgumentException unsupported) {
                throw new ArchiMateImportException("Unsupported original relationship type: " + mappedType, unsupported);
            }

            boolean exists = relationExists(sourceNode.getCode(), targetNode.getCode(), relationType, context);
            if (exists) {
                skipped++;
                continue;
            }

            if (!materialize) {
                continue;
            }

            try {
                if (WorkspaceContext.LEGACY_REPOSITORY_ID.equals(context.repositoryId())) {
                    relationService.createRelation(sourceNode.getCode(), targetNode.getCode(), relationType,
                            "Imported from ArchiMate XML", "ARCHIMATE_IMPORT", context.workspaceId(), context.username());
                } else {
                    relationService.createRelationInContext(sourceNode.getCode(), targetNode.getCode(), relationType,
                            "Imported from ArchiMate XML", "ARCHIMATE_IMPORT", repositoryContext(context));
                }
                created++;
            } catch (IllegalArgumentException error) {
                // Treat only a concurrent duplicate as a skip. Any other failure
                // is fatal and must roll the complete import transaction back.
                if (relationExists(sourceNode.getCode(), targetNode.getCode(), relationType, context)) {
                    skipped++;
                } else {
                    throw new ArchiMateImportException(
                            "Unable to materialize ArchiMate relation", error);
                }
            } catch (RuntimeException error) {
                throw new ArchiMateImportException(
                        "Unable to materialize ArchiMate relation", error);
            }
        }

        result.setRelationsImported(created);
        result.setRelationsSkipped(skipped);
        result.setRelationsRejected(rejected);
        result.setNotes(notes);

        int eligible = model.relationships().size() - skipped - rejected;
        notes.add(materialize
                ? "Created " + created + " relation(s) in workspace " + scopeName(context)
                        + "; skipped " + skipped + " duplicate(s); rejected " + rejected
                        + " relation(s) with unmatched endpoints"
                : "Preview found " + eligible + " relation(s) eligible for workspace "
                        + scopeName(context) + "; " + skipped + " duplicate(s); "
                        + rejected + " relation(s) with unmatched endpoints");

        log.info("ArchiMate {} complete: elements={}, matched={}, relations={}, created={}, skipped={}, rejected={}, workspace={}",
                materialize ? "import" : "preview", model.elements().size(), matchedNodes.size(),
                model.relationships().size(), created, skipped, rejected, context.workspaceId());
        return result;
    }

    private boolean relationExists(String source, String target, RelationType type, WorkspaceContext context) {
        // Only direct legacy callers may resolve the primary repository. HTTP callers carry exact repository identity.
        return WorkspaceContext.LEGACY_REPOSITORY_ID.equals(context.repositoryId())
                ? relationService.relationExistsVisible(source, target, type, context.workspaceId())
                : relationService.relationExistsVisibleInContext(source, target, type, repositoryContext(context));
    }

    private static RepositoryContext repositoryContext(WorkspaceContext context) {
        return context.workspaceId() == null
                ? RepositoryContext.centralWrite(context.repositoryId(), context.currentBranch(), context.username())
                : RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), context.currentBranch(), context.username());
    }

    private ParsedModel parseModel(InputStream inputStream) {
        if (inputStream == null) throw new ArchiMateImportException("No ArchiMate XML input was provided");
        try {
            byte[] bytes = inputStream.readNBytes(ArchiMateSchema.MAX_BYTES + 1);
            var document = ArchiMateSchema.parse(bytes);
            Map<String, ParsedElement> elements = new LinkedHashMap<>();
            List<ParsedRelationship> relationships = new ArrayList<>();
            if (ArchiMateExchangeReader.hasTaxonomyProfile(document)) {
                ArchiMateModel exchange = new ArchiMateExchangeReader().read(document);
                for (var element : exchange.elements()) {
                    elements.put(element.id(), new ParsedElement(element.id(), element.archiMateType(),
                            element.label(), true));
                }
                for (var relation : exchange.relationships()) {
                    relationships.add(new ParsedRelationship(relation.id(), relation.sourceId(), relation.targetId(),
                            relation.archiMateType(), relation.properties().get("taxonomy.type").value()));
                }
                return new ParsedModel(elements, relationships, exchange);
            }
            var root = document.getDocumentElement();
            for (var element : ArchiMateExchangeReader.children(ArchiMateExchangeReader.child(root, "elements"), "element")) {
                String id = element.getAttribute("identifier");
                elements.put(id, new ParsedElement(id, ArchiMateExchangeReader.type(element),
                        ArchiMateExchangeReader.content(element, "name"), false));
            }
            for (var relation : ArchiMateExchangeReader.children(ArchiMateExchangeReader.child(root, "relationships"), "relationship")) {
                relationships.add(new ParsedRelationship(relation.getAttribute("identifier"),
                        relation.getAttribute("source"), relation.getAttribute("target"),
                        ArchiMateExchangeReader.type(relation), null));
            }
            return new ParsedModel(elements, relationships, null);
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw new ArchiMateImportException("Malformed or unsupported ArchiMate XML: " + error.getMessage(), error);
        }
    }

    private Map<String, TaxonomyNode> matchElements(Map<String, ParsedElement> elements,
                                                     List<String> notes) {
        Map<String, TaxonomyNode> matched = new LinkedHashMap<>();
        for (ParsedElement element : elements.values()) {
            if (element.exactIdentity()) {
                nodeRepository.findByCode(element.id()).ifPresentOrElse(node -> matched.put(element.id(), node),
                        () -> notes.add("Unmatched exact Taxonomy identity: " + element.id()));
                continue;
            }
            String taxonomyRoot = PROFILE.mapElementType(element.type());
            if (taxonomyRoot == null) {
                notes.add("Unknown ArchiMate type: " + element.type() + " for element " + element.label());
                continue;
            }
            if (element.label() == null || element.label().isBlank()) {
                continue;
            }

            List<TaxonomyNode> candidates = nodeRepository
                    .findByTaxonomyRootOrderByLevelAscNameEnAsc(taxonomyRoot);
            TaxonomyNode bestMatch = findBestMatch(element.label(), candidates);
            if (bestMatch != null) {
                matched.put(element.id(), bestMatch);
            }
        }
        notes.add("Matched " + matched.size() + " of " + elements.size()
                + " elements to taxonomy nodes");
        return matched;
    }

    private TaxonomyNode findBestMatch(String label, List<TaxonomyNode> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        String normalizedLabel = label.toLowerCase(Locale.ROOT).trim();
        for (TaxonomyNode node : candidates) {
            if (node.getNameEn() != null
                    && node.getNameEn().toLowerCase(Locale.ROOT).trim().equals(normalizedLabel)) {
                return node;
            }
        }
        for (TaxonomyNode node : candidates) {
            if (node.getNameEn() == null) {
                continue;
            }
            String nodeName = node.getNameEn().toLowerCase(Locale.ROOT).trim();
            if (nodeName.contains(normalizedLabel) || normalizedLabel.contains(nodeName)) {
                return node;
            }
        }
        return null;
    }

    private static WorkspaceContext requireContext(WorkspaceContext context) {
        if (context == null) {
            throw new ArchiMateImportException("An explicit workspace context is required");
        }
        return context;
    }

    private static String scopeName(WorkspaceContext context) {
        return context.workspaceId() != null ? context.workspaceId() : "shared";
    }

    private record ParsedModel(Map<String, ParsedElement> elements,
                               List<ParsedRelationship> relationships, ArchiMateModel exchange) {
    }

    private record ParsedElement(String id, String type, String label, boolean exactIdentity) {
    }

    private record ParsedRelationship(String id, String sourceId, String targetId, String type, String taxonomyType) {
    }
}
