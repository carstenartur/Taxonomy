package com.taxonomy.security.service;

import com.taxonomy.backup.BackupCapability;
import com.taxonomy.backup.BackupRepositoryKey;
import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.security.model.PrincipalBinding;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Resolves verified login bindings. Username and email are never external identity keys. */
public final class PrincipalIdentityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String registryLock;

    public PrincipalIdentityService(DataSource database) {
        jdbc = new JdbcTemplate(database);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
        try (var connection = database.getConnection()) {
            boolean sqlServer = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft");
            registryLock = sqlServer
                    ? "select installation_id from principal_installation with (updlock, holdlock) where registry_key='local'"
                    : "select installation_id from principal_installation where registry_key='local' for update";
        } catch (java.sql.SQLException exception) { throw new IllegalStateException("Cannot inspect identity database", exception); }
    }

    public AppPrincipal local(long accountId) {
        return transaction.execute(status -> {
            String installation = lockRegistry();
            var accounts = jdbc.query("select principal_id, username, enabled from app_user where id=?",
                    (row, number) -> new LocalAccount(row.getString(1), row.getString(2), row.getBoolean(3)), accountId);
            if (accounts.size() != 1) throw new BadCredentialsException("Local account is unavailable");
            var account = accounts.getFirst();
            if (!account.enabled()) throw new DisabledException("Local account is disabled");
            if (account.id() == null) throw new BadCredentialsException("Local account requires identity migration");
            var binding = new IdentityBinding(IdentityBinding.Kind.LOCAL, installation, account.id());
            return resolve(binding, new PrincipalId(UUID.fromString(account.id())), account.scope());
        });
    }

    /** Call only after the OIDC token's signature, issuer, audience and lifetime have been verified. */
    public AppPrincipal oidc(String issuer, String subject) {
        var binding = new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject);
        if (issuer.length() > 2048 || subject.length() > 1024) throw new BadCredentialsException("Identity binding is too long");
        return transaction.execute(status -> {
            lockRegistry();
            var id = PrincipalId.create();
            // Legacy workspace branches embed the scope in a Git ref. Keep the
            // opaque namespace portable to native Git and Windows path rules.
            return resolve(binding, id, "principal-" + id.value());
        });
    }

    public Optional<AppPrincipal> find(PrincipalId principal) {
        return jdbc.query("select principal_id, scope_key, enabled from app_principal where principal_id=?",
                (row, number) -> new AppPrincipal(new PrincipalId(UUID.fromString(row.getString(1))), row.getString(2), row.getInt(3) == 1),
                principal.value().toString()).stream().findFirst();
    }

    public boolean isEnabled(PrincipalId principal) {
        if (find(principal).filter(AppPrincipal::enabled).isEmpty()) return false;
        var bindings = jdbc.query("select binding_kind, subject_id from principal_binding where principal_id=? and enabled=1",
                (row, number) -> new ActiveBinding(IdentityBinding.Kind.valueOf(row.getString(1)), row.getString(2)), principal.value().toString());
        for (var binding : bindings) {
            if (binding.kind() != IdentityBinding.Kind.LOCAL) return true;
            var accounts = jdbc.query("select enabled from app_user where principal_id=?",
                    (row, number) -> row.getBoolean(1), binding.subject());
            if (accounts.size() == 1 && accounts.getFirst()) return true;
        }
        return false;
    }

    public boolean hasCapability(PrincipalId principal, BackupCapability capability) {
        if (!isEnabled(principal)) return false;
        return jdbc.queryForObject("select count(*) from backup_capability_grant where principal_id=? and capability=?",
                Integer.class, principal.value().toString(), capability.name()) == 1;
    }

    public boolean hasVersionAccess(PrincipalId principal, BackupRepositoryKey repository, String commit) {
        if (!isEnabled(principal) || commit == null || !commit.matches("[0-9a-f]{40}")) return false;
        String workspace = versionScopeKey(repository);
        // Exact Java comparisons also work with case-insensitive database collations.
        return jdbc.query("select repository_id, workspace_key, commit_id from backup_version_grant where principal_id=? and repository_id=? and workspace_key=? and commit_id=?",
                (row, number) -> repository.repositoryId().equals(row.getString(1)) && workspace.equals(row.getString(2)) && commit.equals(row.getString(3)),
                principal.value().toString(), repository.repositoryId(), workspace, commit).stream().anyMatch(Boolean.TRUE::equals);
    }

    public static String versionScopeKey(BackupRepositoryKey repository) {
        return repository.workspaceId() == null ? "C:" : "W:" + repository.workspaceId();
    }

    public PrincipalId require(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw denied();
        StablePrincipal stable = authentication instanceof StablePrincipal identity ? identity
                : authentication.getPrincipal() instanceof StablePrincipal identity ? identity : null;
        if (stable == null || !isEnabled(stable.principalId())) throw denied();
        return stable.principalId();
    }

    /**
     * Validate the exact login behind a protected request against current committed authority.
     * Unlike isEnabled, a different enabled binding cannot rescue this login after unlinking.
     * A local login must still hold the roles it captured when authenticating.
     * Each decision uses one registry read, without a cross-request or per-instance cache.
     */
    public PrincipalId requireCurrent(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw denied();
        if (authentication instanceof org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                && authentication.getPrincipal() instanceof PrincipalUserDetails local) {
            PrincipalId principal = local.principalId();
            String id = principal.value().toString();
            var currentRoles = jdbc.query("""
                    select p.principal_id, p.scope_key, p.enabled, b.binding_key, b.principal_id,
                           b.binding_kind, b.issuer, b.subject_id, b.enabled, i.installation_id,
                           u.principal_id, u.enabled, r.name
                    from app_principal p
                    join principal_binding b on b.principal_id=p.principal_id and b.binding_kind='LOCAL'
                    join principal_installation i on i.registry_key='local'
                         and b.issuer=i.installation_id and b.subject_id=p.principal_id
                    join app_user u on u.principal_id=p.principal_id
                    left join user_roles ur on ur.user_id=u.id
                    left join app_role r on r.id=ur.role_id
                    where p.principal_id=?
                    """, (row, number) -> {
                String installation = row.getString(10);
                var binding = new IdentityBinding(IdentityBinding.Kind.LOCAL, installation, id);
                boolean current = id.equals(row.getString(1)) && authentication.getName().equals(row.getString(2))
                        && row.getInt(3) == 1 && PrincipalBinding.key(binding).equals(row.getString(4))
                        && id.equals(row.getString(5)) && "LOCAL".equals(row.getString(6))
                        && installation.equals(row.getString(7)) && id.equals(row.getString(8))
                        && row.getInt(9) == 1 && id.equals(row.getString(11)) && row.getBoolean(12);
                if (!current) throw denied();
                return row.getString(13);
            }, id);
            if (currentRoles.isEmpty() || local.getAuthorities().stream()
                    .anyMatch(role -> !currentRoles.contains(role.getAuthority()))) throw denied();
            return principal;
        }
        if (authentication instanceof PrincipalJwtAuthenticationToken jwt) {
            if (jwt.getToken().getIssuer() == null) throw denied();
            return requireCurrentOidc(jwt.principalId(), jwt.getName(),
                    jwt.getToken().getIssuer().toString(), jwt.getToken().getSubject());
        }
        if (authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
                && authentication.getPrincipal() instanceof PrincipalOidcUser oidc) {
            if (oidc.getIssuer() == null) throw denied();
            return requireCurrentOidc(oidc.principalId(), authentication.getName(),
                    oidc.getIssuer().toString(), oidc.getSubject());
        }
        throw denied();
    }

    private PrincipalId requireCurrentOidc(PrincipalId principal, String scope, String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || issuer.length() > 2048
                || subject == null || subject.isBlank() || subject.length() > 1024) throw denied();
        var binding = new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject);
        String id = principal.value().toString();
        String key = PrincipalBinding.key(binding);
        var matches = jdbc.query("""
                select p.principal_id, p.scope_key, p.enabled, b.binding_key, b.principal_id,
                       b.binding_kind, b.issuer, b.subject_id, b.enabled
                from app_principal p join principal_binding b on b.principal_id=p.principal_id
                where b.binding_key=?
                """, (row, number) -> id.equals(row.getString(1)) && scope.equals(row.getString(2))
                        && row.getInt(3) == 1 && key.equals(row.getString(4)) && id.equals(row.getString(5))
                        && "OIDC".equals(row.getString(6)) && issuer.equals(row.getString(7))
                        && subject.equals(row.getString(8)) && row.getInt(9) == 1, key);
        if (matches.size() != 1 || !matches.getFirst()) throw denied();
        return principal;
    }

    private static org.springframework.security.access.AccessDeniedException denied() {
        return new org.springframework.security.access.AccessDeniedException("Verified active principal required");
    }

    private String lockRegistry() { return jdbc.queryForObject(registryLock, String.class); }

    private AppPrincipal resolve(IdentityBinding identity, PrincipalId proposedId, String scope) {
        String key = PrincipalBinding.key(identity);
        var matches = jdbc.query("select principal_id, binding_kind, issuer, subject_id, enabled from principal_binding where binding_key=?",
                (row, number) -> new PrincipalBinding(new PrincipalId(UUID.fromString(row.getString(1))),
                        new IdentityBinding(IdentityBinding.Kind.valueOf(row.getString(2)), row.getString(3), row.getString(4)), row.getInt(5) == 1), key);
        if (!matches.isEmpty()) {
            var match = matches.getFirst();
            if (!match.identity().equals(identity)) throw new BadCredentialsException("Conflicting identity binding");
            if (!match.enabled() || !isEnabled(match.principal())) throw new DisabledException("Identity is disabled");
            return find(match.principal()).orElseThrow(() -> new BadCredentialsException("Principal is unavailable"));
        }
        if (jdbc.queryForObject("select count(*) from app_principal where scope_key=? or principal_id=?", Integer.class,
                scope, proposedId.value().toString()) != 0) {
            throw new BadCredentialsException("Scope requires an explicit identity mapping");
        }
        jdbc.update("insert into app_principal values (?, ?, 1)", proposedId.value().toString(), scope);
        jdbc.update("insert into principal_binding values (?, ?, ?, ?, ?, 1)", key, proposedId.value().toString(),
                identity.kind().name(), identity.issuer(), identity.subject());
        for (var capability : new BackupCapability[]{BackupCapability.EXPORT_CURRENT, BackupCapability.DOWNLOAD_BACKUP}) {
            jdbc.update("insert into backup_capability_grant values (?, ?)", proposedId.value().toString(), capability.name());
        }
        return new AppPrincipal(proposedId, scope, true);
    }

    private record LocalAccount(String id, String scope, boolean enabled) { }
    private record ActiveBinding(IdentityBinding.Kind kind, String subject) { }
}
