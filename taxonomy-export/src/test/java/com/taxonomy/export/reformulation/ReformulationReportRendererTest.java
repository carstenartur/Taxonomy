package com.taxonomy.export.reformulation;

import org.junit.jupiter.api.Test;

class ReformulationReportRendererTest {
    @Test void frozenDiscoveryContextAndMergedOriginAreReadableBeforeTheRawArchive() {
        var key = new com.taxonomy.reformulation.DecisionQuestion.Key("storage", "duration", "BP");
        var schema = new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(
                com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.Kind.TEXT, java.util.List.of(), null, null, null);
        String context = """
                {"current":{"id":"BP-1000","parentIds":["BP"],"score":0.125,
                "description":"{\\"nameEn\\":\\"Business processes\\",\\"nameDe\\":\\"Geschäftsprozesse\\",\\"description\\":\\"Recorded scope\\"}"},
                "terminalContributions":[],"directParentContributions":[]}
                """;
        var discovery = new com.taxonomy.reformulation.DecisionQuestion.Discovery("saved location", context, "saved reason",
                java.util.List.of(), java.util.List.of("BP-1000"), java.util.List.of());
        var origin = new com.taxonomy.reformulation.DecisionQuestion.Origin("old-question", key, "Original question", java.util.List.of(),
                java.util.List.of("s-1"), schema, java.util.List.of("prerequisite-1"), java.util.List.of("follow-up-1"),
                "Captured origin consequence", com.taxonomy.reformulation.DecisionQuestion.State.CONFLICT);
        var question = new com.taxonomy.reformulation.DecisionQuestion("question", key, "Current question", java.util.List.of(discovery),
                java.util.List.of(), schema, java.util.List.of(), java.util.List.of(), "", com.taxonomy.reformulation.DecisionQuestion.State.OPEN,
                java.util.List.of(), java.util.List.of(origin), java.util.List.of());
        for (var language : java.util.List.of("de", "en")) {
            var input = new ReformulationReportRenderer.Input(language, false, java.util.Map.of(), "Original", "Reviewed",
                    java.util.List.of(), java.util.List.of(), java.util.List.of(question), java.util.List.of(),
                    new com.taxonomy.reformulation.ValidationReport(java.util.List.of()), "{\"rawEvidenceMarker\":true}");
            String heading = language.equals("de") ? "Vollständige strukturierte Evidenz" : "Complete structured evidence";
            for (var document : java.util.List.of(ReformulationReportRenderer.html(input), ReformulationReportRenderer.markdown(input))) {
                String narrative = document.substring(0, document.indexOf(heading));
                org.assertj.core.api.Assertions.assertThat(narrative).contains("BP-1000", "Recorded scope", "0.125%", "CONFLICT",
                        "Captured origin consequence", "prerequisite-1", "follow-up-1",
                        language.equals("de") ? "Geschäftsprozesse" : "Business processes")
                        .doesNotContain("directParentContributions", "nameEn");
                org.assertj.core.api.Assertions.assertThat(document.substring(document.indexOf(heading)))
                        .contains("rawEvidenceMarker");
            }
        }
    }

    @Test void externalMarkupRemainsLiteral() { ReformulationReportRendererChecks.literalMarkup(); }
    @Test void decisionHistoryAndMergedOriginsRemainVisible() { ReformulationReportRendererChecks.historyAndOrigins(); }
    @Test void inputSnapshotsAreImmutableAndDeterministic() { ReformulationReportRendererChecks.immutableInputs(); }
    @Test void headingsKeepTildesLiteral() { ReformulationReportRendererChecks.literalTildesInHeadings(); }
}
