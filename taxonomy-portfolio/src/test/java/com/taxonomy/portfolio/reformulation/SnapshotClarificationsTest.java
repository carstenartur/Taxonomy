package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationSearchModel.Direction;
import com.taxonomy.dto.RelationSearchModel.Result;
import com.taxonomy.dto.RelationSearchModel.Unfinished;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.Section;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotClarificationsTest {
    @Test void retainsLegacyRootAndRelationQuestionsWithTheirScopeAndExcludesInterruptedWork() {
        var result = new AnalysisResult();
        result.setAnalysisCoverage(new AnalysisCoverage(Map.of(
                "BP", unknown("CLARIFICATION:No owner was specified. Who approves?"),
                "AP", unknown("FAILED:Connection interrupted")), 0, 2, 2));
        var work = new Unfinished("BP-5", "USES", Direction.OUTGOING, List.of("IP-7"),
                "UNRESOLVED", "Which document?", "claim-a", "IP");
        var otherClaim = new Unfinished("BP-5", "USES", Direction.OUTGOING, List.of("IP-7"),
                "UNRESOLVED", "Which document?", "claim-b", "IP");
        var timeout = new Unfinished("CP", "USES", Direction.INCOMING, List.of("AP"),
                "CALL_BUDGET", "Budget exhausted", null, null);
        var view = new RequirementArchitectureView();
        view.setRelationSearchReport(new RelationSearchReport(1, "test", "test", List.of(),
                new Result(List.of(), List.of(work, work, otherClaim, timeout), List.of(), 1, 1, 1),
                1, 5, 1, List.of(), ""));
        result.setArchitectureView(view);
        var json = new PortfolioJsonCodec(JsonMapper.builder().build());
        var first = SnapshotClarifications.from(result, "snapshot-a", json);
        assertThat(first.questions()).extracting(DecisionQuestion::wording).containsExactly(
                "No owner was specified. Who approves?", "Which document?", "Which document?");
        assertThat(first.sections()).extracting(Section::id).containsExactly("BP", "BP-5", "IP-7");
        assertThat(first.sections().getLast().questionIds()).hasSize(2).doesNotHaveDuplicates();
        assertThat(SnapshotClarifications.from(result, "snapshot-a", json)).isEqualTo(first);
        assertThat(SnapshotClarifications.from(result, "snapshot-b", json).questions()).extracting(DecisionQuestion::id)
                .doesNotContainAnyElementsOf(first.questions().stream().map(DecisionQuestion::id).toList());
    }

    private static AnalysisCoverage.NodeAssessment unknown(String reason) {
        return new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                AnalysisCoverage.Descendants.UNASSESSED, reason);
    }
}
