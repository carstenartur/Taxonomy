package com.taxonomy.templates;

/**
 * Stable, non-sensitive availability contract for the mandatory decision-report template.
 */
public final class DecisionReportAvailabilityContract {

    public static final String CAPABILITY = "decision-report-docx";
    public static final String PROBLEM_CODE =
            "DECISION_REPORT_TEMPLATE_UNAVAILABLE";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String DEGRADED = "DEGRADED";
    public static final String SAFE_UNAVAILABLE_SUMMARY =
            "Required decision-report template is unavailable";
    public static final String REMEDIATION =
            "Restore or upload a valid decision-report template";

    private DecisionReportAvailabilityContract() {
    }
}
