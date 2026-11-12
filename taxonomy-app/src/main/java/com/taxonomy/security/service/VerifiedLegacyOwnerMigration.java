package com.taxonomy.security.service;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.PrincipalBinding;
import com.taxonomy.security.model.PrincipalUserDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit, all-or-nothing mapping of disabled historical ownership scopes to verified
 * OIDC issuer/subject identities. It is deliberately not a login or self-service API:
 * an operator must verify the external ownership and retain the approval evidence.
 */
@Service
public final class VerifiedLegacyOwnerMigration {
    private static final int MAX_PREVIEW = 256;
    private static final int MAX_APPLY = 4096;
    private static final String UNRESOLVED = """
            from app_principal p
            where p.enabled=0
              and not exists (select 1 from principal_binding b where b.principal_id=p.principal_id)
            """;

    public record UnresolvedOwner(PrincipalId principal, String legacyScope) { }

    /** Fingerprint covers every unresolved row, even when the visible page is truncated. */
    public record Preview(long total, List<UnresolvedOwner> owners, String fingerprint, boolean truncated) {
        public Preview { owners = List.copyOf(owners); }
    }

    /**
     * A mapping is approved out of band against the exact immutable provider subject.
     * evidenceReference identifies that approval; its raw value is never put in the audit.
     */
    public record VerifiedMapping(PrincipalId reservedPrincipal, String exactLegacyScope,
                                  String issuer, String subject, String evidenceReference) { }

    private final JdbcTemplate jdbc;
    private final PrincipalIdentityService identities;
    private final TransactionTemplate transaction;
    private final String registryLock;

    public VerifiedLegacyOwnerMigration(DataSource database, PrincipalIdentityService identities) {
        this.jdbc = new JdbcTemplate(database);
        this.identities = Objects.requireNonNull(identities);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
        try (var connection = database.getConnection()) {
            registryLock = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                    .contains("microsoft")
                    ? "select installation_id from principal_installation with (updlock, holdlock) where registry_key='local'"
                    : "select installation_id from principal_installation where registry_key='local' for update";
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("Cannot inspect identity database", error);
        }
    }

    /** Privileged bounded preview. The count and fingerprint include rows beyond the page. */
    public Preview preview(Authentication administrator, int limit) {
        if (limit < 1 || limit > MAX_PREVIEW) throw new IllegalArgumentException("Invalid preview limit");
        return transaction.execute(status -> {
            lockRegistry();
            requireAdministrator(administrator);
            return snapshot(limit);
        });
    }

    /**
     * Apply a COMPLETE verified plan against the preview revision. Any missing/extra owner,
     * stale preview, reused subject, ambiguous scope or failed audit rolls back every link.
     * Only historical reserved principals are eligible; no new ownership is invented.
     */
    public void linkVerified(Authentication administrator, String expectedFingerprint,
                             List<VerifiedMapping> proposed) {
        Objects.requireNonNull(proposed, "proposed");
        transaction.executeWithoutResult(status -> {
            lockRegistry();
            PrincipalId actor = requireAdministrator(administrator);
            // V3 ran once: a LEGACY writer may have inserted new ownership afterwards.
            // Never accept an apparently complete approved plan while such rows are absent
            // from the reserved-principal inventory.
            if (LegacyOwnerMigrationReadiness.inspect(jdbc).missingReservations() != 0) {
                throw new IllegalStateException("New historical owners lack reserved principal identities");
            }
            var current = snapshot(MAX_APPLY);
            if (current.total() == 0 || current.total() > MAX_APPLY
                    || !current.fingerprint().equals(expectedFingerprint)
                    || proposed.size() != current.total()) {
                throw new IllegalStateException("Verified legacy mapping plan is incomplete or stale");
            }
            Map<PrincipalId, VerifiedMapping> byPrincipal = new HashMap<>();
            HashSet<String> bindingKeys = new HashSet<>();
            for (var entry : proposed) {
                if (entry == null || entry.reservedPrincipal() == null || entry.exactLegacyScope() == null
                        || entry.exactLegacyScope().isBlank() || entry.exactLegacyScope().length() > 255
                        || entry.issuer() == null || entry.issuer().isBlank() || entry.issuer().length() > 2048
                        || entry.subject() == null || entry.subject().isBlank() || entry.subject().length() > 1024
                        || entry.evidenceReference() == null || entry.evidenceReference().isBlank()
                        || entry.evidenceReference().length() > 256) {
                    throw new IllegalArgumentException("Exact verified mapping and approval reference required");
                }
                var binding = new IdentityBinding(IdentityBinding.Kind.OIDC, entry.issuer(), entry.subject());
                String key = PrincipalBinding.key(binding);
                if (byPrincipal.putIfAbsent(entry.reservedPrincipal(), entry) != null
                        || !bindingKeys.add(key)) {
                    throw new IllegalArgumentException("Duplicate principal or OIDC subject");
                }
                // Even disabled/tombstoned bindings may not silently be reassigned.
                if (jdbc.queryForObject("select count(*) from principal_binding where binding_key=?",
                        Integer.class, key) != 0) {
                    throw new IllegalStateException("OIDC subject already belongs to another binding");
                }
            }
            for (var owner : current.owners()) {
                var mapping = byPrincipal.remove(owner.principal());
                if (mapping == null || !owner.legacyScope().equals(mapping.exactLegacyScope())) {
                    throw new IllegalStateException("Mapping does not match the exact historical owner");
                }
                var binding = new IdentityBinding(IdentityBinding.Kind.OIDC, mapping.issuer(), mapping.subject());
                String id = owner.principal().value().toString();
                if (jdbc.update("update app_principal set enabled=1 where principal_id=? and scope_key=? and enabled=0",
                        id, owner.legacyScope()) != 1) {
                    throw new IllegalStateException("Historical owner changed during mapping");
                }
                jdbc.update("insert into principal_binding values (?, ?, 'OIDC', ?, ?, 1)",
                        PrincipalBinding.key(binding), id, mapping.issuer(), mapping.subject());
                audit(actor, owner.principal(), "LINK_LEGACY_OIDC", mapping.evidenceReference());
            }
            if (!byPrincipal.isEmpty() || unresolvedCount() != 0) {
                throw new IllegalStateException("Unresolved historical owners remain");
            }
        });
    }

