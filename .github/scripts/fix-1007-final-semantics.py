#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# Incremental SSE events may identify score kind and parent, but they must not
# serialize a batch-local effective product value before the family batch exists.
mapper_path = Path(
    "taxonomy-app/src/main/java/com/taxonomy/analysis/controller/"
    "AnalysisSseEventMapper.java"
)
mapper = mapper_path.read_text(encoding="utf-8")
mapper = replace_once(
    mapper,
    "import com.taxonomy.dto.AnalysisScoreSemantics;\n",
    "import com.taxonomy.dto.AnalysisScoreDetail;\n"
    "import com.taxonomy.dto.AnalysisScoreKind;\n"
    "import com.taxonomy.dto.AnalysisScoreSemantics;\n",
    "mapper score imports",
)
mapper = mapper.replace(
    '            payload.put("scoreSemanticsVersion", '
    'AnalysisScoreSemantics.CURRENT_VERSION);\n',
    '            payload.put("scoreSemanticsVersion", '
    'AnalysisScoreSemantics.CURRENT_VERSION);\n'
    '            payload.put("scoreSemanticsComplete", true);\n',
    2,
)
if mapper.count('payload.put("scoreSemanticsComplete", true);') != 2:
    raise SystemExit("terminal completeness markers were not added exactly twice")
mapper = replace_once(
    mapper,
    '        payload.put("scoreDetails", semantics.scoreDetails());\n'
    '        payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);\n'
    '        payload.put("reasons", scores.reasons() != null ? scores.reasons() : Map.of());',
    '        payload.put("scoreDetails", incrementalScoreDetails(semantics.scoreDetails()));\n'
    '        payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);\n'
    '        payload.put("scoreSemanticsComplete", false);\n'
    '        payload.put("reasons", scores.reasons() != null ? scores.reasons() : Map.of());',
    "incremental structural score details",
)
mapper = replace_once(
    mapper,
    "    private AnalysisScoreSemantics.Derived derive(Map<String, Integer> rawScores) {\n"
    "        return AnalysisScoreSemantics.derive(rawScores, taxonomyTree());\n"
    "    }\n\n",
    "    private AnalysisScoreSemantics.Derived derive(Map<String, Integer> rawScores) {\n"
    "        return AnalysisScoreSemantics.derive(rawScores, taxonomyTree());\n"
    "    }\n\n"
    "    private Map<String, IncrementalScoreDetail> incrementalScoreDetails(\n"
    "            Map<String, AnalysisScoreDetail> scoreDetails) {\n"
    "        Map<String, IncrementalScoreDetail> result = new LinkedHashMap<>();\n"
    "        if (scoreDetails != null) {\n"
    "            scoreDetails.forEach((code, detail) -> {\n"
    "                if (code != null && detail != null) {\n"
    "                    result.put(code, IncrementalScoreDetail.from(detail));\n"
    "                }\n"
    "            });\n"
    "        }\n"
    "        return Map.copyOf(result);\n"
    "    }\n\n",
    "incremental score detail projection",
)
mapper = replace_once(
    mapper,
    "    private record CachedTaxonomyTree(\n",
    "    /** Structural metadata safe to publish before the complete score context exists. */\n"
    "    public record IncrementalScoreDetail(\n"
    "            String nodeCode,\n"
    "            AnalysisScoreKind kind,\n"
    "            int rawScore,\n"
    "            String parentCode) {\n\n"
    "        private static IncrementalScoreDetail from(AnalysisScoreDetail detail) {\n"
    "            return new IncrementalScoreDetail(\n"
    "                    detail.nodeCode(), detail.kind(), detail.rawScore(),\n"
    "                    detail.parentCode());\n"
    "        }\n"
    "    }\n\n"
    "    private record CachedTaxonomyTree(\n",
    "incremental score detail record",
)
mapper_path.write_text(mapper, encoding="utf-8")


