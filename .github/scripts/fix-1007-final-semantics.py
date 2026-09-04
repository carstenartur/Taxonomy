#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# Preserve null on the missing side of an added/removed snapshot score. The former
# conditional expression unboxed a nullable Integer because its other branch was int.
diff_path = Path(
    "taxonomy-app/src/main/java/com/taxonomy/portfolio/service/AnalysisScoreDiff.java"
)
diff_text = diff_path.read_text(encoding="utf-8")
diff_text = replace_once(
    diff_text,
    "        return detail == null ? rawScore : detail.rawScore();",
    "        if (detail != null) {\n"
    "            return detail.rawScore();\n"
    "        }\n"
    "        return rawScore;",
    "null-safe raw score",
)
diff_path.write_text(diff_text, encoding="utf-8")


diff_test_path = Path(
    "taxonomy-app/src/test/java/com/taxonomy/portfolio/service/AnalysisScoreDiffTest.java"
)
diff_test = diff_test_path.read_text(encoding="utf-8")
added_score_test = '''    @Test
    void addedScorePreservesNullEvidenceOnTheMissingSide() {
        TaxonomyNodeDto root = node("CP", null, "CATEGORY");
        AnalysisResult newer = new AnalysisResult(Map.of("CP", 75), List.of(root));

        ScoreChange change = AnalysisScoreDiff.between(null, newer).get("CP");

        assertThat(change).isNotNull();
        assertThat(change.oldScore()).isNull();
        assertThat(change.oldRawScore()).isNull();
        assertThat(change.newScore()).isEqualTo(75);
        assertThat(change.newRawScore()).isEqualTo(75);
        assertThat(change.newKind()).isEqualTo(AnalysisScoreKind.ROOT_RELEVANCE);
    }

'''
diff_test = replace_once(
    diff_test,
    "    private AnalysisResult productAnalysis(\n",
    added_score_test + "    private AnalysisResult productAnalysis(\n",
    "added score regression",
)
diff_test_path.write_text(diff_test, encoding="utf-8")


# Snapshot report generation deliberately enriches the format-neutral report with
# typed score evidence and an evidence-bound digest. Test value preservation rather
# than obsolete object identity.
snapshot_test_path = Path(
    "taxonomy-app/src/test/java/com/taxonomy/portfolio/report/"
    "DecisionRationaleSnapshotReportTest.java"
)
snapshot_test = snapshot_test_path.read_text(encoding="utf-8")
snapshot_test = replace_once(
    snapshot_test,
    "        assertThat(actual).isSameAs(expected);",
    "        assertThat(actual).isNotSameAs(expected);\n"
    "        assertThat(actual.title()).isEqualTo(expected.title());\n"
    "        assertThat(actual.requirement()).isEqualTo(expected.requirement());\n"
    "        assertThat(actual.status()).isEqualTo(expected.status());\n"
    "        assertThat(actual.scoreDetails()).containsKey(\"CP\");\n"
    "        assertThat(actual.metadata().analysisSnapshotFingerprintSha256())\n"
    "                .hasSize(64)\n"
    "                .isNotEqualTo(\n"
    "                        expected.metadata().analysisSnapshotFingerprintSha256());",
    "snapshot enrichment expectation",
)
snapshot_test = replace_once(
    snapshot_test,
    "        assertThat(input.scores()).containsEntry(\"CP\", 100);",
    "        assertThat(input.scores()).containsEntry(\"CP\", 100);\n"
    "        assertThat(input.scoreDetails()).containsKey(\"CP\");",
    "snapshot input score details",
)
snapshot_test_path.write_text(snapshot_test, encoding="utf-8")


# Exercise the same complete typed score envelope that the browser export action
# sends. Reusing effective scores as raw suitability would apply product weighting twice.
e2e_path = Path(".github/scripts/document-template-report-download.mjs")
e2e = e2e_path.read_text(encoding="utf-8")
e2e = replace_once(
    e2e,
    "      scores: state?.currentScores || {},\n"
    "      reasons: state?.currentReasons || {},",
    "      scores: state?.currentScores || {},\n"
    "      rawScores: state?.currentRawScores || state?.currentScores || {},\n"
    "      effectiveScores: state?.currentEffectiveScores || state?.currentScores || {},\n"
    "      scoreDetails: state?.currentScoreDetails || {},\n"
    "      productSuitabilityScores: state?.currentProductSuitabilityScores || {},\n"
    "      scoreSemanticsVersion: state?.scoreSemanticsVersion || 0,\n"
    "      reasons: state?.currentReasons || {},",
    "document E2E score envelope",
)
e2e = replace_once(
    e2e,
    "      `Completed product coverage gaps require FINAL_WITH_WARNINGS, got ${report.status}`);",
    "      `Completed product coverage gaps require FINAL_WITH_WARNINGS, got ${report.status}; `\n"
    "        + `warnings=${JSON.stringify(report.warnings || [])}`);",
    "document E2E status diagnostic",
)
e2e_path.write_text(e2e, encoding="utf-8")
