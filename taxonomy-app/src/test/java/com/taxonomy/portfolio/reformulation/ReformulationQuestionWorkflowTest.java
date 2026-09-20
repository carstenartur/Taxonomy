package com.taxonomy.portfolio.reformulation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @WithMockUser(username="architect",roles="ARCHITECT")
class ReformulationQuestionWorkflowTest extends ReformulationWorkflowFixture {
    tools.jackson.databind.JsonNode answer(String id,long revision,String q,String action,List<String> values,String other,int status) throws Exception {
        return json.readTree(mvc.perform(post(base()+"/"+id+"/answers").with(csrf()).header("If-Match","\""+revision+"\"")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("questionId",q,"action",action,"values",values,"otherText",other,"rationale","Human decision","author","forged"))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    @Test void answersAllKindsAppendEvidenceAndKeepRequirementActive() throws Exception {
        var p=seed(); var before=projects.getRequirement(project.id(),requirement.id(),"architect",context);long n=2;
        for(var item:Map.of("channel",List.of("Terminal"),"multiple",List.of("Browser","Terminal"),"text",List.of("Text <b>data</b>"),"number",List.of("2.0"),"boolean",List.of("true"),"correction",List.of("Not needed")).entrySet()) {
            var result=answer(p.id(),n++,item.getKey(),"ANSWER",item.getValue(),"",201);
            assertThat(result.at("/currentRevision/answers").size()).isEqualTo((int)n-2);
            assertThat(result.at("/currentRevision/answers/0/author").asText()).isEqualTo("architect");
        }
        assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context)).isEqualTo(before);
        assertThat(projects.listRequirementVersions(project.id(),requirement.id(),"architect",context)).hasSize(1);
        assertThat(reformulations.revision(project.id(),requirement.id(),p.id(),2,"architect",context).answers()).isEmpty();
        answer(p.id(),2,"text","ANSWER",List.of("stale"),"",412);
    }
    @Test void otherDeferralAndNotApplicableAreDistinctAndValidated() throws Exception {
        var p=seed();
        answer(p.id(),2,"channel","ANSWER",List.of("Other"),"",422);
        var other=answer(p.id(),2,"channel","ANSWER",List.of("Other"),"Paper then entry",201);
        assertThat(other.at("/currentRevision/answers/0/otherText").asText()).isEqualTo("Paper then entry");
        var defer=answer(p.id(),3,"channel","ANSWER",List.of("Still open"),"",201);
        assertThat(defer.at("/currentRevision/questions/0/state").asText()).isEqualTo("DEFERRED");
        var na=answer(p.id(),4,"correction","NOT_APPLICABLE",List.of(),"",201);
        assertThat(na.at("/currentRevision/questions/5/state").asText()).isEqualTo("NOT_APPLICABLE");
        answer(p.id(),5,"multiple","ANSWER",List.of("Bogus"),"",422);
        answer(p.id(),5,"number","ANSWER",List.of("NaN"),"",422);
        answer(p.id(),5,"number","ANSWER",List.of("61"),"",422);
        answer(p.id(),5,"boolean","ANSWER",List.of("yes"),"",422);
    }
    @Test void localImpactIncludesAncestorButIndependentBranchStaysStableAndGlobalBroadens() throws Exception {
        var p=seed();var result=answer(p.id(),2,"channel","ANSWER",List.of("Terminal"),"",201);
        assertThat(result.at("/currentRevision/impact/sectionIds").toString()).contains("BP-1","\"BP\"").doesNotContain("BP-2");
        assertThat(result.at("/currentRevision/sections/2")).isEqualTo(json.valueToTree(p.currentRevision().sections().get(2)));
        result=answer(p.id(),3,"global","ANSWER",List.of("All capture branches"),"",201);
        assertThat(result.at("/currentRevision/impact/sectionIds").toString()).contains("BP-2");
    }
    @Test void manualEditAndRejectionPersistAcrossVariantAndLateCandidate() throws Exception {
        var p=seed();var run=reformulations.beginRun(project.id(),requirement.id(),p.id(),2,"TEST","test","v1","v1","frozen","architect",context);
        var saved=json.readTree(mvc.perform(post(base()+"/"+p.id()+"/statements/capture").with(csrf()).header("If-Match","\"2\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"EDIT\",\"text\":\"Human protected paragraph\",\"rationale\":\"Correction\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(saved.at("/currentRevision/text").asText()).contains("Human protected paragraph");
        answer(p.id(),3,"channel","ANSWER",List.of("Terminal"),"",201);
        mvc.perform(post(base()+"/"+p.id()+"/statements/independent").with(csrf()).header("If-Match","\"4\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REJECT\",\"rationale\":\"Not requested\"}"))
            .andExpect(status().isCreated());
        var current=reformulations.get(project.id(),requirement.id(),p.id(),"architect",context).currentRevision();
        reformulations.finishRun(project.id(),requirement.id(),p.id(),run.id(),new com.taxonomy.reformulation.ReformulationDocument("Late model wording",p.currentRevision().sections(),p.currentRevision().statements(),p.currentRevision().questions(),p.currentRevision().validation(),List.of()),null,"architect",context);
        assertThat(reformulations.get(project.id(),requirement.id(),p.id(),"architect",context).currentRevision()).isEqualTo(current);
        assertThat(reformulations.runs(project.id(),requirement.id(),p.id(),"architect",context).getLast().candidate().text()).isEqualTo("Late model wording");
        var variant=json.readTree(mvc.perform(post(base()+"/"+p.id()+"/variants").with(csrf()).header("If-Match","\"5\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Terminal variant\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(variant.path("id").asText()).isNotEqualTo(p.id());
        assertThat(variant.at("/currentRevision/text").asText()).contains("Human protected paragraph").doesNotContain("Unabhängige Abrechnung.");
        assertThat(variant.at("/currentRevision/statements").toString()).contains("REJECTED");
        assertThat(json.treeToValue(variant.at("/baseline"),com.taxonomy.reformulation.ReformulationBaseline.class)).isEqualTo(p.baseline());
    }
    @Test void aliasAnswerConflictingWithSourcePreservesBothKindsOfEvidence() throws Exception {
        questionTransform=qs->{var all=new ArrayList<>(qs);var q=all.getFirst();
            var alias=new com.taxonomy.reformulation.DecisionQuestion("old-channel",q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),q.state());
            all.set(0,new com.taxonomy.reformulation.DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),com.taxonomy.reformulation.DecisionQuestion.State.ANSWERED,List.of("old-channel"),List.of(alias.origin()),List.of(new com.taxonomy.reformulation.DecisionQuestion.SourceResolution(List.of("Terminal"),List.of(new com.taxonomy.reformulation.Statement.SourceSpan(0,ORIGINAL.length(),ORIGINAL)),"Frozen source constraint"))));return all;};
        var p=seed();assertThat(p.currentRevision().answers()).isEmpty();
        var result=answer(p.id(),2,"old-channel","ANSWER",List.of("Browser"),"",201);
        assertThat(result.at("/currentRevision/questions/0/state").asText()).isEqualTo("CONFLICT");
        assertThat(result.at("/currentRevision/questions/0/sourceResolutions/0/values/0").asText()).isEqualTo("Terminal");
        assertThat(result.at("/currentRevision/answers/0/questionId").asText()).isEqualTo("old-channel");
        assertThat(result.at("/currentRevision/validation/findings").toString()).contains("SOURCE_ANSWER_CONFLICT");
        assertThat(result.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
    }
    @Test void conditionalOfflineQuestionRetainsHistoryWhenSelectedVariantChanges() throws Exception {
        questionTransform=qs->{var all=new ArrayList<>(qs);var q=question("offline","BOOLEAN",List.of(),"BP-1");
            var schema=new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(q.answerSchema().kind(),List.of(),null,null,null,Map.of(),List.of(),
                List.of(new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.AnswerCondition("channel",List.of("Terminal"))));
            all.add(new com.taxonomy.reformulation.DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),schema,List.of("channel"),List.of(),q.consequences(),q.state()));return all;};
        var p=seed();answer(p.id(),2,"offline","ANSWER",List.of("true"),"",422);
        answer(p.id(),2,"channel","ANSWER",List.of("Terminal"),"",201);
        var selected=answer(p.id(),3,"offline","ANSWER",List.of("true"),"",201);
        assertThat(selected.at("/currentRevision/questions/7/state").asText()).isEqualTo("ANSWERED");
        var changed=answer(p.id(),4,"channel","ANSWER",List.of("Browser"),"",201);
        assertThat(changed.at("/currentRevision/questions/7/state").asText()).isEqualTo("CONFLICT");
        assertThat(changed.at("/currentRevision/answers").size()).isEqualTo(3);
        assertThat(changed.at("/currentRevision/validation/findings").toString()).contains("STALE_FOLLOW_UP_CONTEXT");
        assertThat(changed.at("/currentRevision/questions/7/discoveries").size()).isEqualTo(1);
    }
    @Test void typedOtherOpenAndIncompatibleMultiselectDoNotDependOnTranslatedLabels() throws Exception {
        questionTransform=qs->{var all=new ArrayList<>(qs);var q=all.get(1);
            var schema=new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(q.answerSchema().kind(),List.of("A","B","Custom","Later"),null,null,null,
                Map.of("Custom",com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.OptionMeaning.OTHER,"Later",com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.OptionMeaning.OPEN),List.of(List.of("A","B")),List.of());
            all.set(1,new com.taxonomy.reformulation.DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),schema,List.of(),List.of(),q.consequences(),q.state()));return all;};
        var p=seed();answer(p.id(),2,"multiple","ANSWER",List.of("A","B"),"",422);
        answer(p.id(),2,"multiple","ANSWER",List.of("Custom"),"",422);
        answer(p.id(),2,"multiple","ANSWER",List.of("Custom"),"Explicit paper capture",201);
        var open=answer(p.id(),3,"multiple","ANSWER",List.of("Later"),"",201);
        assertThat(open.at("/currentRevision/questions/1/state").asText()).isEqualTo("DEFERRED");
    }
    @Test void documentedRevisionRouteAppendsAnswerAndParagraphEdit() throws Exception {
        var p=seed();
        mvc.perform(post(base()+"/"+p.id()+"/revisions").with(csrf()).header("If-Match","\"2\"").contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":{\"questionId\":\"channel\",\"action\":\"ANSWER\",\"values\":[\"Terminal\"],\"rationale\":\"Selected design\"}}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.currentRevision.answers[0].values[0]").value("Terminal"));
        mvc.perform(post(base()+"/"+p.id()+"/revisions").with(csrf()).header("If-Match","\"3\"").contentType(MediaType.APPLICATION_JSON)
            .content("{\"statementId\":\"capture\",\"statement\":{\"action\":\"EDIT\",\"text\":\"Human paragraph\",\"rationale\":\"Precise wording\"}}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.currentRevision.statements[0].editingOrigin").value("HUMAN"));
    }
}
