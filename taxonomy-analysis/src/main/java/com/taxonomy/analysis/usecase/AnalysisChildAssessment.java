package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.operation.AnalysisAdapterStore;
import com.taxonomy.analysis.operation.AnalysisChildOperations;
import com.taxonomy.analysis.operation.AnalysisNodeReceipts;
import com.taxonomy.analysis.service.AnalysisFollowUpProviders;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Separates typed public results from the scope and immutable provider binding retained for follow-ups. */
@Service
public class AnalysisChildAssessment {
    private final TaxonomyService catalogue;
    private final LlmService llm;
    private final AnalysisFollowUpProviders providers;
    private final AnalyzeNodeChildrenUseCase nodes;
    private final JustifyLeafUseCase leaves;
    @Autowired(required = false) private AnalysisChildOperations operations;
    @Autowired(required = false) private AnalysisNodeReceipts receipts;

    public AnalysisChildAssessment(TaxonomyService catalogue, LlmService llm, AnalysisFollowUpProviders providers,
                                   AnalyzeNodeChildrenUseCase nodes, JustifyLeafUseCase leaves) {
        this.catalogue = catalogue;
        this.llm = llm;
        this.providers = providers;
        this.nodes = nodes;
        this.leaves = leaves;
    }

    public boolean available() { return operations != null && receipts != null; }

    public AnalyzeNodeChildrenResult node(AnalyzeNodeChildrenCommand command, String parent, String requestedProvider,
                                          String owner, WorkspaceContext workspace, AnalysisReasonLength length, AnalysisLanguage language) {
        var binding = providers.bind(parent, requestedProvider, command.businessText(), owner, workspace, length, language);
        Supplier<List<TaxonomyNode>> read = () -> catalogue.getChildrenOf(command.parentCode());
        var children = List.copyOf(read.get());
        var source = llm.interactiveSource(children, true);
        BooleanSupplier current = () -> source.equals(llm.interactiveSource(read.get(), true));
        var fingerprint = AnalysisAdapterStore.digest("node-v1", source, parent, requestedProvider,
                command.parentCode(), command.businessText(), Double.toString(command.parentScore()),
                binding.reasonLength().value(), binding.analysisLanguage().value());
        boolean hasParent = parent != null && !parent.isBlank();
        boolean dispatch = !children.isEmpty() && providers.execute(binding, () -> llm.interactiveDispatchAvailable(true));
        Supplier<AnalyzeNodeChildrenResult> evaluate = () -> providers.execute(binding, () -> nodes.analyze(command, children));
        return receipts.executeNode(fingerprint, dispatch, id -> {
            var result = evaluate.get();
            var safe = new AnalyzeNodeChildrenResult(result.scores(), result.reasons(), "", "", result.provider(),
                    result.durationMs(), result.error() == null || result.error().isBlank() ? null : "ANALYSIS_FAILED",
                    hasParent ? parent : id, binding.provider(), binding.reasonLength(), binding.analysisLanguage());
            return new AnalysisNodeReceipts.BoundNode(safe, hasParent ? null : binding);
        }, () -> {
            var id = hasParent ? parent : UUID.randomUUID().toString();
            if (!hasParent) providers.rememberBinding(id, binding, owner, workspace);
            return evaluate.get().withFollowUpContext(id, binding.provider());
        }, current);
    }

    public JustifyLeafResult leaf(JustifyLeafCommand command, String parent, String requestedProvider, String owner,
                                  WorkspaceContext workspace, AnalysisReasonLength length, AnalysisLanguage language) {
        var binding = providers.bind(parent, requestedProvider, command.businessText(), owner, workspace, length, language);
        Supplier<List<TaxonomyNode>> read = () -> catalogue.getPathToRoot(command.nodeCode());
        var path = List.copyOf(read.get());
        var source = llm.interactiveSource(path, false);
        String fingerprint = AnalysisAdapterStore.digest("leaf-v1", source, parent, requestedProvider,
                command.nodeCode(), command.businessText(), mapDigest(command.scores()), mapDigest(command.reasons()),
                binding.reasonLength().value(), binding.analysisLanguage().value());
        return operations.execute("LEAF_JUSTIFICATION", fingerprint, JustifyLeafResult.class,
                providers.execute(binding, () -> llm.interactiveDispatchAvailable(false)),
                () -> providers.execute(binding, () -> leaves.justify(command, path)),
                () -> source.equals(llm.interactiveSource(read.get(), false)));
    }

    private static String mapDigest(Map<String, ?> values) {
        var parts = new ArrayList<String>();
        new TreeMap<>(values).forEach((key, value) -> {
            parts.add(key);
            parts.add(value.toString());
        });
        return AnalysisAdapterStore.digest(parts.toArray(String[]::new));
    }
}