mapper_test_path = Path(
    "taxonomy-app/src/test/java/com/taxonomy/analysis/controller/"
    "AnalysisSseEventMapperTest.java"
)
mapper_test = mapper_test_path.read_text(encoding="utf-8")
mapper_test = replace_once(
    mapper_test,
    '                .containsEntry("error", "minor")\n'
    '                .doesNotContainKey("effectiveScores");\n'
    '        assertThat(((Map<?, ?>) payload.get("scoreDetails")).get("CP")).isNotNull();',
    '                .containsEntry("error", "minor")\n'
    '                .containsEntry("scoreSemanticsComplete", false)\n'
    '                .doesNotContainKey("effectiveScores");\n'
    '        AnalysisSseEventMapper.IncrementalScoreDetail scoreDetail =\n'
    '                (AnalysisSseEventMapper.IncrementalScoreDetail)\n'
    '                        ((Map<?, ?>) payload.get("scoreDetails")).get("CP");\n'
    '        assertThat(scoreDetail.rawScore()).isEqualTo(80);\n'
    '        assertThat(scoreDetail.kind()).isEqualTo(\n'
    '                AnalysisScoreKind.HIERARCHICAL_RELEVANCE);',
    "incremental mapper assertion",
)
mapper_test = replace_once(
    mapper_test,
    '                .containsEntry("effectiveScores", Map.of("CP", 80, "CR", 0))\n'
    '                .containsEntry("totalMatched", 1)',
    '                .containsEntry("effectiveScores", Map.of("CP", 80, "CR", 0))\n'
    '                .containsEntry("scoreSemanticsComplete", true)\n'
    '                .containsEntry("totalMatched", 1)',
    "complete event completeness assertion",
)
mapper_test = replace_once(
    mapper_test,
    '                .containsEntry("effectiveScores", Map.of("CP", 80))\n'
    '                .containsEntry("warnings", List.of("warn"))',
    '                .containsEntry("effectiveScores", Map.of("CP", 80))\n'
    '                .containsEntry("scoreSemanticsComplete", true)\n'
    '                .containsEntry("warnings", List.of("warn"))',
    "error event completeness assertion",
)
product_incremental_test = '''    @Test
    void incrementalProductDetailOmitsBatchLocalEffectiveRelevance() {
        TaxonomyService taxonomyService = mock(TaxonomyService.class);
        when(taxonomyService.getFullTree()).thenReturn(List.of(productTree()));
        AnalysisSseEventMapper semanticMapper = new AnalysisSseEventMapper(taxonomyService);

        AnalysisSseEventMapper.MappedEvent mapped = semanticMapper.map(
                new AnalysisStreamEvent.Scores(
                        Map.of("IP-P", 80), Map.of(), "product batch", null));

        Map<String, Object> payload = payload(mapped);
        AnalysisSseEventMapper.IncrementalScoreDetail detail =
                (AnalysisSseEventMapper.IncrementalScoreDetail)
                        ((Map<?, ?>) payload.get("scoreDetails")).get("IP-P");
        assertThat(detail.kind()).isEqualTo(AnalysisScoreKind.PRODUCT_SUITABILITY);
        assertThat(detail.rawScore()).isEqualTo(80);
        assertThat(detail.parentCode()).isEqualTo("IP-F");
        assertThat(payload)
                .containsEntry("scoreSemanticsComplete", false)
                .doesNotContainKeys(
                        "effectiveScores", "productSuitabilityScores",
                        "scoreSemanticsWarnings");
    }

'''
mapper_test = replace_once(
    mapper_test,
    "    @Test\n    void mapTerminalEventsPreserveExistingNamesAndAddEffectiveScores() {\n",
    product_incremental_test
    + "    @Test\n    void mapTerminalEventsPreserveExistingNamesAndAddEffectiveScores() {\n",
    "product incremental regression",
)
mapper_test_path.write_text(mapper_test, encoding="utf-8")


