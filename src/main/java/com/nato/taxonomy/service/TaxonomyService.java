package com.nato.taxonomy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyService.class);

    private final TaxonomyNodeRepository repository;
    private final ObjectMapper objectMapper;

    public TaxonomyService(TaxonomyNodeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    @Transactional
    public void loadTaxonomyFromJson() {
        if (repository.count() > 0) {
            log.info("Taxonomy already loaded, skipping.");
            return;
        }
        try {
            ClassPathResource resource = new ClassPathResource("data/nato-taxonomy.json");
            try (InputStream is = resource.getInputStream()) {
                List<Map<String, Object>> rawList = objectMapper.readValue(is,
                        new TypeReference<>() {});
                List<TaxonomyNode> rootNodes = new ArrayList<>();
                for (Map<String, Object> raw : rawList) {
                    TaxonomyNode node = buildNodeTree(raw, null, 0);
                    rootNodes.add(node);
                }
                repository.saveAll(rootNodes);
                log.info("Loaded {} root taxonomy nodes.", rootNodes.size());
            }
        } catch (Exception e) {
            log.error("Failed to load taxonomy from JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private TaxonomyNode buildNodeTree(Map<String, Object> raw, TaxonomyNode parent, int level) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode((String) raw.get("code"));
        node.setName((String) raw.get("name"));
        node.setDescription((String) raw.get("description"));
        node.setParentCode((String) raw.get("parentCode"));
        node.setLevel(level);

        // Determine taxonomy root (top-level code: C1..C8)
        if (parent == null) {
            node.setTaxonomyRoot(node.getCode());
        } else {
            node.setTaxonomyRoot(parent.getTaxonomyRoot());
        }
        node.setParent(parent);

        List<Map<String, Object>> childrenRaw = (List<Map<String, Object>>) raw.get("children");
        if (childrenRaw != null) {
            for (Map<String, Object> childRaw : childrenRaw) {
                TaxonomyNode child = buildNodeTree(childRaw, node, level + 1);
                node.getChildren().add(child);
            }
        }
        return node;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> getFullTree() {
        List<TaxonomyNode> roots = repository.findByParentIsNullOrderByCodeAsc();
        List<TaxonomyNodeDto> dtos = new ArrayList<>();
        for (TaxonomyNode root : roots) {
            dtos.add(toDto(root));
        }
        return dtos;
    }

    private TaxonomyNodeDto toDto(TaxonomyNode node) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setId(node.getId());
        dto.setCode(node.getCode());
        dto.setName(node.getName());
        dto.setDescription(node.getDescription());
        dto.setParentCode(node.getParentCode());
        dto.setTaxonomyRoot(node.getTaxonomyRoot());
        dto.setLevel(node.getLevel());
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
        return repository.findByParentCodeOrderByNameAsc(parentCode);
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
