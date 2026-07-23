package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dsl.mapping.profiles.ArchiMateMappingProfile;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
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

            String mappedType = PROFILE.mapRelationType(relation.type());
            RelationType relationType = mappedType != null
                    ? RelationType.valueOf(mappedType)
                    : RelationType.RELATED_TO;

            boolean exists = relationService.relationExistsVisible(
                    sourceNode.getCode(), targetNode.getCode(), relationType, context.workspaceId());
            if (exists) {
                skipped++;
                continue;
            }

            if (!materialize) {
                continue;
            }

            try {
                relationService.createRelation(
                        sourceNode.getCode(), targetNode.getCode(), relationType,
                        "Imported from ArchiMate XML", "ARCHIMATE_IMPORT",
                        context.workspaceId(), context.username());
                created++;
            } catch (IllegalArgumentException error) {
                // Treat only a concurrent duplicate as a skip. Any other failure
                // is fatal and must roll the complete import transaction back.
                if (relationService.relationExistsVisible(
                        sourceNode.getCode(), targetNode.getCode(), relationType, context.workspaceId())) {
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

    private ParsedModel parseModel(InputStream inputStream) {
        if (inputStream == null) {
            throw new ArchiMateImportException("No ArchiMate XML input was provided");
        }

        Map<String, ParsedElement> elements = new LinkedHashMap<>();
        List<ParsedRelationship> relationships = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // XXE hardening: never resolve DTDs or external entities.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    if ("element".equals(reader.getLocalName())) {
                        parseElement(reader, elements);
                    } else if ("relationship".equals(reader.getLocalName())) {
                        parseRelationship(reader, relationships);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException error) {
            throw new ArchiMateImportException("Malformed ArchiMate XML", error);
        }

        return new ParsedModel(elements, relationships);
    }

    private void parseElement(XMLStreamReader reader,
                              Map<String, ParsedElement> elements) throws XMLStreamException {
        String identifier = reader.getAttributeValue(null, "identifier");
        String xsiType = reader.getAttributeValue(
                "http://www.w3.org/2001/XMLSchema-instance", "type");
        if (identifier == null || xsiType == null) {
            return;
        }

        String id = stripIdentifierPrefix(identifier);
        String label = null;
        String documentation = null;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if ("name".equals(reader.getLocalName())) {
                    label = reader.getElementText();
                    depth--;
                } else if ("documentation".equals(reader.getLocalName())) {
                    documentation = reader.getElementText();
                    depth--;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        elements.put(id, new ParsedElement(id, xsiType, label, documentation));
    }

    private void parseRelationship(XMLStreamReader reader,
                                   List<ParsedRelationship> relationships) {
        String identifier = reader.getAttributeValue(null, "identifier");
        String xsiType = reader.getAttributeValue(
                "http://www.w3.org/2001/XMLSchema-instance", "type");
        String source = reader.getAttributeValue(null, "source");
        String target = reader.getAttributeValue(null, "target");
        if (identifier == null || source == null || target == null) {
            return;
        }

        relationships.add(new ParsedRelationship(
                identifier,
                stripIdentifierPrefix(source),
                stripIdentifierPrefix(target),
                xsiType != null ? xsiType : "Association"));
    }

    private Map<String, TaxonomyNode> matchElements(Map<String, ParsedElement> elements,
                                                     List<String> notes) {
        Map<String, TaxonomyNode> matched = new LinkedHashMap<>();
        for (ParsedElement element : elements.values()) {
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

    private static String stripIdentifierPrefix(String value) {
        return value.startsWith("id-") ? value.substring(3) : value;
    }

    private static String scopeName(WorkspaceContext context) {
        return context.workspaceId() != null ? context.workspaceId() : "shared";
    }

    private record ParsedModel(Map<String, ParsedElement> elements,
                               List<ParsedRelationship> relationships) {
    }

    private record ParsedElement(String id, String type, String label, String documentation) {
    }

    private record ParsedRelationship(String id, String sourceId, String targetId, String type) {
    }
}