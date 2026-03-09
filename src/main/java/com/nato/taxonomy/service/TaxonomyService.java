package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.model.TaxonomyRelation;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class TaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyService.class);

    private static final String CATALOGUE_PATH = "data/C3_Taxonomy_Catalogue_25AUG2025.xlsx";

    private static final int BATCH_SIZE = 50;

    /** Maps sheet name → two-letter prefix used as virtual root code. */
    private static final Map<String, String> SHEET_PREFIXES = new LinkedHashMap<>();
    static {
        SHEET_PREFIXES.put("Business Processes",       "BP");
        SHEET_PREFIXES.put("Business Roles",           "BR");
        SHEET_PREFIXES.put("Capabilities",             "CP");
        SHEET_PREFIXES.put("COI Services",             "CI");
        SHEET_PREFIXES.put("Communications Services",  "CO");
        SHEET_PREFIXES.put("Core Services",            "CR");
        SHEET_PREFIXES.put("Information Products",     "IP");
        SHEET_PREFIXES.put("User Applications",        "UA");
    }

    private final TaxonomyNodeRepository repository;
    private final TaxonomyRelationRepository relationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    public TaxonomyService(TaxonomyNodeRepository repository,
                           TaxonomyRelationRepository relationRepository) {
        this.repository = repository;
        this.relationRepository = relationRepository;
    }

    /**
     * Load the taxonomy from the bundled Excel workbook.
     * Uses an explicit {@link TransactionTemplate} because {@code @Transactional} is not
     * honoured when a method is invoked directly by the container via {@code @PostConstruct}.
     */
    @PostConstruct
    public void loadTaxonomyFromExcel() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                try {
                    doLoadTaxonomy();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            // Unwrap to log the original exception (IOException, etc.) with its full stack trace
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Failed to load taxonomy from Excel", cause);
        }
    }

    private void doLoadTaxonomy() throws Exception {
        relationRepository.deleteAll();
        repository.deleteAll();

        ClassPathResource resource = new ClassPathResource(CATALOGUE_PATH);

        // Global node map: code → entity (across all sheets)
        Map<String, TaxonomyNode> nodeMap = new LinkedHashMap<>();

        // UUID → code map used to resolve parent references that use UUIDs
        Map<String, String> uuidToCode = new HashMap<>();

        // 1. Create one virtual root per sheet (level 0)
        List<TaxonomyNode> virtualRoots = new ArrayList<>();
        for (Map.Entry<String, String> entry : SHEET_PREFIXES.entrySet()) {
            String sheetName = entry.getKey();
            String prefix    = entry.getValue();
            TaxonomyNode root = new TaxonomyNode();
            root.setCode(prefix);
            root.setNameEn(sheetName);
            root.setDescriptionEn("C3 Taxonomy – " + sheetName);
            root.setTaxonomyRoot(prefix);
            root.setLevel(0);
            nodeMap.put(prefix, root);
            virtualRoots.add(root);
        }

        // Raw relation tuples extracted from the workbook before it is closed:
        // each entry is [sourceCode, targetCode, typeStr, description]
        List<String[]> rawRelations = new ArrayList<>();
        boolean hasExcelRelations = false;

        try (InputStream is = resource.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            // 2. Read every sheet and collect raw rows
            for (Map.Entry<String, String> entry : SHEET_PREFIXES.entrySet()) {
                String sheetName = entry.getKey();
                String prefix    = entry.getValue();
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    log.warn("Sheet '{}' not found in workbook.", sheetName);
                    continue;
                }
                readSheet(sheet, prefix, nodeMap, uuidToCode);
            }

            // 3. Wire parent-child relationships
            for (TaxonomyNode node : nodeMap.values()) {
                if (node.getLevel() == 0) continue; // virtual roots have no parent
                String parentCode = node.getParentCode();
                TaxonomyNode parent = (parentCode != null) ? nodeMap.get(parentCode) : null;

                // Fallback: parentCode might be a UUID — resolve it to the actual code
                if (parent == null && parentCode != null) {
                    String resolvedCode = uuidToCode.get(parentCode);
                    if (resolvedCode != null) {
                        parent = nodeMap.get(resolvedCode);
                        if (parent != null) {
                            node.setParentCode(resolvedCode);
                        }
                    }
                }

                // Last resort: attach to the virtual sheet root
                if (parent == null) {
                    log.debug("Last resort parent assignment for node '{}' (parentCode='{}', root='{}')",
                            node.getCode(), node.getParentCode(), node.getTaxonomyRoot());
                    parent = nodeMap.get(node.getTaxonomyRoot());
                    node.setParentCode(node.getTaxonomyRoot());
                }
                if (parent != null) {
                    node.setParent(parent);
                    parent.getChildren().add(node);
                }
            }

            // 4a. Extract raw relation rows BEFORE closing the workbook so the workbook
            //     can be released from heap before the (memory-intensive) persist phase.
            Sheet relationsSheet = workbook.getSheet("Relations");
            if (relationsSheet != null) {
                hasExcelRelations = true;
                extractRawRelations(relationsSheet, rawRelations);
            }
        } // workbook closes here, releasing its heap memory

        // 4b. Persist nodes in batches to limit Persistence Context size.
        //     Returns a code → database ID map for subsequent relation wiring.
        Map<String, Long> codeToId = persistNodesBatched(virtualRoots, nodeMap);
        log.info("Taxonomy loaded: {} nodes from {} sheets.",
                nodeMap.size(), SHEET_PREFIXES.size());

        // 4c. Load relations using managed entity proxies (via codeToId)
        if (hasExcelRelations) {
            persistRawRelations(rawRelations, codeToId);
        } else {
            log.info("No 'Relations' sheet found in workbook — trying CSV fallback.");
            loadRelationsFromCsv(codeToId);
        }

        // 4d. Help GC by releasing the large in-memory maps
        nodeMap.clear();
        uuidToCode.clear();
        log.info("Cleared in-memory node maps to free heap.");

        // 5. Hibernate Search auto-indexes nodes on JPA persist; log the count.
        log.info("Full-text and vector index will be populated automatically by Hibernate Search.");

        // 6. No explicit KNN index invalidation needed – Hibernate Search manages the index.
        log.debug("Hibernate Search manages the vector index; no manual invalidation required.");
    }

    /**
     * Persist all nodes in batches, flushing and clearing the Persistence Context every
     * {@value #BATCH_SIZE} inserts to keep its memory footprint small.
     *
     * @return a map of node code → generated database ID for subsequent FK wiring
     */
    private Map<String, Long> persistNodesBatched(List<TaxonomyNode> virtualRoots,
                                                   Map<String, TaxonomyNode> nodeMap) {
        Map<String, Long> codeToId = new HashMap<>();

        // Clear in-memory children lists so that individual entityManager.persist() calls
        // do NOT trigger CascadeType.ALL and accidentally persist the entire tree at once.
        // The nodeMap itself (and thus these entities) is discarded after the loading phase,
        // so mutating the transient children collections here has no runtime side effects.
        for (TaxonomyNode node : nodeMap.values()) {
            node.getChildren().clear();
        }

        // Persist level-0 (virtual root) nodes first; they have no parent FK to resolve.
        for (TaxonomyNode root : virtualRoots) {
            entityManager.persist(root);
            // For IDENTITY generation strategy the INSERT is executed immediately,
            // so the generated ID is available right after persist().
            codeToId.put(root.getCode(), root.getId());
        }
        entityManager.flush();
        entityManager.clear();

        // Collect non-root nodes and sort by level so that a parent is always persisted
        // before any of its children, regardless of the order in nodeMap.
        List<TaxonomyNode> nonRoots = new ArrayList<>();
        for (TaxonomyNode node : nodeMap.values()) {
            if (node.getLevel() > 0) {
                nonRoots.add(node);
            }
        }
        nonRoots.sort(Comparator.comparingInt(TaxonomyNode::getLevel));

        int count = 0;
        for (TaxonomyNode node : nonRoots) {
            // Replace the in-memory parent reference with a lightweight managed proxy so
            // that the FK column is set correctly even after earlier PC.clear() calls.
            // getReference() returns an uninitialized proxy whose ID is set immediately;
            // no SELECT is issued because only the FK value (the ID) is needed for the INSERT.
            String parentCode = node.getParentCode();
            if (parentCode != null && codeToId.containsKey(parentCode)) {
                node.setParent(entityManager.getReference(TaxonomyNode.class,
                        codeToId.get(parentCode)));
            }
            entityManager.persist(node);
            codeToId.put(node.getCode(), node.getId());
            count++;
            if (count % BATCH_SIZE == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();

        return codeToId;
    }

    /** Extract raw relation rows from the Relations sheet into a list of tuples. */
    private void extractRawRelations(Sheet sheet, List<String[]> rawRelations) {
        boolean first = true;
        for (Row row : sheet) {
            if (first) { first = false; continue; } // skip header
            String sourceCode  = cellString(row, 0);
            String targetCode  = cellString(row, 1);
            String typeStr     = cellString(row, 2);
            String description = cellString(row, 3);
            if (sourceCode == null || targetCode == null || typeStr == null) continue;
            rawRelations.add(new String[]{sourceCode, targetCode, typeStr, description});
        }
    }

    /** Persist raw relation tuples as TaxonomyRelation entities. */
    private void persistRawRelations(List<String[]> rawRelations, Map<String, Long> codeToId) {
        List<TaxonomyRelation> relations = new ArrayList<>();
        for (String[] raw : rawRelations) {
            String sourceCode  = raw[0];
            String targetCode  = raw[1];
            String typeStr     = raw[2];
            String description = raw[3];

            Long sourceId = codeToId.get(sourceCode);
            Long targetId = codeToId.get(targetCode);
            if (sourceId == null) {
                log.warn("Relations sheet: source node '{}' not found — skipping row.", sourceCode);
                continue;
            }
            if (targetId == null) {
                log.warn("Relations sheet: target node '{}' not found — skipping row.", targetCode);
                continue;
            }

            RelationType relationType;
            try {
                relationType = RelationType.valueOf(typeStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Relations sheet: unknown relation type '{}' — skipping row.", typeStr);
                continue;
            }

            TaxonomyRelation relation = new TaxonomyRelation();
            relation.setSourceNode(entityManager.getReference(TaxonomyNode.class, sourceId));
            relation.setTargetNode(entityManager.getReference(TaxonomyNode.class, targetId));
            relation.setRelationType(relationType);
            relation.setDescription(truncate(description, 2000));
            relation.setProvenance("excel");
            relations.add(relation);
        }
        relationRepository.saveAll(relations);
        log.info("Relations sheet loaded: {} relations.", relations.size());
    }

    /** Load relations from the CSV fallback file when no Relations sheet is present in the workbook. */
    private void loadRelationsFromCsv(Map<String, Long> codeToId) {
        ClassPathResource csvResource = new ClassPathResource("data/relations.csv");
        if (!csvResource.exists()) {
            log.warn("CSV fallback 'data/relations.csv' not found — no relations loaded.");
            return;
        }
        List<TaxonomyRelation> relations = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvResource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header row
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // Split on comma with limit 4; descriptions in the CSV must not contain commas
                String[] parts = line.split(",", 4);
                if (parts.length < 3) continue;
                String sourceCode  = parts[0].trim();
                String targetCode  = parts[1].trim();
                String typeStr     = parts[2].trim();
                String description = parts.length >= 4 ? parts[3].trim() : null;

                Long sourceId = codeToId.get(sourceCode);
                Long targetId = codeToId.get(targetCode);
                if (sourceId == null) {
                    log.warn("CSV relations: source node '{}' not found — skipping row.", sourceCode);
                    continue;
                }
                if (targetId == null) {
                    log.warn("CSV relations: target node '{}' not found — skipping row.", targetCode);
                    continue;
                }

                RelationType relationType;
                try {
                    relationType = RelationType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("CSV relations: unknown relation type '{}' — skipping row.", typeStr);
                    continue;
                }

                TaxonomyRelation relation = new TaxonomyRelation();
                relation.setSourceNode(entityManager.getReference(TaxonomyNode.class, sourceId));
                relation.setTargetNode(entityManager.getReference(TaxonomyNode.class, targetId));
                relation.setRelationType(relationType);
                relation.setDescription(truncate(description, 2000));
                relation.setProvenance("csv-default");
                relations.add(relation);
            }
        } catch (Exception e) {
            log.error("Failed to load relations from CSV fallback", e);
            return;
        }
        relationRepository.saveAll(relations);
        log.info("CSV relations loaded: {} relations.", relations.size());
    }

    /** Read one sheet and populate nodeMap and uuidToCode. */
    private void readSheet(Sheet sheet, String sheetPrefix, Map<String, TaxonomyNode> nodeMap,
                           Map<String, String> uuidToCode) {
        // Expected columns: Page(0), UUID(1), Title(2), Description(3),
        //                   Parent(4), Dataset(5), ExternalID(6), Source(7),
        //                   Reference(8), Order(9), State(10), Level(11)
        boolean first = true;
        for (Row row : sheet) {
            if (first) { first = false; continue; } // skip header
            String code        = cellString(row, 0);
            String uuid        = cellString(row, 1);
            String name        = cellString(row, 2);
            String description = cellString(row, 3);
            String parentCode  = cellString(row, 4);
            String dataset     = cellString(row, 5);
            String externalId  = cellString(row, 6);
            String source      = cellString(row, 7);
            String reference   = cellString(row, 8);
            String orderStr    = cellString(row, 9);
            String state       = cellString(row, 10);
            String levelStr    = cellString(row, 11);

            if (code == null || name == null) continue;

            // Build UUID → code mapping for parent resolution fallback
            if (uuid != null) {
                uuidToCode.put(uuid, code);
            }

            int level = 1;
            if (levelStr != null) {
                try { level = Integer.parseInt(levelStr.trim()); } catch (NumberFormatException ignored) { }
            }

            Integer sortOrder = null;
            if (orderStr != null) {
                try { sortOrder = Integer.parseInt(orderStr.trim()); } catch (NumberFormatException ignored) { }
            }

            TaxonomyNode node = new TaxonomyNode();
            node.setCode(code);
            node.setUuid(uuid);
            node.setNameEn(name);
            node.setDescriptionEn(truncate(description, 5000));
            node.setParentCode(parentCode);
            node.setTaxonomyRoot(sheetPrefix);
            node.setLevel(level);
            node.setDataset(dataset);
            node.setExternalId(externalId);
            node.setSource(source);
            node.setReference(truncate(reference, 5000));
            node.setSortOrder(sortOrder);
            node.setState(state);
            nodeMap.put(code, node);
        }
    }

    private String cellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        String val = switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> null;
        };
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    // ---- read-side methods ----

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> getFullTree() {
        List<TaxonomyNode> roots = repository.findByParentIsNullOrderByCodeAsc();
        List<TaxonomyNodeDto> dtos = new ArrayList<>();
        for (TaxonomyNode root : roots) {
            dtos.add(toDto(root));
        }
        return dtos;
    }

    public TaxonomyNodeDto toDto(TaxonomyNode node) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setId(node.getId());
        dto.setCode(node.getCode());
        dto.setUuid(node.getUuid());
        dto.setNameEn(node.getNameEn());
        dto.setNameDe(node.getNameDe());
        dto.setDescriptionEn(node.getDescriptionEn());
        dto.setDescriptionDe(node.getDescriptionDe());
        dto.setParentCode(node.getParentCode());
        dto.setTaxonomyRoot(node.getTaxonomyRoot());
        dto.setLevel(node.getLevel());
        dto.setDataset(node.getDataset());
        dto.setExternalId(node.getExternalId());
        dto.setSource(node.getSource());
        dto.setReference(node.getReference());
        dto.setSortOrder(node.getSortOrder());
        dto.setState(node.getState());
        List<TaxonomyNodeDto> childDtos = new ArrayList<>();
        for (TaxonomyNode child : node.getChildren()) {
            childDtos.add(toDto(child));
        }
        dto.setChildren(childDtos);
        List<TaxonomyRelationDto> outgoing = new ArrayList<>();
        for (TaxonomyRelation rel : node.getOutgoingRelations()) {
            outgoing.add(relationToDto(rel));
        }
        dto.setOutgoingRelations(outgoing);
        List<TaxonomyRelationDto> incoming = new ArrayList<>();
        for (TaxonomyRelation rel : node.getIncomingRelations()) {
            incoming.add(relationToDto(rel));
        }
        dto.setIncomingRelations(incoming);
        return dto;
    }

    private TaxonomyRelationDto relationToDto(TaxonomyRelation relation) {
        TaxonomyRelationDto dto = new TaxonomyRelationDto();
        dto.setId(relation.getId());
        dto.setSourceCode(relation.getSourceNode().getCode());
        dto.setSourceName(relation.getSourceNode().getNameEn());
        dto.setTargetCode(relation.getTargetNode().getCode());
        dto.setTargetName(relation.getTargetNode().getNameEn());
        dto.setRelationType(relation.getRelationType().name());
        dto.setDescription(relation.getDescription());
        dto.setProvenance(relation.getProvenance());
        dto.setWeight(relation.getWeight());
        dto.setBidirectional(relation.isBidirectional());
        return dto;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNode> getRootNodes() {
        return repository.findByParentIsNullOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNode> getChildrenOf(String parentCode) {
        return repository.findByParentCodeOrderByNameEnAsc(parentCode);
    }

    /**
     * Returns the node identified by {@code code}, or {@code null} if not found.
     */
    @Transactional(readOnly = true)
    public TaxonomyNode getNodeByCode(String code) {
        return repository.findByCode(code).orElse(null);
    }

    /**
     * Returns the path from the root to the node identified by {@code code} (inclusive),
     * ordered from root to leaf. Returns an empty list if the node is not found.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyNode> getPathToRoot(String code) {
        TaxonomyNode node = repository.findByCode(code).orElse(null);
        if (node == null) return List.of();
        LinkedList<TaxonomyNode> path = new LinkedList<>();
        TaxonomyNode current = node;
        while (current != null) {
            path.addFirst(current);
            String parentCode = current.getParentCode();
            if (parentCode == null || parentCode.isBlank()) break;
            current = repository.findByCode(parentCode).orElse(null);
        }
        return List.copyOf(path);
    }

    public TaxonomyNodeDto applyScores(TaxonomyNodeDto dto, Map<String, Integer> scores) {
        if (scores.containsKey(dto.getCode())) {
            dto.setMatchPercentage(scores.get(dto.getCode()));
        }
        List<TaxonomyNodeDto> updatedChildren = new ArrayList<>();
        for (TaxonomyNodeDto child : dto.getChildren()) {
            updatedChildren.add(applyScores(child, scores));
        }
        dto.setChildren(updatedChildren);
        return dto;
    }
}
