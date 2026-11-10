package com.taxonomy.composition.backup;

import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.backup.SourceRecordId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Authored identifiers isolate the reference protocol from any particular shipped catalogue. */
class PortableKnowledgeReferencesTest {
    @Test void packedFieldsRetainTheSameCatalogueAndHypothesisClosure() throws Exception {
        String document = "{\"tree\":[{\"code\":\"FIXTURE-A\",\"description\":\"" + "retained source ".repeat(10_000)
                + "\"}],\"scores\":{\"FIXTURE-B\":70},\"provisionalRelations\":[{\"hypothesisId\":17,\"targetCode\":\"FIXTURE-C\"}]}";
        var record = PortableRows.json().createObjectNode();
        record.put("analysisPayload",com.taxonomy.exchange.backup.PortableJsonPayloads.pack(document,24_000_000));
        var refs = new PortableKnowledgeReferences(); refs.encoded(record,"analysisPayload");
        assertThat(refs.codes).containsExactly("FIXTURE-A","FIXTURE-B","FIXTURE-C");
        assertThat(refs.hypotheses).containsExactly(new SourceRecordId("knowledge.hypothesis","17"));
        record.put("analysisPayload",record.path("analysisPayload").asText().replace(":v1:",":v2:"));
        assertThatThrownBy(() -> new PortableKnowledgeReferences().encoded(record,"analysisPayload"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void closesTypedCurrentDraftEvidenceAndIgnoresDiagnosticDisplayLabels()throws Exception {
        var refs=new PortableKnowledgeReferences();
        refs.payload(PortableRows.json().readTree("""
            {"scores":{"FIXTURE-A":70},"reasons":{"FIXTURE-A":"because"},"evaluatedNodes":["FIXTURE-B"],
             "architectureView":{"includedElements":[{"nodeCode":"FIXTURE-C","parentNodeCode":"FIXTURE-ROOT"}]},
             "provisionalRelations":[{"hypothesisId":17,"sourceCode":"FIXTURE-A","targetCode":"FIXTURE-D"}],
             "diagnostics":{"nodeCode":"DISPLAY LABEL"}}
            """));
        assertThat(refs.codes).containsExactly("FIXTURE-A","FIXTURE-B","FIXTURE-C","FIXTURE-D","FIXTURE-ROOT");
        assertThat(refs.hypotheses).containsExactly(new SourceRecordId("knowledge.hypothesis","17"));
    }
    @Test void currentRecommendationAndGapPayloadsCloseEvidenceOnlyCatalogueReferences() throws Exception {
        var recommendation = new com.taxonomy.dto.ArchitectureRecommendation();
        recommendation.setAcceptedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-ACCEPTED", "Display title", "BR", 0, "Reason")));
        recommendation.setMatchedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-MATCHED", "Display title", "CR", 95, "Reason")));
        recommendation.setGapAnalysis(gap("FIXTURE-NESTED"));
        var record = PortableRows.json().createObjectNode();
        record.put("recommendationPayload", com.taxonomy.exchange.backup.PortableJsonPayloads.pack(
                PortableRows.json().writeValueAsString(recommendation), 24_000_000));
        record.put("gapAnalysisPayload", com.taxonomy.exchange.backup.PortableJsonPayloads.pack(
                PortableRows.json().writeValueAsString(gap("FIXTURE-JUDGMENT")), 24_000_000));
        var refs = new PortableKnowledgeReferences();
        refs.encoded(record, "recommendationPayload");
        refs.encoded(record, "gapAnalysisPayload");
        // No scores, tree or unrelated record supplies any of these four element identities.
        assertThat(selectedCodes(refs, java.util.Set.of("FIXTURE-ACCEPTED", "FIXTURE-NESTED", "FIXTURE-JUDGMENT", "BP", "BR", "CR")))
                .containsExactlyInAnyOrder("FIXTURE-ACCEPTED", "FIXTURE-MATCHED", "FIXTURE-NESTED",
                "FIXTURE-JUDGMENT", "BP", "BR", "CR");
    }

    @Test void retainedCopilotTraversesCurrentEvidenceButNotPatternProseOrProfileNames() throws Exception {
        var recommendation = new com.taxonomy.dto.ArchitectureRecommendation();
        recommendation.setAcceptedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-COPILOT", "Title", "BR", 0, "FIXTURE-REASON-NOT-ID")));
        var patterns = new com.taxonomy.dto.PatternDetectionView();
        patterns.setIncompletePatterns(java.util.List.of(new com.taxonomy.dto.DetectedPattern("FIXTURE-PATTERN-NOT-ID",
                java.util.List.of("FIXTURE-STEP-NOT-ID"), java.util.List.of(), java.util.List.of("FIXTURE-MISSING-PROSE"), 0)));
        var copilot = new com.taxonomy.dto.AnalysisCopilotResult(2, "COMPLETED", gap("FIXTURE-COPILOT-GAP"),
                patterns, recommendation, java.util.List.of("FIXTURE-NOTE-NOT-ID"));
        var record = PortableRows.json().createObjectNode();
        record.put("analysisPayload", com.taxonomy.exchange.backup.PortableJsonPayloads.pack(
                PortableRows.json().writeValueAsString(java.util.Map.of("copilot", copilot)), 24_000_000));
        var refs = new PortableKnowledgeReferences(); refs.encoded(record, "analysisPayload");
        assertThat(selectedCodes(refs, java.util.Set.of("FIXTURE-COPILOT", "FIXTURE-COPILOT-GAP", "BP", "BR", "CR")))
                .containsExactlyInAnyOrder("FIXTURE-COPILOT", "FIXTURE-COPILOT-GAP", "BP", "BR", "CR");
    }

    @Test void obsoleteGapAndRecommendationAliasesAreNotCurrentReferenceContracts() throws Exception {
        var refs = new PortableKnowledgeReferences();
        refs.payload(PortableRows.json().readTree("""
                {"confirmedElements":[{"nodeCode":"OLD-CONFIRMED"}],
                 "missingRelations":[{"sourceNodeCode":"OLD-MISSING"}],
                 "coverageGaps":[{"nodeCode":"OLD-COVERAGE"}],"expectedTargetRoot":"OLD-TARGET"}
                """));
        assertThat(refs.codes).isEmpty();
    }

    @Test void malformedJudgmentTargetRootReferencesFailClosed() {
        for (String targets : java.util.List.of("17", "[17]", "[\" invalid \"]"))
            assertThatThrownBy(() -> new PortableKnowledgeReferences().payload(PortableRows.json().readTree(
                    "{\"judgments\":[{\"targetRoots\":" + targets + "}]}")))
                    .isInstanceOf(java.io.IOException.class);
    }

    private static com.taxonomy.dto.GapAnalysisView gap(String code) {
        var gap = new com.taxonomy.dto.GapAnalysisView();
        gap.setProfileId("FIXTURE-PROFILE-NOT-ID");
        gap.setJudgments(java.util.List.of(new com.taxonomy.dto.ObligationJudgment("FIXTURE-RULE-NOT-ID", code, "CR",
                "SUPPORTS", java.util.Set.of("BP", "BR"), 1, null, 0, com.taxonomy.dto.ObligationProfile.Severity.WARNING,
                com.taxonomy.dto.ObligationJudgment.Status.INCONCLUSIVE, "FIXTURE-DESCRIPTION-NOT-ID")));
        return gap;
    }

    @Test void customCanonicalEvidenceDoesNotBecomeMandatoryCatalogueDependencies() throws Exception {
        var recommendation = new com.taxonomy.dto.ArchitectureRecommendation();
        recommendation.setAcceptedElements(java.util.List.of(
                new com.taxonomy.dto.RecommendedElement("service", "Service", "CR", 0, "Canonical member"),
                new com.taxonomy.dto.RecommendedElement("role", "Role", "BR", 0, "Canonical member"),
                new com.taxonomy.dto.RecommendedElement("FIXTURE-ACCEPTED", "Catalogue member", "CR", 0, "Canonical member")));
        recommendation.setMatchedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-MATCHED", "Catalogue match", "CR", 85, "Match")));
        recommendation.setProposedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-PROPOSED", "Catalogue proposal", "BR", 0, "Proposal")));
        recommendation.setSuggestedRelations(java.util.List.of(
                new com.taxonomy.dto.SuggestedRelation("service", "role", "SUPPORTS", "Existing members"),
                new com.taxonomy.dto.SuggestedRelation("service", "FIXTURE-PROPOSED", "SUPPORTS", "New catalogue candidate")));
        recommendation.setGapAnalysis(gap("service"));
        var record = PortableRows.json().createObjectNode();
        record.put("recommendationPayload", PortableRows.json().writeValueAsString(recommendation));
        var refs = new PortableKnowledgeReferences(); refs.encoded(record, "recommendationPayload");
        assertThat(refs.codes).contains("FIXTURE-MATCHED", "FIXTURE-PROPOSED")
                .doesNotContain("service", "role");
        assertThat(selectedCodes(refs, java.util.Set.of("FIXTURE-ACCEPTED", "FIXTURE-MATCHED", "FIXTURE-PROPOSED", "BP", "BR", "CR")))
                .containsExactlyInAnyOrder("FIXTURE-ACCEPTED", "FIXTURE-MATCHED", "FIXTURE-PROPOSED", "BP", "BR", "CR");
        // Missing mandatory catalogue dependencies remain selected for strict contributor validation.
        assertThat(selectedCodes(refs, java.util.Set.of()))
                .containsExactlyInAnyOrder("FIXTURE-MATCHED", "FIXTURE-PROPOSED", "BR", "CR");
    }

    @Test void modelTypeRootsRequireExactCatalogueMembership() throws Exception {
        String systemRoot = com.taxonomy.dsl.model.TaxonomyRootTypes.rootFor("System");
        String componentRoot = com.taxonomy.dsl.model.TaxonomyRootTypes.rootFor("Component");
        var recommendation = new com.taxonomy.dto.ArchitectureRecommendation();
        recommendation.setAcceptedElements(java.util.List.of(
                new com.taxonomy.dto.RecommendedElement("system", "System", systemRoot, 0, "Canonical member"),
                new com.taxonomy.dto.RecommendedElement("component", "Component", componentRoot, 0, "Canonical member")));
        recommendation.setMatchedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-MATCHED", "Catalogue match", "CR", 90, "Match")));
        recommendation.setProposedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "FIXTURE-PROPOSED", "Catalogue proposal", "BP", 0, "Proposal")));
        var gap = new com.taxonomy.dto.GapAnalysisView();
        gap.setJudgments(java.util.List.of(new com.taxonomy.dto.ObligationJudgment("fixture-model-rule", "system", systemRoot,
                "RELATED_TO", java.util.Set.of(componentRoot, "BR"), 1, null, 0,
                com.taxonomy.dto.ObligationProfile.Severity.WARNING,
                com.taxonomy.dto.ObligationJudgment.Status.INCONCLUSIVE, "Authored model obligation")));
        recommendation.setGapAnalysis(gap);
        var record = PortableRows.json().createObjectNode();
        record.put("recommendationPayload", PortableRows.json().writeValueAsString(recommendation));
        var refs = new PortableKnowledgeReferences(); refs.encoded(record, "recommendationPayload");
        assertThat(selectedCodes(refs, java.util.Set.of("FIXTURE-MATCHED", "FIXTURE-PROPOSED", "CR", "BP", "BR")))
                .containsExactlyInAnyOrder("FIXTURE-MATCHED", "FIXTURE-PROPOSED", "CR", "BP", "BR");
        // Membership evidence, rather than a hardcoded exclusion of model root names, decides inclusion.
        assertThat(selectedCodes(refs, java.util.Set.of(systemRoot, componentRoot, "BR")))
                .containsExactlyInAnyOrder("FIXTURE-MATCHED", "FIXTURE-PROPOSED", "CR", "BP", "BR", systemRoot, componentRoot);
        // Catalogue-derived roots remain mandatory even if their rows are absent.
        assertThat(selectedCodes(refs, java.util.Set.of()))
                .containsExactlyInAnyOrder("FIXTURE-MATCHED", "FIXTURE-PROPOSED", "CR", "BP");
    }

