package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.operation.AnalysisChildOperations;
import com.taxonomy.analysis.operation.AnalysisNodeReceipts;
import com.taxonomy.analysis.service.AnalysisFollowUpProviders.Binding;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
@ConditionalOnFeature({"analysis"})
public class ScopedAnalysisChildOperations implements AnalysisChildOperations, AnalysisNodeReceipts {
    private final ScopedAnalysisAdapterExecution operations;
    private final ObjectMapper json;

    public ScopedAnalysisChildOperations(ScopedAnalysisAdapterExecution operations, ObjectMapper json) {
        this.operations = operations;
        this.json = json;
    }

    @Override
    public <T> T execute(String kind, String fingerprint, Class<T> type, boolean dispatch,
                         Supplier<T> result, BooleanSupplier current) {
        return nativeResult(operations.execute(kind, fingerprint, dispatch, result, result, guard(current)), type);
    }

    @Override
    public AnalyzeNodeChildrenResult executeNode(String fingerprint, boolean dispatch,
            Function<String, BoundNode> work, Supplier<AnalyzeNodeChildrenResult> local, BooleanSupplier current) {
        var response = operations.executeWithReceipt("NODE_CHILDREN", fingerprint, dispatch, work, local, guard(current));
        return nativeResult(response, AnalyzeNodeChildrenResult.class);
    }

    @Override
    public com.taxonomy.analysis.usecase.JustifyLeafResult executeLeaf(String fingerprint, boolean dispatch,
            Supplier<BoundLeaf> work, Supplier<com.taxonomy.analysis.usecase.JustifyLeafResult> local, BooleanSupplier current) {
        var response = operations.execute("LEAF_JUSTIFICATION", fingerprint, dispatch, work, local, guard(current));
        return nativeResult(response, com.taxonomy.analysis.usecase.JustifyLeafResult.class);
    }

    @Override
    public Binding readParent(String operationId) {
        var operation = operations.readOwned(operationId);
        if (!"NODE_CHILDREN".equals(operation.kind()) || !"COMPLETED".equals(operation.state()) || operation.resultJson() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis operation is unavailable in this scope");
        var stored = json.readTree(operation.resultJson());
        if (!stored.hasNonNull("parentBinding") || !operationId.equals(stored.path("result").path("analysisOperationId").asString()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis operation is unavailable in this scope");
        return json.treeToValue(stored.path("parentBinding"), Binding.class);
    }

    private static BooleanSupplier guard(BooleanSupplier current) {
        return () -> {
            try { return current.getAsBoolean(); }
            catch (RuntimeException unavailable) { return false; }
        };
    }

    private <T> T nativeResult(ResponseEntity<?> response, Class<T> type) {
        if (response.getStatusCode().value() != 200) throw new ReceiptStatus(response);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request && request.getResponse() != null)
            response.getHeaders().forEach((name, values) -> values.forEach(value -> request.getResponse().addHeader(name, value)));
        return type.isInstance(response.getBody()) ? type.cast(response.getBody()) : json.convertValue(response.getBody(), type);
    }

    static final class ReceiptStatus extends ResponseStatusException {
        final ResponseEntity<?> receipt;
        ReceiptStatus(ResponseEntity<?> receipt) {
            super(receipt.getStatusCode(), "Analysis operation status");
            this.receipt = receipt;
        }
    }
}
