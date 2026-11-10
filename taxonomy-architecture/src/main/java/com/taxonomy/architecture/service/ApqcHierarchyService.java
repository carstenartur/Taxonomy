package com.taxonomy.architecture.service;

import com.taxonomy.dto.ApqcHierarchyNode;
import com.taxonomy.dto.ApqcHierarchyView;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service that extracts the APQC process hierarchy from one selected canonical commit.
 *
 * <p>Reads the frozen DSL snapshot for elements with the
 * {@code x-source-framework: "apqc"} extension and reconstructs the
 * parent–child hierarchy using {@code x-apqc-parent} chains.
 */
@Service
public class ApqcHierarchyService {

    private final GraphSnapshotReadService snapshots;

    public ApqcHierarchyService(GraphSnapshotReadService snapshots) {
        this.snapshots = snapshots;
    }

    public ApqcHierarchyView buildHierarchy(RepositoryContext context, String commitId) {
        var selected = snapshots.read(context, commitId);
        var snapshot = selected.proof();
        var apqcElements = selected.nodes().values().stream()
                .filter(node -> "apqc".equals(node.extensions().get("x-source-framework"))).toList();

        // Build lookup map and tree
        Map<String, ApqcHierarchyNode> nodeMap = new LinkedHashMap<>();
        for (var el : apqcElements) {
            Map<String, String> ext = el.extensions();
            String level = ext.getOrDefault("x-apqc-level", "Unknown");
            String pcfId = ext.getOrDefault("x-apqc-pcf-id", "");
            String parentId = ext.get("x-apqc-parent");
            String taxonomyRoot = el.taxonomyRoot();
            nodeMap.put(el.id(), new ApqcHierarchyNode(
                    el.id(), el.title(), level, pcfId, parentId,
                    taxonomyRoot != null ? taxonomyRoot : "",
                    new ArrayList<>()));
        }

        // A cycle must not silently disappear as an empty hierarchy.
        for (ApqcHierarchyNode node : nodeMap.values()) {
            Set<String> ancestors = new HashSet<>();
            ApqcHierarchyNode current = node;
            while (current != null) {
                if (!ancestors.add(current.id())) throw new IllegalStateException("APQC parent cycle in selected commit");
                current = nodeMap.get(current.parentId());
            }
        }
        // Link children to parents
        List<ApqcHierarchyNode> roots = new ArrayList<>();
        for (ApqcHierarchyNode node : nodeMap.values()) {
            if (node.parentId() != null && nodeMap.containsKey(node.parentId())) {
                nodeMap.get(node.parentId()).children().add(node);
            } else {
                roots.add(node);
            }
        }

        snapshots.verifyHead(selected);
        return new ApqcHierarchyView(roots, ArchitectureGraphQueryServiceImpl.evidence(snapshot, 0, 0, "apqc-parent-hierarchy-v1"));
    }
}