# Conflicting duplicate taxonomy codes must not make role/parent interpretation
# depend on traversal order.
semantics_path = Path(
    "taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java"
)
semantics = semantics_path.read_text(encoding="utf-8")
old_visit = '''        String code = node.getCode().strip();
        if (!activeCodes.add(code)) {
            return;
        }
        String parentCode = firstNonBlank(node.getParentCode(), inheritedParentCode);
        String role = node.getAnalysisRole() == null
                ? "CATEGORY" : node.getAnalysisRole().strip().toUpperCase(Locale.ROOT);
        result.putIfAbsent(code, new NodeContext(parentCode, role));'''
new_visit = '''        String code = node.getCode().strip();
        String parentCode = firstNonBlank(node.getParentCode(), inheritedParentCode);
        String role = node.getAnalysisRole() == null
                ? "CATEGORY" : node.getAnalysisRole().strip().toUpperCase(Locale.ROOT);
        NodeContext context = new NodeContext(parentCode, role);
        NodeContext previous = result.putIfAbsent(code, context);
        if (previous != null && !previous.equals(context)) {
            throw new IllegalArgumentException(
                    "Taxonomy tree contains conflicting score-semantics definitions for node "
                            + code + ": " + previous + " versus " + context);
        }
        if (!activeCodes.add(code)) {
            return;
        }'''
semantics = replace_once(
    semantics, old_visit, new_visit, "conflicting duplicate taxonomy codes"
)
semantics_path.write_text(semantics, encoding="utf-8")


semantics_test_path = Path(
    "taxonomy-domain/src/test/java/com/taxonomy/dto/AnalysisScoreSemanticsTest.java"
)
semantics_test = semantics_test_path.read_text(encoding="utf-8")
semantics_test = replace_once(
    semantics_test,
    "import static org.junit.jupiter.api.Assertions.assertTrue;\n",
    "import static org.junit.jupiter.api.Assertions.assertThrows;\n"
    "import static org.junit.jupiter.api.Assertions.assertTrue;\n",
    "assertThrows import",
)
duplicate_test = '''    @Test
    void conflictingDuplicateNodeDefinitionsFailIndependentlyOfTraversalOrder() {
        TaxonomyNodeDto first = node("IP-P", "IP-F1", "PRODUCT");
        TaxonomyNodeDto second = node("IP-P", "IP-F2", "CATEGORY");

        for (List<TaxonomyNodeDto> order : List.of(
                List.of(first, second), List.of(second, first))) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> AnalysisScoreSemantics.derive(Map.of("IP-P", 80), order));
            assertTrue(failure.getMessage().contains(
                    "conflicting score-semantics definitions for node IP-P"));
        }
    }

'''
semantics_test = replace_once(
    semantics_test,
    "    @Test\n    void explicitRawScoresWinRegardlessOfLegacyJsonPropertyOrder() {\n",
    duplicate_test
    + "    @Test\n    void explicitRawScoresWinRegardlessOfLegacyJsonPropertyOrder() {\n",
    "duplicate semantics regression",
)
semantics_test_path.write_text(semantics_test, encoding="utf-8")


# An empty JavaScript record is the canonical no-score state after common startup
# initialization. The browser acceptance must not require the narrower null value.
ui_path = Path(".github/scripts/ui-primary-session-workflow.mjs")
ui = ui_path.read_text(encoding="utf-8")
old_state = "      && !state?.currentScores\n"
new_state = "      && Object.keys(state?.currentScores || {}).length === 0\n"
if ui.count(old_state) != 2:
    raise SystemExit(
        f"UI currentScores empty-state checks: expected two, found {ui.count(old_state)}"
    )
ui = ui.replace(old_state, new_state)
old_legacy = "      && !window._taxonomyCurrentScores\n"
new_legacy = "      && Object.keys(window._taxonomyCurrentScores || {}).length === 0\n"
if ui.count(old_legacy) != 2:
    raise SystemExit(
        f"UI legacy score empty-state checks: expected two, found {ui.count(old_legacy)}"
    )
ui = ui.replace(old_legacy, new_legacy)
ui_path.write_text(ui, encoding="utf-8")
