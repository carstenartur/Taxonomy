package com.taxonomy.architecture.decision;

/** Localized descriptions of recorded provenance, created with the report model. */
final class DecisionReportSourceLabels {
    private final boolean german;
    DecisionReportSourceLabels(String languageTag) {
        german = languageTag != null && languageTag.toLowerCase(java.util.Locale.ROOT).startsWith("de");
    }
    public String snapshotFingerprint(String hash) { return (german ? "Snapshot-Fingerabdruck " : "snapshot fingerprint ") + hash; }
    public String historicalResourceNotRecorded() { return german ? "Im historischen Snapshot nicht separat gespeichert" : "not persisted separately in the historical snapshot"; }
    public String immutableSnapshotSource() { return german ? "Unveränderlicher Anforderungs-Analysesnapshot" : "Immutable requirement analysis snapshot"; }
}
