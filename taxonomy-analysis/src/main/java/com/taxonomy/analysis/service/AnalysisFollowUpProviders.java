package com.taxonomy.analysis.service;

import com.taxonomy.analysis.cluster.ClusterAnalysisStore;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.extension.api.plugin.PluginInvocation;
import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Resolves a follow-up's provider only within its original owner, source and requirement. */
@Service
public class AnalysisFollowUpProviders {
    private record Pin(String provider, PluginInvocation binding, AnalysisReasonLength reasonLength,
                       com.taxonomy.dto.AnalysisLanguage analysisLanguage, RequirementReference requirement,
                       String owner, WorkspaceContext workspace, Instant expires) { }
    private final LlmProviderConfig providers;
    private final LinkedHashMap<String, Pin> local = new LinkedHashMap<>();
    @Autowired(required = false) private ClusterAnalysisStore cluster;
    @Autowired(required = false) private AnalysisRuntimeSettings analysisRuntimeSettings;

    public AnalysisFollowUpProviders(LlmProviderConfig providers) { this.providers = providers; }

    /** Retention is bounded like live progress; an evicted operation must be reloaded or restarted explicitly. */
    public synchronized String remember(String operationId, String requested, String text,
                                        String owner, WorkspaceContext workspace) {
        return remember(operationId, requested, text, owner, workspace,
                AnalysisReasonContext.resolve(null, analysisRuntimeSettings));
    }

    public synchronized String remember(String operationId, String requested, String text,
                                        String owner, WorkspaceContext workspace, AnalysisReasonLength reasonLength) {
        return remember(operationId, requested, text, owner, workspace, reasonLength,
                AnalysisLanguageContext.resolveCurrentRequest(null));
    }

    public synchronized String remember(String operationId, String requested, String text,
                                        String owner, WorkspaceContext workspace, AnalysisReasonLength reasonLength,
                                        com.taxonomy.dto.AnalysisLanguage language) {
        String provider = effectiveProvider(requested);
        if (providers.isScenarioReplay(provider)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Replay requires an authorized persisted analysis operation");
        local.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        if (local.containsKey(operationId)) throw unavailable();
        while (local.size() >= 512) local.remove(local.keySet().iterator().next());
        local.put(operationId, new Pin(provider, providers.captureProviderBinding(provider), AnalysisReasonContext.effective(reasonLength), language,
                RequirementReference.adHoc(text), owner, workspace, Instant.now().plusSeconds(86400)));
        return provider;
    }

    public <T> T execute(String operationId, String requestedProvider, String text,
                         String owner, WorkspaceContext workspace, Supplier<T> work) {
        Pin pin;
        if (operationId != null && !operationId.isBlank()) {
            pin = resolve(operationId, owner, workspace);
            if (!pin.requirement().matches(text)) throw new IllegalArgumentException("Follow-up requirement does not match analysis");
            if (requestedProvider != null && !requestedProvider.isBlank()
                    && !pin.provider().equalsIgnoreCase(requestedProvider.strip()))
                throw new IllegalArgumentException("Follow-up provider does not match analysis");
        } else {
            String provider = effectiveProvider(requestedProvider);
            // Replay identities are usable only through a persisted and authorized operation.
            if (providers.isScenarioReplay(provider)) throw unavailable();
            pin = new Pin(provider, providers.captureProviderBinding(provider),
                    AnalysisReasonContext.resolve(null, analysisRuntimeSettings),
                    AnalysisLanguageContext.resolveCurrentRequest(null),
                    RequirementReference.adHoc(text),
                    owner, workspace, Instant.now());
        }
        try (var ignored = providers.withRequestProvider(pin.provider(), pin.binding());
             var reasons = AnalysisReasonContext.open(pin.reasonLength());
             var language = AnalysisLanguageContext.open(pin.analysisLanguage())) { return work.get(); }
    }

    private Pin resolve(String operationId, String owner, WorkspaceContext workspace) {
        if (owner == null || owner.isBlank() || workspace == null) throw unavailable();
        if (cluster != null) {
            try {
                var context = cluster.findAuthorized(operationId, owner, workspace);
                if (context.isPresent()) {
                    var command = cluster.command(context.get());
                    if (!Objects.equals(command.username(), owner) || !Objects.equals(command.workspaceContext(), workspace))
                        throw unavailable();
                    if (command.provider() == null || command.provider().isBlank()
                            || !context.get().requirement().matches(command.businessText())) throw unavailable();
                    return new Pin(command.provider(), command.providerBinding(),
                            AnalysisReasonContext.effective(command.analysisReasonLength()), command.analysisLanguage(),
                            context.get().requirement(), owner, workspace, Instant.MAX);
                }
            } catch (SecurityException denied) { throw unavailable(); }
        }
        synchronized (this) {
            var pin = local.get(operationId);
            if (pin == null || pin.expires().isBefore(Instant.now()) || !Objects.equals(pin.owner(), owner)
                    || !Objects.equals(pin.workspace(), workspace)) throw unavailable();
            return pin;
        }
    }

    private String effectiveProvider(String requested) {
        String explicit = requested == null || requested.isBlank() ? null : requested.strip();
        if (explicit != null && !"MOCK".equalsIgnoreCase(explicit)) {
            try { explicit = providers.requireRegisteredProvider(explicit).value(); }
            catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown analysis provider", invalid);
            }
        }
        if (providers.isScenarioReplay(explicit)) return explicit;
        if ("MOCK".equalsIgnoreCase(explicit) || providers.isMockMode()) return "MOCK";
        return explicit == null ? providers.getActiveProviderId().value() : explicit;
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis operation is unavailable in this scope");
    }
}
