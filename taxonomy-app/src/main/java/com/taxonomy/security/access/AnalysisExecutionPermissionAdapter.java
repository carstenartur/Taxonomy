package com.taxonomy.security.access;

import com.taxonomy.portfolio.service.AnalysisExecutionPermission;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Applies the same role/scope capability to conditional model admission. */
@Component
@ConditionalOnFeature("portfolio")
public class AnalysisExecutionPermissionAdapter implements AnalysisExecutionPermission {
    @Override
    public boolean mayExecute(String actor) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && actor != null && actor.equals(authentication.getName())
                && ApiAccessPolicy.hasPermission(authentication, ApiPermission.ANALYSIS_RUN);
    }
}