    @Test void catalogueMembershipLookupFailuresPropagate() throws Exception {
        var recommendation = new com.taxonomy.dto.ArchitectureRecommendation();
        recommendation.setAcceptedElements(java.util.List.of(new com.taxonomy.dto.RecommendedElement(
                "system", "System", com.taxonomy.dsl.model.TaxonomyRootTypes.rootFor("System"), 0, "Canonical member")));
        var refs = new PortableKnowledgeReferences(); refs.payload(PortableRows.json().valueToTree(recommendation));
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:hsqldb:mem:missing-catalogue-" + java.util.UUID.randomUUID(), "sa", ""));
        try {
            assertThatThrownBy(() -> ProductionCurrentBackupSource.resolveKnowledgeReferences(refs, jdbc, () -> {}))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { jdbc.execute("shutdown"); }
    }

    private static java.util.Set<String> selectedCodes(PortableKnowledgeReferences refs, java.util.Set<String> catalogue) throws Exception {
        var database = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:hsqldb:mem:reference-closure-" + java.util.UUID.randomUUID(), "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database);
        try {
            jdbc.execute("create table taxonomy_node (code varchar(256) primary key)");
            for (String code : catalogue) jdbc.update("insert into taxonomy_node(code) values (?)", code);
            return ProductionCurrentBackupSource.resolveKnowledgeReferences(refs, jdbc, () -> {}).catalogueCodes();
        } finally {
            jdbc.execute("shutdown");
        }
    }

