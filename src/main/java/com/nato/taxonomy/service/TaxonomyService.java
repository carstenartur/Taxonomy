package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;

@Service
public class TaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyService.class);

    private static final String CATALOGUE_PATH = "data/C3_Taxonomy_Catalogue_25AUG2025.xlsx";

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
    private final SearchService searchService;
    private final LocalEmbeddingService localEmbeddingService;

    public TaxonomyService(TaxonomyNodeRepository repository, SearchService searchService,
                           LocalEmbeddingService localEmbeddingService) {
        this.repository = repository;
        this.searchService = searchService;
        this.localEmbeddingService = localEmbeddingService;
    }

    @PostConstruct
    @Transactional
    public void loadTaxonomyFromExcel() {
        repository.deleteAll();
        try {
            ClassPathResource resource = new ClassPathResource(CATALOGUE_PATH);
            try (InputStream is = resource.getInputStream();
                 Workbook workbook = new XSSFWorkbook(is)) {

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
                    root.setDescriptionEn("NATO C3 Taxonomy – " + sheetName);
                    root.setTaxonomyRoot(prefix);
                    root.setLevel(0);
                    nodeMap.put(prefix, root);
                    virtualRoots.add(root);
                }

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

                // 4. Persist (save virtual roots first; cascade saves children)
                repository.saveAll(virtualRoots);
                log.info("Taxonomy loaded: {} nodes from {} sheets.",
                        nodeMap.size(), SHEET_PREFIXES.size());

                // 5. Build Lucene full-text search index
                searchService.buildIndex(nodeMap.values());
                log.info("Full-text search index built successfully.");

                // 6. Invalidate the KNN vector index so it is rebuilt lazily on first
                //    LOCAL_ONNX use (safe no-op when LOCAL_ONNX is not configured)
                localEmbeddingService.invalidateVectorIndex();
            }
        } catch (Exception e) {
            log.error("Failed to load taxonomy from Excel", e);
        }
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
