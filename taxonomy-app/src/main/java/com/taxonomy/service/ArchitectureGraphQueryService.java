package com.taxonomy.service;

import com.taxonomy.dto.ChangeImpactView;
import com.taxonomy.dto.GraphNeighborhoodView;
import com.taxonomy.dto.RequirementImpactView;

/**
 * Service for graph-based architecture queries over the taxonomy.
 *
 * <p>Provides three categories of queries:
 * <ol>
 *   <li><b>Requirement Impact</b> — Which elements are affected by a business requirement?</li>
 *   <li><b>Upstream / Downstream</b> — What supports or depends on a given element?</li>
 *   <li><b>Failure / Change Impact</b> — What is affected if an element fails or changes?</li>
 * </ol>
 *
 * <p>All queries traverse only ACCEPTED relations (SUPPORTS, REALIZES, USES) and
 * apply relevance decay per hop.
 */
public interface ArchitectureGraphQueryService {

    /**
     * Finds elements impacted by a business requirement text.
     * Uses the LLM scoring pipeline to identify anchors, then traverses
     * the graph to find all affected elements.
     *
     * @param scores    map of nodeCode → LLM score (0–100)
     * @param businessText  the requirement text
     * @param maxHops   maximum graph hops from anchors (1–5, clamped)
     * @return view of impacted elements and relationships
     */
    RequirementImpactView findImpactForRequirement(java.util.Map<String, Integer> scores,
                                                    String businessText, int maxHops);

    /**
     * Finds upstream neighbors of a node (elements that feed into it).
     * Traverses incoming edges only.
     *
     * @param nodeCode  the starting node code
     * @param maxHops   maximum graph hops (1–5, clamped)
     * @return upstream neighborhood view
     */
    GraphNeighborhoodView findUpstream(String nodeCode, int maxHops);

    /**
     * Finds downstream neighbors of a node (elements that depend on it).
     * Traverses outgoing edges only.
     *
     * @param nodeCode  the starting node code
     * @param maxHops   maximum graph hops (1–5, clamped)
     * @return downstream neighborhood view
     */
    GraphNeighborhoodView findDownstream(String nodeCode, int maxHops);

    /**
     * Analyzes failure/change impact for a node.
     * Determines directly and indirectly affected elements using
     * relation-type-weighted propagation.
     *
     * @param nodeCode  the node that fails or changes
     * @param maxHops   maximum graph hops (1–5, clamped)
     * @return change impact view with directly and indirectly affected elements
     */
    ChangeImpactView findFailureImpact(String nodeCode, int maxHops);
}