    @Test void malformedCodeAndHypothesisContractsFailClosed() {
        var refs=new PortableKnowledgeReferences();
        assertThatThrownBy(()->refs.payload(PortableRows.json().readTree("{\"scores\":[]}"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->refs.payload(PortableRows.json().readTree("{\"evaluatedNodes\":[17]}"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->refs.payload(PortableRows.json().readTree("{\"provisionalRelations\":[{\"hypothesisId\":0}]}"))).isInstanceOf(java.io.IOException.class);
    }
    @Test void hypothesesShareOneBoundAcrossAllVisitedDocumentsAndPayloads()throws Exception {
        var refs=new PortableKnowledgeReferences();
        var payload=PortableRows.json().createObjectNode();var hypothesis=payload.putArray("provisionalRelations").addObject();
        for(long id=1;id<=100_000;id++) {
            if(id%2==0){hypothesis.put("hypothesisId",id);refs.payload(payload);}
            else refs.hypothesis(Long.toString(id));
        }
        hypothesis.put("hypothesisId",1);refs.payload(payload);
        refs.hypothesis("2");
        assertThat(refs.hypotheses).hasSize(100_000);
        assertThatThrownBy(()->refs.hypothesis("100001")).isInstanceOf(java.io.IOException.class)
                .hasMessage("Hypothesis reference limit exceeded");
        hypothesis.put("hypothesisId",100_001);
        assertThatThrownBy(()->refs.payload(payload)).isInstanceOf(java.io.IOException.class)
                .hasMessage("Hypothesis reference limit exceeded");
        assertThat(refs.hypotheses).hasSize(100_000);
    }
    @Test void documentHypothesesUseExactPositiveLongIdentities()throws Exception {
        var refs=new PortableKnowledgeReferences();
        refs.hypothesis(Long.toString(Long.MAX_VALUE));
        assertThat(refs.hypotheses).containsExactly(new SourceRecordId("knowledge.hypothesis","9223372036854775807"));
        for(String invalid:java.util.List.of("","0","01","-1","+1"," 1","1 ","1e3","9223372036854775808","100000000000000000000"))
            assertThatThrownBy(()->refs.hypothesis(invalid)).as("invalid identity %s",invalid)
                    .isInstanceOf(java.io.IOException.class).hasMessage("Invalid hypothesis reference");
    }
}
