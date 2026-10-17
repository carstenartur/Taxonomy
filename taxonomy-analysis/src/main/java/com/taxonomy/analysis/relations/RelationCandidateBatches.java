package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchModel.Node;
import java.util.*;
import java.util.function.ToIntFunction;

/** Packs whole descriptions using the assembled prompt; never truncates catalogue evidence. */
final class RelationCandidateBatches {
    private RelationCandidateBatches() { }

    static List<List<Node>> split(List<Node> nodes, int countLimit, int characterLimit,
                                  ToIntFunction<List<Node>> promptSize) {
        List<List<Node>> result = new ArrayList<>();
        List<Node> batch = new ArrayList<>();
        for (Node node : nodes) {
            var proposed = new ArrayList<>(batch); proposed.add(node);
            if (!batch.isEmpty() && (proposed.size() > countLimit
                    || !node.root().equals(batch.getFirst().root()) || promptSize.applyAsInt(proposed) > characterLimit)) {
                result.add(List.copyOf(batch)); batch.clear();
            }
            batch.add(node);
        }
        if (!batch.isEmpty()) result.add(List.copyOf(batch));
        return List.copyOf(result);
    }
}
