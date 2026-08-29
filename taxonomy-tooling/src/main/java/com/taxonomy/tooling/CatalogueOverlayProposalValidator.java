package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.Patch;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceCatalogue;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared hierarchy invariants for both reviewed and proposed mappings. */
final class CatalogueOverlayProposalValidator {

    private CatalogueOverlayProposalValidator() {
    }

    static void validateHierarchy(
            Map<String, String> parents,
            Map<String, SourceNode> nodes,
            String root,
            String phase) {
        SourceCatalogue source = new SourceCatalogue(root, nodes);
        for (Map.Entry<String, String> entry : parents.entrySet()) {
            String code = entry.getKey();
            String parent = entry.getValue();
            requireKnownParent(code, parent, source, root);
            if (code.equals(parent)) {
                throw new IllegalArgumentException(
                        phase + " hierarchy self-parents " + code);
            }
            if (!root.equals(rootOf(code)) || !root.equals(rootOf(parent))) {
                throw new IllegalArgumentException(
                        phase + " hierarchy crosses roots via " + code + " -> " + parent);
            }
        }

        Map<String, VisitState> states = new HashMap<>();
        for (String code : parents.keySet()) {
            visit(code, parents, root, states, phase);
        }
    }

    static void validateOverlayProductLeaves(
            Map<String, Patch> patches,
            Map<String, String> parents,
            String phase) {
        Map<String, String> roles = patches.values().stream()
                .collect(Collectors.toMap(Patch::code, Patch::analysisRole));
        validateProductLeaves(roles, parents, phase);
    }

    static void validateProductLeaves(
            Map<String, String> roles,
            Map<String, String> parents,
            String phase) {
        Set<String> parentCodes = new HashSet<>(parents.values());
        List<String> invalid = roles.entrySet().stream()
                .filter(entry -> CatalogueOverlayProposalGenerator.ROLE_PRODUCT
                        .equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .filter(parentCodes::contains)
                .sorted()
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                    phase + " classifies non-leaf nodes as PRODUCT: " + invalid);
        }
    }

    static boolean createsCycle(
            String sourceCode,
            String candidateParent,
            Map<String, String> parents) {
        String current = candidateParent;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            if (sourceCode.equals(current)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    static void requireKnownParent(
            String code,
            String parent,
            SourceCatalogue source,
            String root) {
        if (parent == null || parent.isBlank()) {
            throw new IllegalArgumentException(code + " has no parent code");
        }
        if (!root.equals(parent) && !source.nodes().containsKey(parent)) {
            throw new IllegalArgumentException(
                    code + " references unknown parent " + parent);
        }
    }

    private static void visit(
            String code,
            Map<String, String> parents,
            String root,
            Map<String, VisitState> states,
            String phase) {
        VisitState state = states.get(code);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw new IllegalArgumentException(
                    phase + " hierarchy contains a cycle at " + code);
        }
        states.put(code, VisitState.VISITING);
        String parent = parents.get(code);
        if (parent != null && !root.equals(parent)) {
            visit(parent, parents, root, states, phase);
        }
        states.put(code, VisitState.VISITED);
    }

    private static String rootOf(String code) {
        if (code == null) {
            return null;
        }
        int dash = code.indexOf('-');
        return dash < 0 ? code : code.substring(0, dash);
    }

    private enum VisitState { VISITING, VISITED }
}
