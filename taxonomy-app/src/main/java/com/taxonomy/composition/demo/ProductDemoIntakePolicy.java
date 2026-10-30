package com.taxonomy.composition.demo;

import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.RequirementIntakePolicy;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo replay has no intake fixture: fail visibly instead of falling back to a live provider. */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@Component
public class ProductDemoIntakePolicy implements RequirementIntakePolicy {
    private final ProductDemoRunRepository runs;
    public ProductDemoIntakePolicy(ProductDemoRunRepository runs) { this.runs = runs; }
    @Override @Transactional(readOnly = true)
    public boolean allowsProviderReview(Long projectId, String username, WorkspaceContext context) {
        return projectId == null || runs.findByProjectIdAndScopeKey(projectId, PortfolioScope.key(username, context)).isEmpty();
    }
}
