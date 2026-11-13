package com.taxonomy.analysis.operation;

import com.taxonomy.analysis.service.AnalysisFollowUpProviders.Binding;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.analysis.usecase.JustifyLeafResult;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** The first durable node can be the parent of later requests from another replica. */
public interface AnalysisNodeReceipts {
    record Source(String nodeCode, String fingerprint, String parentId) {
        public Source {
            if (nodeCode == null || nodeCode.isBlank() || nodeCode.length() > 256 || nodeCode.chars().anyMatch(Character::isISOControl)
                    || parentId != null && (parentId.length() > 256 || parentId.chars().anyMatch(Character::isISOControl))
                    || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid child source");
        }
    }
    record BoundNode(AnalyzeNodeChildrenResult result, Binding parentBinding, Source source) { }
    record BoundLeaf(JustifyLeafResult result, Source source) { }

    AnalyzeNodeChildrenResult executeNode(String fingerprint, boolean dispatchAvailable,
            Function<String, BoundNode> work, Supplier<AnalyzeNodeChildrenResult> local, BooleanSupplier current);
    JustifyLeafResult executeLeaf(String fingerprint, boolean dispatchAvailable,
            Supplier<BoundLeaf> work, Supplier<JustifyLeafResult> local, BooleanSupplier current);
    Binding readParent(String operationId);
}
