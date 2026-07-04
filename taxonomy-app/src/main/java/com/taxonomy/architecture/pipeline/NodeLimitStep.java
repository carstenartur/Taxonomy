package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Truncates the element and relationship lists to the requested maximum node count.
 *
 * <p>When {@code maxArchitectureNodes} is positive and the element list exceeds
 * that limit, only the first {@code maxArchitectureNodes} elements are kept and
 * any relationships whose endpoints no longer appear in the kept set are removed.
 * A note is added to the view.
 *
 * <p>This step is a pure function with no external dependencies and can be
 * unit-tested without a Spring context.
 */
public class NodeLimitStep {

    /**
     * Applies the node-count limit stored in {@code ctx.maxArchitectureNodes}.
     * Has no effect when the limit is 0 or when the element count is within the limit.
     */
    public void execute(ArchitectureViewContext ctx) {
        int maxNodes = ctx.getMaxArchitectureNodes();
        List<RequirementElementView> elements = ctx.getElements();
        List<RequirementRelationshipView> relationships = ctx.getRelationships();

        if (maxNodes <= 0 || elements.size() <= maxNodes) {
            return;
        }

        Set<String> keptCodes = elements.subList(0, maxNodes).stream()
                .map(RequirementElementView::getNodeCode)
                .collect(Collectors.toSet());

        ctx.setElements(new ArrayList<>(elements.subList(0, maxNodes)));
        ctx.setRelationships(relationships.stream()
                .filter(r -> keptCodes.contains(r.getSourceCode())
                        && keptCodes.contains(r.getTargetCode()))
                .collect(Collectors.toList()));

        ctx.getView().getNotes().add("Architecture view limited to " + maxNodes + " elements.");
    }
}
