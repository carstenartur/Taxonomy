package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.PublicationScope;
import com.taxonomy.interop.*;
import com.taxonomy.interop.IntegrationDomainAdapter.EndpointIndex;
import com.taxonomy.interop.publication.PublicationEvidence.PublicationPreviewEnvelope;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Bounded pre-effect mappings. The optional endpoint index must come from scoped local validation,
 * never a request/import deserializer. The execution service still authorizes typed local apply. */
public final class PublicationMappings {
    private static final List<String> NATIVE_AUTHORITY = List.of("nativeProjection", "nativeSource", "nativeTarget", "nativeType");
    private final IntegrationContext endpointContext;
    private final EndpointIndex endpoints;
    private final PublicationScope endpointScope;
    public PublicationMappings() { endpointContext = null; endpointScope = null; endpoints = null; }
    public PublicationMappings(IntegrationContext validatedContext, PublicationScope validatedScope, EndpointIndex validatedEndpoints) {
        endpointContext = Objects.requireNonNull(validatedContext); endpointScope = Objects.requireNonNull(validatedScope);
        if (!endpointContext.externalScope().equals(endpointScope.externalScope())) throw IntegrationProblem.conflict("PUBLICATION_ENDPOINT_CONTEXT_MISMATCH");
        endpoints = new EndpointIndex(map(Objects.requireNonNull(validatedEndpoints).external(), MAX_SCOPE_ITEMS));
        endpoints.external().forEach((id, endpoint) -> { resource(id); Objects.requireNonNull(endpoint.kind()); text(endpoint.businessIdentity()); });
        bounded(MAX_DOCUMENT_BYTES, endpointContext, endpointScope, endpoints);
    }
    public record Result(Artifact target, Set<MappingLoss> resolvedLosses) {
        public Result { resolvedLosses = Set.copyOf(resolvedLosses); }
    }
    public Result apply(PublicationPreviewEnvelope preview, String changeId, Artifact locallyAccepted, Artifact target,
                        MappingOverride mapping, EndpointOverride endpoint, List<MappingLoss> losses) {
        if (target == null) {
            if (mapping != null || endpoint != null) throw unsupported();
            return new Result(null, Set.of());
        }
        if (mapping != null) {
            if (mapping.internalIdentity() != null || mapping.canonicalType() != null || target.kind() != ArtifactKind.REQUIREMENT
                    || mapping.titleAttribute() == null && mapping.textAttribute() == null) throw unsupported();
            try {
                if (mapping.titleAttribute() != null) text(mapping.titleAttribute());
                if (mapping.textAttribute() != null) text(mapping.textAttribute());
                target = IntegrationMappingFields.remap(target, mapping);
            } catch (RuntimeException invalidMapping) { throw unsupported(); }
            if (target.title().isBlank() || target.title().length() > 240 || target.text().isBlank() || target.text().length() > 100_000) throw unsupported();
        }
        if (target.kind() == ArtifactKind.RELATION) target = localAuthority(target, locallyAccepted);
        if (endpoint != null) target = endpoint(preview, target, endpoint);
        Set<MappingLoss> resolved = new LinkedHashSet<>();
        if (mapping != null) for (MappingLoss loss : losses) {
            if (!Objects.equals(loss.artifactId(), target.id()) && !Objects.equals(loss.artifactId(), changeId)) continue;
            if (loss.disposition() != LossDisposition.UNSUPPORTED) continue;
            boolean title = mapping.titleAttribute() != null && "title".equals(loss.field()) && "AMBIGUOUS_TITLE_ATTRIBUTE".equals(loss.code());
            boolean body = mapping.textAttribute() != null && "text".equals(loss.field())
                    && Set.of("AMBIGUOUS_TEXT_ATTRIBUTE", "EMPTY_REQUIREMENT_TEXT").contains(loss.code());
            if (title || body) resolved.add(loss);
        }
        bounded(MAX_ITEM_BYTES, target, resolved);
        return new Result(target, resolved);
    }
    private Artifact endpoint(PublicationPreviewEnvelope preview, Artifact target, EndpointOverride endpoint) {
        if (target.kind() != ArtifactKind.RELATION || endpoint.projection() == null) throw unsupported();
        if (endpointContext == null) throw new IntegrationProblem("PUBLICATION_ENDPOINT_CONTEXT_REQUIRED", 422, "Endpoint review requires a scoped local identity index");
        if (!endpointContext.equals(preview.context()) || !endpointScope.equals(preview.request().scope())) throw IntegrationProblem.conflict("PUBLICATION_ENDPOINT_CONTEXT_MISMATCH");
        bounded(MAX_ITEM_BYTES, endpoint);
        if (endpoint.projection() == RelationProjection.ARCHITECTURE_RELATION) {
            if (endpoint.canonicalType() == null || !com.taxonomy.dsl.validation.DslValidator.relationTypes().contains(endpoint.canonicalType())) throw unsupported();
        } else if (endpoint.canonicalType() != null) throw unsupported();
        if (endpoint.projection() == RelationProjection.PRESERVE_ONLY) {
            if (endpoint.sourceInternalIdentity() != null || endpoint.targetInternalIdentity() != null) throw unsupported();
        } else {
            try { text(endpoint.sourceInternalIdentity()); text(endpoint.targetInternalIdentity()); }
            catch (IllegalArgumentException invalidIdentity) { throw unsupported(); }
        }
        // This shared guard compares the exact normalized ends, endpoint kinds and scoped business identities.
        // Passing the explicit override avoids the legacy fallback that can read retained nativeProjection evidence.
        var reviewed = IntegrationDomainAdapter.relationTarget(target, endpoints, endpoint);
        if (reviewed.type() != null) target = IntegrationMappingFields.remap(target, new MappingOverride(reviewed.type(), null, null, null));
        Map<String, String> extension = new TreeMap<>(target.extensions()); NATIVE_AUTHORITY.forEach(extension::remove);
        extension.put("nativeProjection", reviewed.projection().name());
        if (reviewed.source() != null) extension.put("nativeSource", reviewed.source());
        if (reviewed.target() != null) extension.put("nativeTarget", reviewed.target());
        if (reviewed.type() != null) extension.put("nativeType", reviewed.type());
        return new Artifact(target.id(), target.kind(), target.type(), target.title(), target.text(), target.attributes(), extension);
    }
    private static Artifact localAuthority(Artifact target, Artifact local) {
        Map<String, String> extension = new TreeMap<>(target.extensions()); NATIVE_AUTHORITY.forEach(extension::remove);
        boolean sameEnds = local != null && local.kind() == ArtifactKind.RELATION
                && List.of("source", "target", "direction", "canonicalType").stream().allMatch(field -> Objects.equals(local.extensions().get(field), target.extensions().get(field)));
        if (sameEnds) for (String key : NATIVE_AUTHORITY) if (local.extensions().containsKey(key)) extension.put(key, local.extensions().get(key));
        return new Artifact(target.id(), target.kind(), target.type(), target.title(), target.text(), target.attributes(), extension);
    }
    private static IntegrationProblem unsupported() { return new IntegrationProblem("PUBLICATION_REMAP_UNSUPPORTED", 422, "This pre-effect publication mapping is unsupported"); }
}
