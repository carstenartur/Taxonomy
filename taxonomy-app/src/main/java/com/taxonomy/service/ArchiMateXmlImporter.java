package com.taxonomy.service;

import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dsl.mapping.profiles.ArchiMateMappingProfile;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.model.TaxonomyRelation;
import com.taxonomy.repository.TaxonomyNodeRepository;
import com.taxonomy.repository.TaxonomyRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.*;

/**
 * Imports an ArchiMate 3.x Model Exchange Format XML file and maps its
 * elements and relationships to taxonomy nodes and relations.
 *
 * <p>The importer performs the following steps:
 * <ol>
 *   <li>Parse elements from the XML and extract id, type, and label.</li>
 *   <li>Parse relationships from the XML and extract source, target, and type.</li>
 *   <li>Map each ArchiMate element type to a taxonomy root code using the
 *       reverse of {@link ArchiMateDiagramService#toArchiMateType(String)}.</li>
 *   <li>Match elements to existing taxonomy nodes by name similarity.</li>
 *   <li>Create new relations for matched elements.</li>
 * </ol>
 */
@Service
public class ArchiMateXmlImporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiMateXmlImporter.class);

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;

    /** Shared mapping profile for ArchiMate types. */
    private static final ArchiMateMappingProfile PROFILE = new ArchiMateMappingProfile();

    public ArchiMateXmlImporter(TaxonomyNodeRepository nodeRepository,
                                 TaxonomyRelationRepository relationRepository) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * Imports an ArchiMate XML file and creates taxonomy relations.
     *
     * @param inputStream the XML input stream
     * @return the import result with statistics
     */
    @Transactional
    public ArchiMateImportResult importXml(InputStream inputStream) {
        ArchiMateImportResult result = new ArchiMateImportResult();
        List<String> notes = new ArrayList<>();

        try {
            // Parse XML
            Map<String, ParsedElement> elements = new LinkedHashMap<>();
            List<ParsedRelationship> relationships = new ArrayList<>();
            parseXml(inputStream, elements, relationships);

            notes.add("Parsed " + elements.size() + " elements and " + relationships.size() + " relationships from XML");

            // Match elements to taxonomy nodes
            Map<String, TaxonomyNode> matchedNodes = matchElements(elements, notes);
            result.setElementsMatched(matchedNodes.size());
            result.setElementsUnmatched(elements.size() - matchedNodes.size());
            result.setElementsImported(elements.size());

            // Create relations for matched element pairs
            int relationsCreated = createRelations(relationships, elements, matchedNodes, notes);
            result.setRelationsImported(relationsCreated);

        } catch (Exception e) {
            log.error("ArchiMate import failed", e);
            notes.add("Import error: " + e.getMessage());
        }

        result.setNotes(notes);
        return result;
    }

    private void parseXml(InputStream inputStream,
                          Map<String, ParsedElement> elements,
                          List<ParsedRelationship> relationships) throws Exception {

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Security: disable external entities
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("element".equals(localName)) {
                    parseElement(reader, elements);
                } else if ("relationship".equals(localName)) {
                    parseRelationship(reader, relationships);
                }
            }
        }
        reader.close();
    }

    private void parseElement(XMLStreamReader reader, Map<String, ParsedElement> elements) throws Exception {
        String identifier = reader.getAttributeValue(null, "identifier");
        String xsiType = reader.getAttributeValue(
                "http://www.w3.org/2001/XMLSchema-instance", "type");

        if (identifier == null || xsiType == null) return;

        // Strip "id-" prefix if present
        String id = identifier.startsWith("id-") ? identifier.substring(3) : identifier;

        String label = null;
        String documentation = null;

        // Read child elements for name and documentation
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if ("name".equals(reader.getLocalName())) {
                    label = reader.getElementText();
                    depth--; // getElementText consumes the end element
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

    private void parseRelationship(XMLStreamReader reader, List<ParsedRelationship> relationships) throws Exception {
        String identifier = reader.getAttributeValue(null, "identifier");
        String xsiType = reader.getAttributeValue(
                "http://www.w3.org/2001/XMLSchema-instance", "type");
        String source = reader.getAttributeValue(null, "source");
        String target = reader.getAttributeValue(null, "target");

        if (identifier == null || source == null || target == null) return;

        // Strip "id-" prefix variants
        String sourceId = source.startsWith("id-") ? source.substring(3) : source;
        String targetId = target.startsWith("id-") ? target.substring(3) : target;
        String type = xsiType != null ? xsiType : "Association";

        relationships.add(new ParsedRelationship(identifier, sourceId, targetId, type));
    }

    private Map<String, TaxonomyNode> matchElements(Map<String, ParsedElement> elements,
                                                      List<String> notes) {
        Map<String, TaxonomyNode> matched = new LinkedHashMap<>();

        for (ParsedElement el : elements.values()) {
            String taxonomyRoot = PROFILE.mapElementType(el.type);
            if (taxonomyRoot == null) {
                notes.add("Unknown ArchiMate type: " + el.type + " for element " + el.label);
                continue;
            }

            // Try to find a matching taxonomy node by name in the expected root
            if (el.label != null && !el.label.isBlank()) {
                List<TaxonomyNode> candidates = nodeRepository
                        .findByTaxonomyRootOrderByLevelAscNameEnAsc(taxonomyRoot);

                TaxonomyNode bestMatch = findBestMatch(el.label, candidates);
                if (bestMatch != null) {
                    matched.put(el.id, bestMatch);
                }
            }
        }

        notes.add("Matched " + matched.size() + " of " + elements.size() + " elements to taxonomy nodes");
        return matched;
    }

    private TaxonomyNode findBestMatch(String label, List<TaxonomyNode> candidates) {
        if (candidates.isEmpty()) return null;

        String normalizedLabel = label.toLowerCase(Locale.ROOT).trim();

        // First: exact name match
        for (TaxonomyNode node : candidates) {
            if (node.getNameEn() != null &&
                    node.getNameEn().toLowerCase(Locale.ROOT).trim().equals(normalizedLabel)) {
                return node;
            }
        }

        // Second: contains match
        for (TaxonomyNode node : candidates) {
            if (node.getNameEn() != null) {
                String nodeName = node.getNameEn().toLowerCase(Locale.ROOT).trim();
                if (nodeName.contains(normalizedLabel) || normalizedLabel.contains(nodeName)) {
                    return node;
                }
            }
        }

        return null;
    }

    private int createRelations(List<ParsedRelationship> relationships,
                                 Map<String, ParsedElement> elements,
                                 Map<String, TaxonomyNode> matchedNodes,
                                 List<String> notes) {
        int created = 0;

        for (ParsedRelationship rel : relationships) {
            TaxonomyNode sourceNode = matchedNodes.get(rel.sourceId);
            TaxonomyNode targetNode = matchedNodes.get(rel.targetId);

            if (sourceNode == null || targetNode == null) continue;

            String relTypeName = PROFILE.mapRelationType(rel.type);
            RelationType relationType = relTypeName != null
                    ? RelationType.valueOf(relTypeName)
                    : RelationType.RELATED_TO;

            // Check if relation already exists
            List<TaxonomyRelation> existing = relationRepository
                    .findBySourceNodeCode(sourceNode.getCode());
            boolean alreadyExists = existing.stream()
                    .anyMatch(r -> r.getTargetNode().getCode().equals(targetNode.getCode()) &&
                            r.getRelationType() == relationType);

            if (!alreadyExists) {
                TaxonomyRelation newRel = new TaxonomyRelation();
                newRel.setSourceNode(sourceNode);
                newRel.setTargetNode(targetNode);
                newRel.setRelationType(relationType);
                newRel.setDescription("Imported from ArchiMate XML");
                newRel.setProvenance("ARCHIMATE_IMPORT");
                relationRepository.save(newRel);
                created++;
            }
        }

        notes.add("Created " + created + " new relations (" +
                (relationships.size() - created) + " already existed or had unmatched endpoints)");
        return created;
    }

    // ── Internal record types ─────────────────────────────────────────────────

    /** Represents a parsed ArchiMate element with its id, ArchiMate type, display label, and optional documentation text. */
    private record ParsedElement(String id, String type, String label, String documentation) {}

    /** Represents a parsed ArchiMate relationship linking a source element to a target element via a specific relationship type. */
    private record ParsedRelationship(String id, String sourceId, String targetId, String type) {}
}
