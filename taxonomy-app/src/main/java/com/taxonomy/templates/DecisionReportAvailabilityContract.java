package com.taxonomy.templates;

/** Stable machine and operator contract for the decision-report DOCX capability. */
public final class DecisionReportAvailabilityContract {

    public static final String CAPABILITY = "decision-report-docx";
    public static final String PROBLEM_CODE =
            "DECISION_REPORT_TEMPLATE_UNAVAILABLE";
    public static final String HEALTH_ERROR =
            "Required decision-report template is unavailable or invalid.";
    public static final String RESPONSE_MESSAGE =
            "Decision report generation is temporarily unavailable because its "
                    + "template requires administrator attention.";
    public static final String REMEDIATION =
            "Upload, validate, or restore a valid decision-report template in "
                    + "the document-template administration page.";

    private DecisionReportAvailabilityContract() {
    }
}
