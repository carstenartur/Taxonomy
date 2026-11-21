package com.taxonomy.sparx.mapping;

/** Optional human assessments from the BWRequirement stereotype in the ADMBw 2026.05 MDG. */
public enum AdmbwRequirementField {
    CATEGORY("requirementCategory", "Anforderungsart"),
    CRITICALITY("criticality", "Kritikalität"),
    ASSESSMENT("operationalAssessment", "Operative Bewertung"),
    PRIORITY("procurementPriority", "Priorität Vergabe"),
    QUALITY_STATUS("qualityStatus", "QS_Status");

    private final String planningKey;
    private final String nativeTag;

    AdmbwRequirementField(String planningKey, String nativeTag) {
        this.planningKey = planningKey;
        this.nativeTag = nativeTag;
    }

    public String planningKey() { return planningKey; }
    public String nativeTag() { return nativeTag; }
}
