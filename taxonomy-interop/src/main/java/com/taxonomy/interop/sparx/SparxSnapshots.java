package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationProblem;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Adds scoped identity metadata to canonical exchange values without trusting it as an authorization grant. */
public final class SparxSnapshots {
    private SparxSnapshots() {}
    public static boolean isSparx(String profile) { return SparxMappingProfile.PROFILE.equals(profile); }

    public static ExchangeDocument identify(ExchangeDocument source, UUID connection) {
        Set<String> identities = new HashSet<>();
        List<Artifact> artifacts = source.artifacts().stream().map(a -> {
            Map<String, String> tags = tags(a.attributes(), connection, a.id(), identities);
            Map<String, String> extension = new TreeMap<>(a.extensions()); extension.put("internalIdentity", tags.get("tag:taxonomy.id"));
            return new Artifact(a.id(), a.kind(), a.type(), a.title(), a.text(), tags, extension);
        }).toList();
        List<Relation> relations = source.relations().stream().map(r -> {
            Map<String, String> tags = tags(r.attributes(), connection, r.id(), identities);
            Map<String, String> extension = new TreeMap<>(r.extensions()); extension.put("internalIdentity", tags.get("tag:taxonomy.id"));
            return new Relation(r.id(), r.type(), r.source(), r.target(), tags, extension);
        }).toList();
        return new ExchangeDocument(source.profile(), source.profileVersion(), source.externalVersion(), source.completeScope(), source.source(),
                artifacts, relations, source.placements(), source.metadata(), source.losses());
    }

    private static Map<String, String> tags(Map<String, String> before, UUID connection, String externalId, Set<String> identities) {
        Map<String, String> result = new TreeMap<>(before);
        String id = result.getOrDefault("tag:taxonomy.id", UUID.nameUUIDFromBytes((connection + "\u0000" + externalId).getBytes(StandardCharsets.UTF_8)).toString());
        String normalized = SparxMappingProfile.guid(id); id = normalized.substring(1, normalized.length() - 1).toLowerCase(Locale.ROOT);
        if (!identities.add(id)) throw new IntegrationProblem("DUPLICATE_IDENTITY", 400, "Two external objects claim the same Taxonomy identity");
        result.put("tag:taxonomy.id", id); result.put("tag:taxonomy.mappingProfile", SparxMappingProfile.PROFILE + "@" + SparxMappingProfile.VERSION);
        return result;
    }
}
