package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.operation.AnalysisAdapterStore;
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
    @Autowired(required = false) private AnalysisNodeReceipts receipts;

    public AnalysisChildAssessment(TaxonomyService catalogue, LlmService llm, AnalysisFollowUpProviders providers,
                                   AnalyzeNodeChildrenUseCase nodes, JustifyLeafUseCase leaves) {
        this.catalogue = catalogue;
        this.llm = llm;
        this.providers = providers;
        this.nodes = nodes;
        this.leaves = leaves;
    }

    private record Pin(AnalysisFollowUpProviders.Binding binding, RuntimeException unavailable,
                       AnalysisReasonLength length, AnalysisLanguage language) {
        AnalysisFollowUpProviders.Binding require() {
            if (unavailable != null) throw unavailable;
            return binding;
        }
    }

    private Pin pin(String parent, String provider, String text, String owner, WorkspaceContext workspace,
                    AnalysisReasonLength length, AnalysisLanguage language) {
        try {
            var selected = providers.bind(parent, provider, text, owner, workspace, length, language);
            return new Pin(selected, null, selected.reasonLength(), selected.analysisLanguage());
        } catch (com.taxonomy.analysis.service.ProviderPluginUnavailableException unavailable) {
            return new Pin(null, unavailable, length, language);
        }
    }

    private boolean dispatch(Pin pin, boolean scoring) {
        if (pin.unavailable() != null) return false;
        try { return providers.execute(pin.require(), () -> llm.interactiveDispatchAvailable(scoring)); }
        catch (com.taxonomy.analysis.service.ProviderPluginUnavailableException unavailable) { return false; }
    }

    public boolean available() { return receipts != null; }

    public AnalyzeNodeChildrenResult node(AnalyzeNodeChildrenCommand command, String parent, String requestedProvider,
                                          String owner, WorkspaceContext workspace, AnalysisReasonLength length, AnalysisLanguage language) {
        try (var catalogueScope = providers.openParentCatalogue(parent, owner, workspace)) {
            return evaluateNode(command, parent, requestedProvider, owner, workspace, length, language);
        }
    }

    private AnalyzeNodeChildrenResult evaluateNode(AnalyzeNodeChildrenCommand command, String parent, String requestedProvider,
            String owner, WorkspaceContext workspace, AnalysisReasonLength length, AnalysisLanguage language) {
        var pin = pin(parent, requestedProvider, command.businessText(), owner, workspace, length, language);
        Supplier<List<TaxonomyNode>> read = () -> catalogue.getChildrenOf(command.parentCode());
        var children = List.copyOf(read.get());
        var source = llm.interactiveSource(children, true);
        BooleanSupplier current = () -> providers.parentCurrent(parent, owner, workspace) && source.equals(llm.interactiveSource(read.get(), true));
        var fingerprint = AnalysisAdapterStore.digest("node-v1", source, parent, requestedProvider,
                command.parentCode(), command.businessText(), Double.toString(command.parentScore()),
                pin.length().value(), pin.language().value());
        boolean hasParent = parent != null && !parent.isBlank();
        boolean dispatch = !children.isEmpty() && dispatch(pin, true);
        Supplier<AnalyzeNodeChildrenResult> evaluate = () -> providers.execute(pin.require(), () -> nodes.analyze(command, children));
        return receipts.executeNode(fingerprint, dispatch, id -> {
            var binding = pin.require();
            var result = evaluate.get();
            var safe = new AnalyzeNodeChildrenResult(result.scores(), result.reasons(), "", "", result.provider(),
                    result.durationMs(), result.error() == null || result.error().isBlank() ? null : "ANALYSIS_FAILED",
                    hasParent ? parent : id, binding.provider(), binding.reasonLength(), binding.analysisLanguage());
            return new AnalysisNodeReceipts.BoundNode(safe, hasParent ? null : binding,
                    new AnalysisNodeReceipts.Source(command.parentCode(), source, hasParent ? parent : null));
        }, () -> {
            var binding = pin.require();
            var id = hasParent ? parent : UUID.randomUUID().toString();
            if (!hasParent) providers.rememberBinding(id, binding, owner, workspace);
            return evaluate.get().withFollowUpContext(id, binding.provider());
        }, current);
    }

    public JustifyLeafResult leaf(JustifyLeafCommand command, String parent, String requestedProvider, String owner,
                                  WorkspaceContext workspace, AnalysisReasonLength length, AnalysisLanguage language) {
        try (var catalogueScope = providers.openParentCatalogue(parent, owner, workspace)) {
            return evaluateLeaf(command, parent, requestedProvider, owner, workspace, length, language);
        }
    }

    private JustifyLeafResult evaluateLeaf(JustifyLeafCommand command, String parent, String requestedProvider, String owner,
            WorkspaceContext workspace, AnalysisReasonLength length, AnalysisLanguage language) {
        var pin = pin(parent, requestedProvider, command.businessText(), owner, workspace, length, language);
        Supplier<List<TaxonomyNode>> read = () -> catalogue.getPathToRoot(command.nodeCode());
        var path = List.copyOf(read.get());
        var source = llm.interactiveSource(path, false);
        String fingerprint = AnalysisAdapterStore.digest("leaf-v1", source, parent, requestedProvider,
                command.nodeCode(), command.businessText(), mapDigest(command.scores()), mapDigest(command.reasons()),
                pin.length().value(), pin.language().value());
        Supplier<JustifyLeafResult> result = () -> providers.execute(pin.require(), () -> leaves.justify(command, path));
        var retainedSource = new AnalysisNodeReceipts.Source(command.nodeCode(), source, parent);
        return receipts.executeLeaf(fingerprint, dispatch(pin, false), () -> new AnalysisNodeReceipts.BoundLeaf(result.get(), retainedSource),
                result, () -> providers.parentCurrent(parent, owner, workspace) && source.equals(llm.interactiveSource(read.get(), false)));
    }

    public boolean sourceCurrent(AnalysisNodeReceipts.Source source, boolean scoring, String owner, WorkspaceContext workspace) {
        try (var catalogueScope = providers.openParentCatalogue(source.parentId(), owner, workspace)) {
            var nodes = scoring ? catalogue.getChildrenOf(source.nodeCode()) : catalogue.getPathToRoot(source.nodeCode());
            return providers.parentCurrent(source.parentId(), owner, workspace)
                    && source.fingerprint().equals(llm.interactiveSource(nodes, scoring));
        } catch (RuntimeException unavailable) {
            return false;
        }
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
