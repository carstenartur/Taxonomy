package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WalkUpReformulationTest {
    static ReformulationBaseline baseline() {
        var scope=new ReformulationBaseline.Scope("repo","ws","main",1,1);
        return ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope,1,"Arbeitszeiterfassung. Ausschließlich Terminal, keine Browseroberfläche. Frist: 2 Sekunden."),
            new ReformulationBaseline.Snapshot(scope,"snap",1,"{}"),Map.of(),"de","reformulation-v1");
    }
    static WalkUpPlanner.Node node(String id,int score,String... parents) {
        return new WalkUpPlanner.Node(id,List.of(parents),id+" description",score,List.of());
    }
    @Test void groupsThreeTerminalsUnderTwoParentsAndRetainsDirectParentAndZeroRestriction() {
        var restriction=new Statement("s-zero","keine Browseroberfläche",List.of(),Statement.Provenance.ORIGINAL,List.of(),List.of(),null,Statement.EditingOrigin.SOURCE,"UNREVIEWED");
        var nodes=List.of(node("P",40),node("Q",30,"P"),node("A",50,"Q"),node("B",30,"Q"),
            node("C",20,"P"),new WalkUpPlanner.Node("Z",List.of("P"),"Browser",0,List.of(restriction)));
        var plan=new WalkUpPlanner().plan(nodes);
        assertThat(plan.stream().map(WalkUpPlanner.Step::nodeId)).containsExactly("Q","P");
        assertThat(plan.getFirst().terminalIds()).containsExactly("A","B");
        assertThat(plan.getLast().terminalIds()).containsExactly("C","Z");
        assertThat(plan.getLast().childTaskIds()).containsExactly("Q");
        assertThat(plan.getLast().directNodeIds()).containsExactly("P");
    }
    @Test void stoppedFrontIsTerminalEvenWithUnmatchedCatalogueChildren() {
        var plan=new WalkUpPlanner().plan(List.of(node("P",0),node("STOP",35,"P"),node("DEEP",0,"STOP")));
        assertThat(plan).hasSize(1);
        assertThat(plan.getFirst().terminalIds()).containsExactly("STOP");
    }
    @Test void noMatchUsesSyntheticSourceRoot() {
        assertThat(new WalkUpPlanner().plan(List.of(node("N",0))).getFirst().nodeId()).isEqualTo(WalkUpPlanner.DOCUMENT_ROOT);
    }
    @Test void dagSharedTaskIsScheduledOnceAndCyclesAndUnknownParentsFail() {
        var plan=new WalkUpPlanner().plan(List.of(node("A",1),node("B",1),node("C",1,"A","B"),node("D",1,"C")));
        assertThat(plan.stream().map(WalkUpPlanner.Step::nodeId)).containsExactly("C","A","B");
        assertThatThrownBy(()->new WalkUpPlanner().plan(List.of(node("A",1,"B"),node("B",1,"A"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A").hasMessageContaining("B");
        assertThatThrownBy(()->new WalkUpPlanner().plan(List.of(node("A",1,"missing")))).isInstanceOf(IllegalArgumentException.class);
    }
}
