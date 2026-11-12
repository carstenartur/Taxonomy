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
    /** Stored with a node result only inside its actor-scoped receipt. */
    public record Binding(String provider, PluginInvocation binding, AnalysisReasonLength reasonLength,
                          com.taxonomy.dto.AnalysisLanguage analysisLanguage, RequirementReference requirement) {
        public Binding {
            if (provider == null || provider.isBlank() || provider.length() > 200 || provider.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid provider identity");
            Objects.requireNonNull(requirement);
            Objects.requireNonNull(reasonLength);
            Objects.requireNonNull(analysisLanguage);
        }
    }
    @Autowired(required = false) private com.taxonomy.analysis.operation.AnalysisNodeReceipts nodeReceipts;
    private final LlmProviderConfig providers;
    private final LinkedHashMap<String, Pin> local = new LinkedHashMap<>();
    @Autowired(required = false) private ClusterAnalysisStore cluster;
    @Autowired(required = false) private com.taxonomy.catalog.snapshot.CatalogueSnapshotService snapshots;
    @Autowired(required = false) private com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort models;
    @Autowired(required = false) private tools.jackson.databind.ObjectMapper mapper;
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
        rememberBinding(operationId, new Binding(provider, providers.captureProviderBinding(provider),
                AnalysisReasonContext.effective(reasonLength), language, RequirementReference.adHoc(text)), owner, workspace);
        return provider;
    }

    public synchronized void rememberBinding(String id, Binding binding, String owner, WorkspaceContext workspace) {
        local.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        if (local.containsKey(id)) throw unavailable();
        while (local.size() >= 512) local.remove(local.keySet().iterator().next());
        local.put(id, new Pin(binding.provider(), binding.binding(), binding.reasonLength(), binding.analysisLanguage(),
                binding.requirement(), owner, workspace, Instant.now().plusSeconds(86400)));
    }

    public Binding bind(String operationId, String requestedProvider, String text, String owner, WorkspaceContext workspace,
                        AnalysisReasonLength length, com.taxonomy.dto.AnalysisLanguage language) {
        if (operationId == null || operationId.isBlank()) {
            String provider = effectiveProvider(requestedProvider);
            if (providers.isScenarioReplay(provider)) throw unavailable();
            return new Binding(provider, providers.captureProviderBinding(provider),
                    AnalysisReasonContext.effective(length), language, RequirementReference.adHoc(text));
        }
        var pin = resolve(operationId, owner, workspace);
        if (!pin.requirement().matches(text)) throw new IllegalArgumentException("Follow-up requirement does not match analysis");
        if (requestedProvider != null && !requestedProvider.isBlank() && !pin.provider().equalsIgnoreCase(requestedProvider.strip()))
            throw new IllegalArgumentException("Follow-up provider does not match analysis");
        return new Binding(pin.provider(), pin.binding(), pin.reasonLength(), pin.analysisLanguage(), pin.requirement());
    }

    public <T> T execute(String operationId, String requestedProvider, String text,
                         String owner, WorkspaceContext workspace, Supplier<T> work) {
        return execute(bind(operationId, requestedProvider, text, owner, workspace,
                AnalysisReasonContext.resolve(null, analysisRuntimeSettings), AnalysisLanguageContext.resolveCurrentRequest(null)), work);
    }

    public <T> T execute(Binding pin, Supplier<T> work) {
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
            if (pin != null && !pin.expires().isBefore(Instant.now()) && Objects.equals(pin.owner(), owner)
                    && Objects.equals(pin.workspace(), workspace)) return pin;
        }
        if (nodeReceipts == null) throw unavailable();
        var binding = nodeReceipts.readParent(operationId);
        return new Pin(binding.provider(), binding.binding(), binding.reasonLength(), binding.analysisLanguage(),
                binding.requirement(), owner, workspace, Instant.MAX);
    }

    /** Cluster follow-ups read the exact catalogue shards accepted by their parent. */
    public com.taxonomy.catalog.snapshot.FrozenCatalogueContext.Scope openParentCatalogue(String id, String owner,
                                                                                          WorkspaceContext workspace) {
        if (cluster == null || id == null || id.isBlank()) return null;
        java.util.Optional<com.taxonomy.analysis.dag.AnalysisOperationContext> selected;
        try { selected = cluster.findAuthorized(id, owner, workspace); }
        catch (SecurityException denied) { throw unavailable(); }
        if (selected.isEmpty()) return null;
        var context = selected.get();
        var authority = context.authority();
        var identity = new com.taxonomy.catalog.snapshot.CatalogueSourceIdentity(authority.repositoryId(), authority.workspaceId(),
                authority.branch(), authority.sourceCommit(), authority.semanticModelSha256());
        if (snapshots == null || mapper == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Parent catalogue unavailable");
        var roots = cluster.inputRoots(context);
        var required = roots.stream().map(root -> root.code()).collect(java.util.stream.Collectors.toSet());
        var shards = roots.stream().map(root -> mapper.readValue(cluster.shard(context, root),
                com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.class)).toList();
        return snapshots.bind(identity, required, shards);
    }

    /** The workspace's current semantic generation must still match its historical parent. */
    public boolean parentCurrent(String id, String owner, WorkspaceContext workspace) {
        if (id == null || id.isBlank()) return true;
        try {
            if (cluster != null) {
                var selected = cluster.findAuthorized(id, owner, workspace);
                if (selected.isPresent()) {
                    var context = selected.get();
                    var command = cluster.command(context);
                    if (models == null) return false;
                    var current = models.capture(workspace, null, null, command.analysisScope());
                    var accepted = command.modelBinding();
                    var authority = context.authority();
                    return Objects.equals(current.commit(), authority.sourceCommit())
                            && (authority.semanticModelSha256() == null || authority.semanticModelSha256().equals(current.modelSha256()))
                            && (accepted == null || Objects.equals(accepted.semanticRevision(), current.semanticRevision())
                                && Objects.equals(accepted.modelSha256(), current.modelSha256()));
                }
            }
            resolve(id, owner, workspace);
            return true;
        } catch (ResponseStatusException | SecurityException unavailable) {
            return false;
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
