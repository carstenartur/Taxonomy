package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrossTaxonomyReconciliationTest {
    final ObjectMapper json=new ObjectMapper();
    String originalOverride;
    final Map<String,String> wordingOverrides=new HashMap<>();
    final List<JsonNode> reviews=new ArrayList<>();
    final List<JsonNode> wordings=new ArrayList<>();
    java.util.function.Function<JsonNode,Object> response=in->Map.of("affectedSectionIds",List.of(),"sourceResolutions",List.of(),"findings",List.of());
    ReformulationBaseline baseline() {
        var b=WalkUpReformulationTest.baseline();
        return new ReformulationBaseline(b.scope(),b.sourceVersionId(),originalOverride==null?b.originalText():originalOverride,com.taxonomy.identity.StableIdentityHash.sha256(originalOverride==null?b.originalText():originalOverride),b.snapshotId(),"{\"rawScores\":{\"BP\":1,\"AP\":90}}",
            Map.of("catalogue","[{\"code\":\"BP\",\"descriptionEn\":\"Frozen process description\"},{\"code\":\"INFO\",\"descriptionEn\":\"Shared information description\"},{\"code\":\"UNRELATED\",\"descriptionEn\":\"NEVER_INCLUDE\"}]","relationMappings","[{\"id\":1,\"sourceCode\":\"BP\",\"targetCode\":\"AP\",\"relationType\":\"SUPPLIES\",\"reviewStatus\":\"PROPOSED\"}]",
                "workspaceDsl","FORBIDDEN_WORKSPACE","snapshotDetail","UNRELATED_ARCHIVE"),"de","v1");
    }
    CrossTaxonomyReconciler reconciler() {
        var registry=mock(LlmGatewayRegistry.class);var config=mock(LlmProviderConfig.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.OPENAI);when(config.isProviderConfigured(LlmProvider.OPENAI)).thenReturn(true);
        when(registry.getGateway(LlmProvider.OPENAI)).thenReturn(new LlmGateway(){
            public String providerName(){return "fixture";} public String extractResponseText(String s){return s;}
            public String sendHttpRequest(String prompt,String key){
                assertThat(prompt).contains(baseline().originalText()).doesNotContain("FORBIDDEN_WORKSPACE","UNRELATED_ARCHIVE","NEVER_INCLUDE");
                String marker=prompt.contains("RECONCILIATION_DATA_JSON\n")?"RECONCILIATION_DATA_JSON\n":"INPUT_DATA_JSON\n";
                var in=json.readTree(prompt.substring(prompt.indexOf(marker)+marker.length()));
                if(marker.startsWith("RECONCILIATION")){reviews.add(in);return json.writeValueAsString(response.apply(in));}
                wordings.add(in);var ids=new LinkedHashSet<String>();var qs=new LinkedHashSet<String>();
                in.path("directContributions").forEach(s->ids.add(s.path("id").asText()));
                in.path("openDecisions").forEach(q->qs.add(q.path("id").asText()));
                in.path("children").forEach(c->{c.path("statementProposals").forEach(s->ids.add(s.path("id").asText()));c.path("questionProposals").forEach(q->qs.add(q.path("id").asText()));});
                return json.writeValueAsString(Map.of("summary","Reconciled summary for "+in.path("nodeId").asText(),"statementProposals",List.of(),"preservedStatementIds",ids,"questionProposals",List.of(),"preservedQuestionIds",qs,"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
            }
        });
        return new CrossTaxonomyReconciler(new NodeReformulationService(registry,config,json),json);
    }
    DecisionQuestion question(String id,String node,String scope) {
        return new DecisionQuestion(id,new DecisionQuestion.Key("time","capture",scope),"How capture?",
            List.of(new DecisionQuestion.Discovery(node,"Context "+node,"Needs capture method",List.of(),List.of(node),List.of())),List.of("s-"+node),
            new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE,List.of("Terminal","Browser"),null,null,null),List.of(),List.of(),"Defines capture method",DecisionQuestion.State.OPEN);
    }
    ReformulationDocument draft(List<DecisionQuestion> qs) {
        var ss=new ArrayList<Statement>();var sections=new ArrayList<Section>();var results=new ArrayList<NodeSynthesisResult>();
        for(String n:List.of("AP","SV","BP","OTHER")) {
            var s=new Statement("s-"+n,wordingOverrides.getOrDefault(n,n.equals("BP")?"Arbeitsbeginn und Arbeitsende werden erfasst.":"Detail "+n),List.of(),Statement.Provenance.MODEL_ADDITION,List.of(n,"INFO"),qs.stream().filter(q->q.affectedStatementIds().contains("s-"+n)).map(DecisionQuestion::id).toList(),null,Statement.EditingOrigin.MODEL,"UNREVIEWED");ss.add(s);
            var local=qs.stream().filter(q->q.affectedStatementIds().contains(s.id())).toList();
            sections.add(new Section(n,n,n,"Summary "+n,List.of(),List.of(s.id()),local.stream().map(DecisionQuestion::id).toList()));
            results.add(new NodeSynthesisResult(n,"Summary "+n,List.of(s),List.of(),local,List.of(),List.of(),List.of()));
        }
        return new ReformulationDocument("Phase A",sections,ss,qs,new ValidationReport(List.of()),results);
    }
    DecisionAnswer answer(String q,String value){return new DecisionAnswer("a-"+q,q,"offer",2,List.of(value),DecisionQuestion.State.ANSWERED,"architect",Instant.EPOCH,"Human choice");}
    @Test void mergesThreeTaxonomyDiscoveriesByExactKeyKeepingAllDerivationsAndImmutablePhaseA() {
        originalOverride="Arbeitszeiterfassung";
        var original=draft(List.of(question("q-p","BP","global"),question("q-a","AP","global"),question("q-s","SV","global")));
        String frozen=json.writeValueAsString(original);
        var out=reconciler().reconcile(baseline(),original,List.of(),List.of());
        assertThat(out.questions()).hasSize(1);assertThat(out.questions().getFirst().discoveries()).hasSize(3);
        assertThat(out.questions().getFirst().state()).isEqualTo(DecisionQuestion.State.OPEN);
        assertThat(out.questions().getFirst().answerSchema().options()).contains("Terminal","Browser");
        assertThat(out.text()).contains("Arbeitszeiterfassung","Summary BP","Arbeitsbeginn und Arbeitsende werden erfasst.","MODEL_ADDITION");
        assertThat(json.valueToTree(out.questions().getFirst()).path("aliases").toString()).contains("q-p","q-s");
        assertThat(json.writeValueAsString(original)).isEqualTo(frozen);
        assertThat(out.sections().getFirst().taxonomyCode()).isEqualTo("BP");
    }
    @Test void equalWordingWithDifferentScopeAndIncompatibleContractsStaySeparate() {
        var q=question("q-s","SV","global");
        var incompatible=new DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),q.consequences(),q.state());
        var out=reconciler().reconcile(baseline(),draft(List.of(question("q-p","BP","global"),question("q-a","AP","local"),incompatible)),List.of(),List.of());
        assertThat(out.questions()).hasSize(3);assertThat(out.validation().findings()).extracting(ValidationReport.Finding::code).contains("INCOMPATIBLE_ANSWER_CONTRACTS");
    }
    @Test void conflictingHumanAnswersRemainVisibleAndPriorAnsweredCanonicalIdWins() {
        var old=question("q-old","BP","global");var added=question("a-new","AP","global");
        var answers=List.of(answer(old.id(),"Terminal"),answer(added.id(),"Browser"));
        var out=reconciler().reconcile(baseline(),draft(List.of(added,old)),answers,List.of(old));
        assertThat(out.questions()).singleElement().satisfies(q->{assertThat(q.id()).isEqualTo("q-old");assertThat(q.state()).isEqualTo(DecisionQuestion.State.CONFLICT);});
        assertThat(out.validation().findings()).extracting(ValidationReport.Finding::code).contains("CONFLICTING_ANSWERS");
        assertThat(answers).extracting(DecisionAnswer::questionId).containsExactly("q-old","a-new");
    }
    @Test void preservesDirectedBoundariesSharedInformationGlobalConstraintsAndUnmatchedOriginal() {
        var out=reconciler().reconcile(baseline(),draft(List.of(question("q-p","BP","global"))),List.of(),List.of());
        assertThat(reviews).hasSize(1);assertThat(reviews.getFirst().path("boundaryEdges").toString()).contains("SUPPLIES","sourceCode","BP","targetCode","AP","PROPOSED");
        assertThat(reviews.getFirst().path("sharedInformation").toString()).contains("INFO");
        assertThat(reviews.getFirst().path("frozenNodeContext").toString()).contains("Frozen process description","Shared information description");
        assertThat(out.text()).contains(baseline().originalText(),"keine Browseroberfläche","2 Sekunden","SUPPLIES","BP → AP");
        assertThat(out.text().indexOf(baseline().originalText())).isGreaterThan(out.text().indexOf("Original / Unmapped source"));
        assertThat(reviews.getFirst().path("globalConstraintIds")).isNotEmpty();
        assertThat(reviews.getFirst().path("frozenNodeContext").path("rawScores").path("BP").asInt()).isEqualTo(1);
        assertThat(out.sections()).extracting(Section::id).contains("@interfaces","@cross-cutting","@unmapped");
        assertThat(out.validation().findings()).extracting(ValidationReport.Finding::kind).contains(ValidationReport.Kind.UNMAPPED_SOURCE,ValidationReport.Kind.SEMANTIC_REVIEW);
    }
    @Test void atMostTwoFrozenRoundsRewordOnlyAffectedSectionsAndAncestorsResidualConflictVisible() {
        wordingOverrides.put("BP","Offline capture must be possible.");wordingOverrides.put("AP","Every booking must be transmitted online immediately.");
        var d=draft(List.of());var secs=new ArrayList<>(d.sections());secs.add(new Section("ROOT","BP","Process","Root summary",List.of("BP"),List.of("s-BP"),List.of()));
        d=new ReformulationDocument(d.text(),secs,d.statements(),d.questions(),d.validation(),d.nodeResults());
        response=in->Map.of("affectedSectionIds",List.of("BP"),"sourceResolutions",List.of(),"findings",List.of(Map.of("kind","CONFLICT","code","INTERFACE_CONFLICT","message","Offline capture conflicts with immediate online transmission.","statementIds",List.of("s-BP"),"sourceSpans",List.of())));
        var out=reconciler().reconcile(baseline(),d,List.of(),List.of());
        assertThat(reviews).hasSize(2);assertThat(out.reconciliation()).isNotNull();
        assertThat(wordings.getFirst().path("nodeDescription").asText()).contains("Frozen process description");
        assertThat(wordings.get(1).path("children").get(0).path("summary").asText()).contains("Reconciled summary for BP");
        assertThat(wordings.get(1).path("boundaryEdges").toString()).contains("SUPPLIES");
        assertThat(out.text()).contains("Offline capture must be possible.","Every booking must be transmitted online immediately.","[CONFLICT] Offline capture conflicts with immediate online transmission.");
        assertThat(out.reconciliation().rounds()).isEqualTo(2);assertThat(out.reconciliation().phaseAResults()).isEqualTo(d.nodeResults());
        assertThat(wordings).extracting(n->n.path("nodeId").asText()).containsExactly("BP","ROOT","BP","ROOT");
        assertThat(out.sections().stream().filter(s->s.id().equals("AP")).findFirst()).contains(d.sections().getFirst());
        assertThat(out.validation().findings()).extracting(ValidationReport.Finding::code).contains("INTERFACE_CONFLICT","RECONCILIATION_LIMIT");
    }
    @Test void originalResolvedQuestionHasSourceEvidenceWithoutInventingHumanAnswer() {
        response=in->Map.of("affectedSectionIds",List.of(),"findings",List.of(),"sourceResolutions",List.of(Map.of("questionId","q-p","values",List.of("Terminal"),"rationale","Explicit original restriction","sourceSpans",List.of(new Statement.SourceSpan(0,baseline().originalText().length(),baseline().originalText())))));
        var out=reconciler().reconcile(baseline(),draft(List.of(question("q-p","BP","global"))),List.of(),List.of());
        assertThat(out.questions().getFirst().state().name()).isEqualTo("ANSWERED");
        assertThat(json.valueToTree(out.questions().getFirst()).path("sourceResolutions").toString()).contains("Explicit original restriction","Terminal");
        assertThat(reviews.getFirst().path("answers")).isEmpty();
        assertThat(json.valueToTree(out).path("answers").isMissingNode()).isTrue();
    }
    @Test void repeatedOriginsDoNotBecomeIndependentConfirmationAndSimilarAnsweredScopesRemainSeparate() {
        var q=question("q-p","BP","global");var other=question("q-a","AP","other");
        var out=reconciler().reconcile(baseline(),draft(List.of(q,other)),List.of(answer(other.id(),"Browser")),List.of(other));
        assertThat(out.questions()).hasSize(2);
        var again=reconciler().reconcile(baseline(),draft(List.of(q,q)),List.of(),List.of(q));
        assertThat(again.questions()).singleElement().satisfies(v->{assertThat(v.state()).isEqualTo(DecisionQuestion.State.OPEN);assertThat(v.discoveries()).hasSize(1);});
        assertThat(again.validation().findings()).extracting(ValidationReport.Finding::code).contains("UNCONFIRMED_ADDITION");
    }
    @Test void priorQuestionPrerequisitesAndSameStepAliasesKeepClosedReferencesAfterMerge() {
        var q=question("q-p","BP","global");var alias=question("q-a","AP","global");
        var dependency=new DecisionQuestion("dependent",new DecisionQuestion.Key("time","correction","global"),"Correction?",List.of(),List.of("s-SV"),q.answerSchema(),List.of(alias.id()),List.of(),"Dependent",DecisionQuestion.State.OPEN);
        var out=reconciler().reconcile(baseline(),draft(List.of(q,alias,dependency)),List.of(answer(q.id(),"Terminal")),List.of(q));
        var refs=out.questions().stream().flatMap(v->v.referenceIds().stream()).toList();
        assertThat(out.questions()).hasSize(2);
        out.statements().forEach(v->assertThat(refs).containsAll(v.questionDependencies()));
        out.questions().forEach(v->assertThat(refs).containsAll(v.prerequisites()));
        assertThat(out.questions().stream().filter(v->v.id().equals(q.id())).findFirst()).get().satisfies(v->assertThat(v.retains(q)).isTrue());
    }

    @Test void equivalentNumericHumanAnswersPreservePriorAnsweredIdentityAndExactRawValues() {
        var prior=numericQuestion("q-prior","BP",DecisionQuestion.State.ANSWERED,List.of());
        var discovery=numericQuestion("a-discovery","AP",DecisionQuestion.State.OPEN,List.of());
        var answers=List.of(answer(prior.id(),"2"),answer(discovery.id(),"20e-1"));
        String rawEvidence=json.writeValueAsString(answers);
        var out=reconciler().reconcile(baseline(),draft(List.of(discovery,prior)),answers,List.of(prior));
        assertThat(out.questions()).singleElement().satisfies(q->{
            assertThat(q.id()).isEqualTo(prior.id());
            assertThat(q.state()).isEqualTo(DecisionQuestion.State.ANSWERED);
            assertThat(q.retains(prior)).isTrue();
        });
        assertThat(out.validation().findings()).extracting(ValidationReport.Finding::code).doesNotContain("CONFLICTING_ANSWERS");
        assertThat(json.writeValueAsString(answers)).isEqualTo(rawEvidence);
        assertThat(out.text()).contains("Human decision: 2","Human decision: 20e-1");
    }
    @Test void equivalentNumericSourceResolutionsAcrossRoundsKeepPriorEvidenceWithoutStickyConflict() {
        var source=sourceResolution("2.00");
        var prior=numericQuestion("q-prior","BP",DecisionQuestion.State.ANSWERED,List.of(source));
        var answers=List.of(answer(prior.id(),"2"));
        String rawPrior=json.writeValueAsString(prior);String rawAnswers=json.writeValueAsString(answers);
        response=in->{
            boolean first=in.path("round").asInt()==1;
            return Map.of("affectedSectionIds",first?List.of("BP"):List.of(),
                "sourceResolutions",List.of(Map.of("questionId",prior.id(),"values",List.of(first?"2.0":"2e0"),"rationale","Same original two-second deadline","sourceSpans",source.sourceSpans())),
                "findings",first?List.of(Map.of("kind","SEMANTIC_REVIEW","code","REVIEW_DEADLINE_WORDING","message","Review deadline wording","statementIds",List.of("s-BP"),"sourceSpans",List.of())):List.of());
        };
        var out=reconciler().reconcile(baseline(),draft(List.of(prior)),answers,List.of(prior));
        assertThat(out.reconciliation().rounds()).isEqualTo(2);
        assertThat(out.questions()).singleElement().satisfies(q->{
            assertThat(q.id()).isEqualTo(prior.id());assertThat(q.state()).isEqualTo(DecisionQuestion.State.ANSWERED);
            assertThat(q.sourceResolutions()).extracting(r->r.values().getFirst()).containsExactly("2.00","2.0","2e0");
            assertThat(q.sourceResolutions()).contains(source);assertThat(q.retains(prior)).isTrue();
        });
        assertThat(out.validation().findings()).extracting(ValidationReport.Finding::code).doesNotContain("CONFLICTING_ANSWERS");
        assertThat(json.writeValueAsString(prior)).isEqualTo(rawPrior);assertThat(json.writeValueAsString(answers)).isEqualTo(rawAnswers);
    }
    @Test void genuinelyUnequalNumericHumanAndSourceValuesConflictWithoutFloatingPointRounding() {
        var evidence=sourceResolution("2.0000000000000001");
        var prior=numericQuestion("q-prior","BP",DecisionQuestion.State.ANSWERED,List.of(evidence));
        var answers=List.of(answer(prior.id(),"2"));
        var out=reconciler().reconcile(baseline(),draft(List.of(prior)),answers,List.of(prior));
        assertThat(out.questions()).singleElement().satisfies(q->{
            assertThat(q.state()).isEqualTo(DecisionQuestion.State.CONFLICT);assertThat(q.sourceResolutions()).containsExactly(evidence);
        });
        assertThat(out.validation().findings()).extracting(ValidationReport.Finding::code).contains("CONFLICTING_ANSWERS");
        assertThat(answers.getFirst().values()).containsExactly("2");
    }
    @Test void numericLookingTextAnswersRemainDistinct() {
        var number=numericQuestion("q-text","BP",DecisionQuestion.State.OPEN,List.of());
        var textQuestion=new DecisionQuestion(number.id(),number.key(),number.wording(),number.discoveries(),number.affectedStatementIds(),
            new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),number.consequences(),number.state());
        var out=reconciler().reconcile(baseline(),draft(List.of(textQuestion)),List.of(answer(textQuestion.id(),"2"),answer(textQuestion.id(),"2.0")),List.of());
        assertThat(out.questions().getFirst().state()).isEqualTo(DecisionQuestion.State.CONFLICT);
    }
    @Test void finiteNumericSyntaxAlreadyAcceptedBySourceParserRemainsComparable() {
        var q=numericQuestion("q-prior","BP",DecisionQuestion.State.ANSWERED,List.of());
        var answers=List.of(answer(q.id(),"2"));
        for(String value:List.of("2d","0x1.0p1")) {
            response=in->Map.of("affectedSectionIds",List.of(),"findings",List.of(),"sourceResolutions",List.of(Map.of(
                "questionId",q.id(),"values",List.of(value),"rationale","Original two-second deadline","sourceSpans",sourceResolution(value).sourceSpans())));
            assertThatCode(()->{
                var out=reconciler().reconcile(baseline(),draft(List.of(q)),answers,List.of(q));
                assertThat(out.questions().getFirst().state()).isEqualTo(DecisionQuestion.State.ANSWERED);
                assertThat(out.questions().getFirst().sourceResolutions().getFirst().values()).containsExactly(value);
            }).as("Accepted numeric representation %s",value).doesNotThrowAnyException();
        }
    }
    private DecisionQuestion numericQuestion(String id,String node,DecisionQuestion.State state,List<DecisionQuestion.SourceResolution> evidence) {
        var discovery=question(id,node,"global");
        return new DecisionQuestion(id,new DecisionQuestion.Key("time","deadline","global"),"What is the deadline?",discovery.discoveries(),discovery.affectedStatementIds(),
            new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.NUMBER,List.of(),"seconds",0d,null),List.of(),List.of(),"Response deadline",state,List.of(),List.of(),evidence);
    }
    private DecisionQuestion.SourceResolution sourceResolution(String value) {
        String original=baseline().originalText();
        return new DecisionQuestion.SourceResolution(List.of(value),List.of(new Statement.SourceSpan(0,original.length(),original)),"Original deadline evidence");
    }

}
