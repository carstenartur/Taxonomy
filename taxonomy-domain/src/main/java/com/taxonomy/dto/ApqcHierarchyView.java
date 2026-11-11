package com.taxonomy.dto;
import java.util.List;
public record ApqcHierarchyView(List<ApqcHierarchyNode> nodes, GraphQueryEvidence evidence) {
    public ApqcHierarchyView { nodes = List.copyOf(nodes); }
}
