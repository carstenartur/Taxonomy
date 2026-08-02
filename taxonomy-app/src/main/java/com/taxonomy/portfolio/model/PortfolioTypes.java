package com.taxonomy.portfolio.model;

/**
 * Stable vocabulary for the project/requirement/solution/product portfolio.
 *
 * <p>The enums live in one framework-neutral holder so persistence entities,
 * API contracts and aggregation services use exactly the same values.</p>
 */
public final class PortfolioTypes {

    private PortfolioTypes() {
    }

    public enum ProjectStatus {
        PLANNING,
        ACTIVE,
        ON_HOLD,
        COMPLETED,
        ARCHIVED
    }

    public enum RequirementStatus {
        DRAFT,
        APPROVED,
        IMPLEMENTING,
        SATISFIED,
        REJECTED,
        ARCHIVED
    }

    public enum RequirementType {
        FUNCTIONAL,
        NON_FUNCTIONAL,
        ORGANIZATIONAL,
        TECHNICAL,
        LEGAL,
        PROCESS,
        SECURITY,
        DATA,
        OTHER
    }

    public enum Criticality {
        LOW,
        MEDIUM,
        HIGH,
        MISSION_CRITICAL
    }

    public enum ReviewStatus {
        PROPOSED,
        CONFIRMED,
        REJECTED
    }

    public enum AnalysisStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    public enum MappingOrigin {
        DIRECT,
        PROPAGATED,
        ENRICHED,
        GAP_PROPOSAL,
        MANUAL
    }

    /**
     * Decision about how a relevant architecture element is to be realised.
     * Relevance alone never implies CREATE or PROCURE; the default is UNDECIDED.
     */
    public enum ActionStatus {
        SATISFIED_AS_IS,
        REUSE,
        CHANGE,
        CREATE,
        PROCURE,
        ORGANIZATIONAL,
        RETIRE_OR_REPLACE,
        UNDECIDED
    }

    public enum LifecycleStatus {
        PLANNED,
        ACTIVE,
        DEPRECATED,
        RETIRED
    }

    public enum OperatingModel {
        ON_PREMISES,
        PRIVATE_CLOUD,
        PUBLIC_CLOUD,
        SAAS,
        HYBRID,
        ORGANIZATIONAL,
        UNSPECIFIED
    }

    public enum SolutionType {
        BUSINESS,
        PROCESS,
        ORGANIZATIONAL,
        APPLICATION,
        PLATFORM,
        SERVICE,
        DATA,
        INFRASTRUCTURE,
        SECURITY,
        OTHER
    }

    public enum ProjectSolutionStatus {
        PROPOSED,
        EVALUATED,
        SELECTED,
        IMPLEMENTED,
        REJECTED
    }

    public enum RequirementSolutionRole {
        USES,
        CHANGES,
        CAUSES
    }

    public enum ProductStatus {
        CANDIDATE,
        ACTIVE,
        DEPRECATED,
        END_OF_SUPPORT,
        WITHDRAWN
    }

    public enum ProductSelectionStatus {
        CANDIDATE,
        SHORTLISTED,
        SELECTED,
        REJECTED
    }

    public enum ConflictType {
        HOSTING,
        LIFECYCLE,
        SECURITY,
        DATA_LOCATION,
        AVAILABILITY,
        PLATFORM,
        OTHER
    }

    public enum ConflictStatus {
        PROPOSED,
        CONFIRMED,
        REJECTED,
        RESOLVED
    }
}
