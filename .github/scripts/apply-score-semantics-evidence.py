#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_portfolio_dtos() -> None:
    path = Path("taxonomy-app/src/main/java/com/taxonomy/portfolio/dto/PortfolioDtos.java")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "import com.taxonomy.dto.AnalysisResult;\n",
        "import com.taxonomy.dto.AnalysisResult;\n"
        "import com.taxonomy.dto.AnalysisScoreKind;\n",
        "PortfolioDtos AnalysisScoreKind import",
    )
    text = replace_once(
        text,
        "    public record ScoreChange(Integer oldScore, Integer newScore) {\n"
        "    }",
        "    /**\n"
        "     * One node's effective-score change plus the complete meaning of that value.\n"
        "     * The two original components remain first for source and JSON compatibility.\n"
        "     */\n"
        "    public record ScoreChange(\n"
        "            Integer oldScore,\n"
        "            Integer newScore,\n"
        "            Integer oldRawScore,\n"
        "            Integer newRawScore,\n"
        "            AnalysisScoreKind oldKind,\n"
        "            AnalysisScoreKind newKind,\n"
        "            String oldParentCode,\n"
        "            String newParentCode,\n"
        "            Integer oldParentScore,\n"
        "            Integer newParentScore) {\n\n"
        "        public ScoreChange(Integer oldScore, Integer newScore) {\n"
        "            this(oldScore, newScore, oldScore, newScore,\n"
        "                    null, null, null, null, null, null);\n"
        "        }\n"
        "    }",
        "PortfolioDtos ScoreChange record",
    )
    path.write_text(text, encoding="utf-8")


def patch_snapshot_diff() -> None:
    path = Path(
        "taxonomy-app/src/main/java/com/taxonomy/portfolio/service/"
        "PortfolioAnalysisPersistenceService.java"
    )
    text = path.read_text(encoding="utf-8")
    start_marker = "        Map<String, Integer> oldScores ="
    end_marker = "\n\n        Set<String> oldElements ="
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit("PortfolioAnalysisPersistenceService: score diff start not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit("PortfolioAnalysisPersistenceService: score diff end not found")
    replacement = (
        "        Map<String, ScoreChange> scoreChanges =\n"
        "                AnalysisScoreDiff.between(olderAnalysis, newerAnalysis);"
    )
    text = text[:start] + replacement + text[end:]
    path.write_text(text, encoding="utf-8")


def patch_decision_report_fingerprint() -> None:
    path = Path(
        "taxonomy-app/src/main/java/com/taxonomy/architecture/decision/"
        "DecisionRationaleReportService.java"
    )
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "import com.taxonomy.dto.ProductCoverageGap;\n",
        "import com.taxonomy.dto.ProductCoverageGap;\n"
        "import com.taxonomy.dto.TaxonomyDataFingerprint;\n",
        "DecisionRationaleReportService TaxonomyDataFingerprint import",
    )
    text = replace_once(
        text,
        "        String actualDataFingerprint = fingerprint(nodesByCode.values());",
        "        String actualDataFingerprint = TaxonomyDataFingerprint.sha256(\n"
        "                input.taxonomyTree().isEmpty()\n"
        "                        ? taxonomyService.getFullTree() : input.taxonomyTree());",
        "DecisionRationaleReportService fingerprint call",
    )
    start_marker = "    private String fingerprint(Collection<TaxonomyNode> nodes) {"
    end_marker = "    private String fingerprintAnalysis("
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit("DecisionRationaleReportService: legacy fingerprint start not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit("DecisionRationaleReportService: analysis fingerprint start not found")
    text = text[:start] + text[end:]
    path.write_text(text, encoding="utf-8")


patch_portfolio_dtos()
patch_snapshot_diff()
patch_decision_report_fingerprint()
