package com.taxonomy.interop;

import com.taxonomy.workspace.service.WorkspaceContext;
import java.time.Instant;
import java.util.List;

/**
 * Portfolio operations required by reviewed external-tool interoperability.
 * The application binds these operations to the existing scoped portfolio services;
 * no portfolio entities or implementation DTOs cross this boundary.
 */
public interface IntegrationPortfolioPort {
    record ProjectData(Long id, String title) {}
    record VersionData(String text, Instant createdAt) {}
    /** Status retains the portfolio's serialized enum name, including future values. */
    record RequirementData(Long id, String requirementKey, String title, String status,
                           Long currentVersionId, Instant updatedAt, VersionData currentVersion) {
        public boolean archived() { return "ARCHIVED".equals(status); }
        public boolean approved() { return "APPROVED".equals(status); }
    }
    record RequirementsPage(List<RequirementData> requirements, boolean hasNext) {}
    record ImportProvenance(String sectionReference, String originalText) {}
    record ImportedRequirement(String key, String title, String text, String rationale,
                               ImportProvenance source) {}

    List<ProjectData> listProjects(String username, WorkspaceContext context);
    ProjectData getProject(Long projectId, String username, WorkspaceContext context);
    void requireProject(Long projectId, String username, WorkspaceContext context);
    void requireProjectForUpdate(Long projectId, String username, WorkspaceContext context);
    List<RequirementData> listRequirements(Long projectId, String username, WorkspaceContext context);
    RequirementsPage listApprovedRequirements(Long projectId, String username, WorkspaceContext context,
                                              int page, int pageSize);
    RequirementData getRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context);
    RequirementData createRequirement(Long projectId, ImportedRequirement request,
                                      String username, WorkspaceContext context);
    void archiveRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context);
    void updateRequirement(Long projectId, Long requirementId, String title, boolean requiresReview,
                           String username, WorkspaceContext context);
    void addRequirementVersion(Long projectId, Long requirementId, String text, String rationale,
                               ImportProvenance source, String username, WorkspaceContext context);
    String contributeTo(String source, String username, WorkspaceContext context);
}
