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
}