    /**
     * Revoke a particular verified link, preserving its binding tombstone and the historical
     * ownership key. A revoked subject cannot self-register again under a fresh principal.
     */
    public void unlinkVerified(Authentication administrator, PrincipalId historicalPrincipal,
                               String issuer, String subject, String evidenceReference) {
        if (historicalPrincipal == null || issuer == null || issuer.isBlank() || issuer.length() > 2048
                || subject == null || subject.isBlank() || subject.length() > 1024
                || evidenceReference == null || evidenceReference.isBlank() || evidenceReference.length() > 256) {
            throw new IllegalArgumentException("Exact mapping and approval reference required");
        }
        transaction.executeWithoutResult(status -> {
            lockRegistry();
            PrincipalId actor = requireAdministrator(administrator);
            String id = historicalPrincipal.value().toString();
            // Do not accidentally turn this migration-specific operation into a generic
            // OIDC principal deletion API. Only principals linked by this workflow qualify.
            if (jdbc.queryForObject("select count(*) from principal_access_audit where target_principal=? and decision_kind='LINK_LEGACY_OIDC'",
                    Integer.class, id) < 1) {
                throw new AccessDeniedException("Previously verified legacy link required");
            }
            var binding = new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject);
            var bindings = jdbc.query("select binding_key, binding_kind, issuer, subject_id, enabled from principal_binding where principal_id=?",
                    (rs, index) -> new BindingRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5) == 1), id);
            if (bindings.size() != 1 || !bindings.getFirst().active()
                    || !bindings.getFirst().key().equals(PrincipalBinding.key(binding))
                    || !"OIDC".equals(bindings.getFirst().kind())
                    || !issuer.equals(bindings.getFirst().issuer())
                    || !subject.equals(bindings.getFirst().subject())) {
                throw new AccessDeniedException("Verified current OIDC link required");
            }
            if (jdbc.update("update principal_binding set enabled=0 where binding_key=? and principal_id=? and enabled=1",
                    PrincipalBinding.key(binding), id) != 1
                    || jdbc.update("update app_principal set enabled=0 where principal_id=? and enabled=1", id) != 1) {
                throw new IllegalStateException("Current principal changed during unlink");
            }
            audit(actor, historicalPrincipal, "UNLINK_LEGACY_OIDC", evidenceReference);
        });
    }

    private Preview snapshot(int limit) {
        long total = unresolvedCount();
        var digest = digest();
        var owners = new ArrayList<UnresolvedOwner>();
        jdbc.query("select p.principal_id,p.scope_key " + UNRESOLVED + " order by p.scope_key,p.principal_id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    String id = row.getString(1);
                    String scope = row.getString(2);
                    addDigestField(digest, id);
                    addDigestField(digest, scope);
                    if (owners.size() < limit) {
                        owners.add(new UnresolvedOwner(new PrincipalId(UUID.fromString(id)), scope));
                    }
                });
        return new Preview(total, owners, HexFormat.of().formatHex(digest.digest()), total > limit);
    }

    private long unresolvedCount() {
        return jdbc.queryForObject("select count(*) " + UNRESOLVED, Long.class);
    }

    private PrincipalId requireAdministrator(Authentication authentication) {
        PrincipalId actor = identities.requireCurrent(authentication);
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new AccessDeniedException("Current administrator authority required");
        }
        if (authentication.getPrincipal() instanceof PrincipalUserDetails) {
            int current = jdbc.queryForObject("""
                    select count(*) from app_user u join user_roles ur on ur.user_id=u.id
                    join app_role r on r.id=ur.role_id
                    where u.principal_id=? and u.enabled=1 and r.name='ROLE_ADMIN'
                    """, Integer.class, actor.value().toString());
            if (current != 1) throw new AccessDeniedException("Current administrator authority required");
        } else if (!(authentication instanceof PrincipalJwtAuthenticationToken)
                && !(authentication instanceof OAuth2AuthenticationToken
                     && authentication.getPrincipal() instanceof PrincipalOidcUser)) {
            throw new AccessDeniedException("Verified administrator login required");
        }
        return actor;
    }

    private void lockRegistry() { jdbc.queryForObject(registryLock, String.class); }

    private void audit(PrincipalId actor, PrincipalId target, String decision, String evidence) {
        jdbc.update("insert into principal_access_audit values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), actor.value().toString(), target.value().toString(),
                decision, "approval-sha256:" + HexFormat.of().formatHex(digest().digest(
                        evidence.getBytes(StandardCharsets.UTF_8))), Instant.now().toEpochMilli());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void addDigestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record BindingRow(String key, String kind, String issuer, String subject, boolean active) { }
}
