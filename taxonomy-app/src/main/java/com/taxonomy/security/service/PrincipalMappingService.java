package com.taxonomy.security.service;

import com.taxonomy.backup.*;
import com.taxonomy.security.model.PrincipalBinding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.util.*;

/** Builds the target side of a restore preview from the server registry, never from archive claims. */
@Service
public final class PrincipalMappingService {
    private final JdbcTemplate jdbc;
    private final PrincipalIdentityService identities;

    public PrincipalMappingService(DataSource database, PrincipalIdentityService identities) {
        jdbc = new JdbcTemplate(database); this.identities = identities;
    }

    public PrincipalMappingPlan preview(SourceIdentitySet source) {
        if (source.identities().size() > 10_000) throw new IllegalArgumentException("Too many source identities");
        var keys = new LinkedHashSet<IdentityBinding>();
        for (var identity : source.identities()) {
            keys.addAll(identity.bindings());
            if (keys.size() > 10_000) throw new IllegalArgumentException("Too many source bindings");
        }
        var candidates = new LinkedHashSet<PrincipalId>();
        for (var key : keys) {
            jdbc.query("select principal_id, binding_kind, issuer, subject_id from principal_binding where binding_key=? and enabled=1",
                    row -> {
                        var actual = new IdentityBinding(IdentityBinding.Kind.valueOf(row.getString(2)), row.getString(3), row.getString(4));
                        if (!actual.equals(key)) throw new IllegalStateException("Conflicting identity binding");
                        candidates.add(new PrincipalId(UUID.fromString(row.getString(1))));
                    }, PrincipalBinding.key(key));
        }
        var targets = new ArrayList<TargetIdentityContext.Identity>();
        for (var candidate : candidates) {
            var principal = identities.find(candidate).orElseThrow();
            var bindings = jdbc.query("select binding_kind, issuer, subject_id from principal_binding where principal_id=? and enabled=1",
                    (row, number) -> new IdentityBinding(IdentityBinding.Kind.valueOf(row.getString(1)), row.getString(2), row.getString(3)), candidate.value().toString());
            targets.add(new TargetIdentityContext.Identity(candidate, principal.scopeKey(), null, identities.isEnabled(candidate), Set.copyOf(bindings)));
        }
        return PrincipalMapping.preview(source, new TargetIdentityContext(targets));
    }
}
