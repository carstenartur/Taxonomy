package com.taxonomy.analysis.operation;

import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.service.AnalysisFollowUpProviders.Binding;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.analysis.usecase.JustifyLeafResult;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisReasonLength;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ChildAnalysisReceiptTest {
    final ObjectMapper json = new ObjectMapper();
    final String id = UUID.randomUUID().toString();
    final String hash = "c".repeat(64);

    @Test void archiveChecksParentIdPinAndDiagnosticPrivacyBeforeRestoringNativeNode() {
        var node = new AnalyzeNodeChildrenResult(Map.of("child", 91d), Map.of("child","because"), "", "", "Gemini", 7,
                null, id, "GEMINI", AnalysisReasonLength.SHORT, AnalysisLanguage.GERMAN);
        var pin = new Binding("GEMINI", null, AnalysisReasonLength.SHORT, AnalysisLanguage.GERMAN, RequirementReference.adHoc("input"));
        var stored = json.valueToTree(new AnalysisNodeReceipts.BoundNode(node, pin, new AnalysisNodeReceipts.Source("CP", hash, null)));
        ChildAnalysisReceipt.validate("NODE_CHILDREN", id, stored);
        assertThat(ChildAnalysisReceipt.publicResult("NODE_CHILDREN", stored)).isEqualTo(json.valueToTree(node));
        assertThatThrownBy(() -> ChildAnalysisReceipt.validate("NODE_CHILDREN", UUID.randomUUID().toString(), stored))
                .isInstanceOf(IllegalArgumentException.class);
        var target = UUID.randomUUID().toString();
        var moved = ChildAnalysisReceipt.restoreResult("NODE_CHILDREN", id, target, stored);
        assertThat(moved.path("result").path("analysisOperationId").asString()).isEqualTo(target);
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
}
