package com.taxonomy.portfolio.reformulation;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class ReformulationImpactTest {
    @Test void boundaryImpactIsOneHopAndDoesNotCascadeAcrossUnrelatedInterfaces() {
        var scope=new ReformulationBaseline.Scope("repo","ws","branch",1L,2L);
        var baseline=ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope,3L,"Source"),new ReformulationBaseline.Snapshot(scope,"snapshot",3L,"{}"),
            Map.of("relationMappings","[{\"id\":1,\"sourceCode\":\"A\",\"targetCode\":\"B\"},{\"id\":2,\"sourceCode\":\"B\",\"targetCode\":\"C\"}]"),"en","v1");
        var q=new DecisionQuestion("q",new DecisionQuestion.Key("subject","dimension","A"),"Question",List.of(),List.of("a"),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),"Impact",DecisionQuestion.State.OPEN);
        var sections=List.of(new Section("ROOT","T","Root","Root",List.of("A","B","C"),List.of(),List.of()),new Section("A","T","A","A",List.of(),List.of("a"),List.of("q")),new Section("B","T","B","B",List.of(),List.of("b"),List.of()),new Section("C","T","C","C",List.of(),List.of("c"),List.of()));
        var revision=new ReformulationDtos.Revision(1,null,"Source",sections,List.of(),List.of(q),List.of(),new ValidationReport(List.of()),"architect",Instant.EPOCH,"Fixture");
        var impact=ReformulationQuestionService.impact(revision,q,baseline);
        assertThat(impact.sectionIds()).containsExactlyInAnyOrder("ROOT","A","B");
        assertThat(impact.boundaryEdgeIds()).containsExactly("edge-1");
    }
    @Test void humanStatementEditInvalidatesItsDependentQuestionsWithoutInventingAQuestion() {
        var scope=new ReformulationBaseline.Scope("repo","ws","branch",1L,2L);
        var baseline=ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope,3L,"Source"),new ReformulationBaseline.Snapshot(scope,"snapshot",3L,"{}"),Map.of(),"en","v1");
        var schema=new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null);
        var question=new DecisionQuestion("q",new DecisionQuestion.Key("subject","dimension","A"),"Question",List.of(),List.of("a"),schema,List.of(),List.of("follow"),"Impact",DecisionQuestion.State.OPEN);
        var follow=new DecisionQuestion("follow",new DecisionQuestion.Key("subject","follow","B"),"Follow-up",List.of(),List.of("b"),schema,List.of("q"),List.of(),"Impact",DecisionQuestion.State.OPEN);
        var edit=new DecisionQuestion("edit-a",new DecisionQuestion.Key("a","edit","local"),"Human edit",List.of(),List.of("a"),schema,List.of(),List.of(),"Impact",DecisionQuestion.State.ANSWERED);
        var sections=List.of(new Section("A","T","A","A",List.of(),List.of("a"),List.of("q")),new Section("B","T","B","B",List.of(),List.of("b"),List.of("follow")),new Section("C","T","C","C",List.of(),List.of("c"),List.of()));
        var statements=List.of("a","b","c").stream().map(id->new Statement(id,id,List.of(),Statement.Provenance.MODEL_ADDITION,List.of(),List.of(),null,Statement.EditingOrigin.MODEL,"UNREVIEWED")).toList();
        var revision=new ReformulationDtos.Revision(1,null,"Source",sections,statements,List.of(question,follow),List.of(),new ValidationReport(List.of()),"architect",Instant.EPOCH,"Fixture");
        var impact=ReformulationQuestionService.impact(revision,edit,baseline);
        assertThat(impact.questionIds()).containsExactlyInAnyOrder("q","follow");
        assertThat(impact.statementIds()).containsExactlyInAnyOrder("a","b");
        assertThat(impact.sectionIds()).containsExactlyInAnyOrder("A","B");
    }
}
