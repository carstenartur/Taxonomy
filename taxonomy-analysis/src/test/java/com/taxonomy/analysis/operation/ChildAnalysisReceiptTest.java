package com.taxonomy.analysis.operation;

import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.service.AnalysisFollowUpProviders.Binding;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.analysis.usecase.JustifyLeafResult;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.dto.RequirementNodeAssessment.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ChildAnalysisReceiptTest {
    final ObjectMapper json = new ObjectMapper();
    final String id = UUID.randomUUID().toString();
    final String hash = "c".repeat(64);

    @Test void archiveChecksParentIdPinAndDiagnosticPrivacyBeforeRestoringNativeNode() {
        var assessment = new RequirementNodeAssessment("input", List.of(SubjectKind.SERVICE), List.of(RequirementKind.FUNCTIONAL),
                ContributionType.SUPPORTS, EvidenceStatus.SUPPORTED, EvidenceBasis.INFERRED,
                Necessity.REQUIRED, Scope.DELIVERY, "", "The service supports the function.", "");
        var node = new AnalyzeNodeChildrenResult(Map.of("child", 91d), Map.of("child","because"), "", "", "Gemini", 7,
                null, id, "GEMINI", AnalysisReasonLength.SHORT, AnalysisLanguage.GERMAN, Map.of("child", List.of(assessment)));
        var pin = new Binding("GEMINI", null, AnalysisReasonLength.SHORT, AnalysisLanguage.GERMAN, RequirementReference.adHoc("input"));
        var stored = json.valueToTree(new AnalysisNodeReceipts.BoundNode(node, pin, new AnalysisNodeReceipts.Source("CP", hash, null)));
        ChildAnalysisReceipt.validate("NODE_CHILDREN", id, stored);
        assertThat(ChildAnalysisReceipt.publicResult("NODE_CHILDREN", stored)).isEqualTo(json.valueToTree(node));
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("NODE_CHILDREN", UUID.randomUUID().toString(), stored))
                .isInstanceOf(IllegalArgumentException.class);
        var target = UUID.randomUUID().toString();
        var moved = ChildAnalysisReceipt.restoreResult("NODE_CHILDREN", id, target, stored);
        assertThat(moved.path("result").path("analysisOperationId").asString()).isEqualTo(target);
        assertThat(moved.path("result").path("nodeAssessments").path("child").path(0).path("requirementQuote").asString())
                .isEqualTo("input");
        var tamperedAssessment = (tools.jackson.databind.node.ObjectNode) moved.path("result")
                .path("nodeAssessments").path("child").path(0);
        tamperedAssessment.put("prompt", "private wire data");
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("NODE_CHILDREN", target, moved))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stored.path("result").path("analysisOperationId").asString()).isEqualTo(id);
        var tampered = (tools.jackson.databind.node.ObjectNode) stored.get("result");
        tampered.put("rawResponse","private wire response");
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("NODE_CHILDREN",id,stored)).isInstanceOf(IllegalArgumentException.class);
        tampered.put("rawResponse","");
        ((tools.jackson.databind.node.ObjectNode) stored.get("parentBinding")).put("apiKey","never accepted");
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("NODE_CHILDREN",id,stored)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unrecordedLanguageIsAcceptedOnlyForAnInheritedHistoricalNode() {
        String parentId = UUID.randomUUID().toString();
        var historical = new AnalyzeNodeChildrenResult(Map.of(), Map.of(), "", "", "GEMINI", 0,
                null, parentId, "GEMINI", AnalysisReasonLength.STANDARD, null);
        var nested = json.valueToTree(new AnalysisNodeReceipts.BoundNode(historical, null,
                new AnalysisNodeReceipts.Source("CP", hash, parentId)));
        ChildAnalysisReceipt.validate("NODE_CHILDREN", id, nested);
        var first = json.valueToTree(new AnalysisNodeReceipts.BoundNode(historical, null,
                new AnalysisNodeReceipts.Source("CP", hash, null)));
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("NODE_CHILDREN", parentId, first))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void leafRestoresOnlyItsMatchingNativeNodeAndSource() {
        var leaf = new JustifyLeafResult("CP-1", "explanation");
        var stored = json.valueToTree(new AnalysisNodeReceipts.BoundLeaf(leaf, new AnalysisNodeReceipts.Source("CP-1",hash,null)));
        ChildAnalysisReceipt.validate("LEAF_JUSTIFICATION", id, stored);
        assertThat(ChildAnalysisReceipt.publicResult("LEAF_JUSTIFICATION", stored)).isEqualTo(json.valueToTree(leaf));
        ((tools.jackson.databind.node.ObjectNode) stored.get("source")).put("parentId",UUID.randomUUID().toString());
        assertThat(ChildAnalysisReceipt.restoreResult("LEAF_JUSTIFICATION",id,UUID.randomUUID().toString(),stored)).isNull();
        ((tools.jackson.databind.node.ObjectNode) stored.get("source")).put("nodeCode","other");
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("LEAF_JUSTIFICATION", id, stored)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void focusReceiptRetainsExplicitSourceWorkspaceWithoutAllowingOtherProperties() {
        for (String workspace : List.of("", "private-copy", "__shared__")) {
            var source = Map.of("kind", "ANALYSIS_SNAPSHOT", "repositoryId", "repo", "workspaceScopeKey",
                    workspace.isEmpty() ? "__shared__" : workspace, "workspaceId", workspace, "branch", "draft",
                    "projectId", 1, "requirementId", 2, "requirementVersionId", 3, "snapshotId", "retained");
            var properties = "__shared__".equals(workspace) ? Map.of("x-analysis-workspace-id", workspace) : Map.of();
            var proposal = Map.of("id", "hypothesis", "sourceId", "a", "targetId", "b", "relationType", "USES",
                    "rationale", "Reviewed scope", "evidence", List.of(), "properties", properties);
            var receipt = json.valueToTree(Map.of("source", source, "sourceId", "a", "sourceHash", hash, "method",
                    "AI:fixture", "outcome", "ASSESSED", "modelCalls", 1, "candidateCount", 1, "suggestions", List.of(proposal)));
            ChildAnalysisReceipt.validate("FOCUS_INVESTIGATION", id, receipt);
            assertThat(ChildAnalysisReceipt.publicResult("FOCUS_INVESTIGATION", receipt)).isEqualTo(receipt);
            ((tools.jackson.databind.node.ObjectNode) receipt.path("source")).put("apiKey", "not allowed");
            assertThatThrownBy(() -> ChildAnalysisReceipt.validate("FOCUS_INVESTIGATION", id, receipt))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
