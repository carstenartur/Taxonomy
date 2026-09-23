package com.taxonomy.portfolio.service;

import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementElementView;

/** The existing scalar snapshot projection must handle the new evidence origin without fake confidence. */
public final class RelationEvidenceMappingContract {
    public static void main(String[] args) throws Exception {
        var origin = PortfolioAnalysisPersistenceService.class.getDeclaredMethod("mappingOrigin", NodeOrigin.class);
        origin.setAccessible(true);
        if (origin.invoke(null, NodeOrigin.RELATION_EVIDENCE) == null) throw new AssertionError("missing coarse origin");
        var confidence = PortfolioAnalysisPersistenceService.class.getDeclaredMethod("confidence", RequirementElementView.class);
        confidence.setAccessible(true);
        var element = new RequirementElementView(); element.setOrigin(NodeOrigin.RELATION_EVIDENCE); element.setDirectLlmScore(95);
        if (((Double) confidence.invoke(null, element)) != 0.0) throw new AssertionError("relevance converted to uncalibrated confidence");
        element.setOrigin(NodeOrigin.DIRECT_SCORED);
        if (((Double) confidence.invoke(null, element)) != 0.95) throw new AssertionError("legacy confidence changed");
        System.out.println("Relation evidence mapping: origin accepted; scoped confidence not invented; legacy preserved");
    }
}
