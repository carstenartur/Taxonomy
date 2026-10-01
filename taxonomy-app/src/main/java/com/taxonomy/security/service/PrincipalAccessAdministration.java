package com.taxonomy.security.service;

import com.taxonomy.backup.BackupCapability;
import com.taxonomy.backup.BackupRepositoryKey;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.PrincipalUserDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Grant changes and their stable-actor audit commit together; login roles are not backup grants. */
@Service
public final class PrincipalAccessAdministration {
    private final JdbcTemplate jdbc;
    private final PrincipalIdentityService identities;
    private final TransactionTemplate transaction;
    private final String lock;

    public PrincipalAccessAdministration(DataSource database, PrincipalIdentityService identities) {
        this.identities = identities; jdbc = new JdbcTemplate(database);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
        try (var connection = database.getConnection()) {
            lock = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft")
                    ? "select installation_id from principal_installation with (updlock, holdlock) where registry_key='local'"
                    : "select installation_id from principal_installation where registry_key='local' for update";
        } catch (java.sql.SQLException error) { throw new IllegalStateException("Cannot inspect identity database", error); }
    }

    public void setCapability(Authentication authentication, PrincipalId target, BackupCapability capability, boolean granted) {
        Objects.requireNonNull(capability);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject(lock, String.class);
            PrincipalId actor = requireAdministrator(authentication);
            requireTarget(target);
            jdbc.update("delete from backup_capability_grant where principal_id=? and capability=?", target.value().toString(), capability.name());
            if (granted) jdbc.update("insert into backup_capability_grant values (?, ?)", target.value().toString(), capability.name());
            audit(actor, target, granted ? "GRANT_CAPABILITY" : "REVOKE_CAPABILITY", capability.name());
        });
    }

    public void setVersionAccess(Authentication authentication, PrincipalId target, BackupRepositoryKey repository, String commit, boolean granted) {
        Objects.requireNonNull(repository);
        if (commit == null || !commit.matches("[0-9a-f]{40}") || repository.repositoryId().length() > 255
                || (repository.workspaceId() != null && repository.workspaceId().length() > 255)) {
            throw new IllegalArgumentException("A supported repository key and exact commit ID are required");
        }
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject(lock, String.class);
            PrincipalId actor = requireAdministrator(authentication);
            requireTarget(target);
            String workspace = PrincipalIdentityService.versionScopeKey(repository);
            jdbc.update("delete from backup_version_grant where principal_id=? and repository_id=? and workspace_key=? and commit_id=?",
                    target.value().toString(), repository.repositoryId(), workspace, commit);
            if (granted) jdbc.update("insert into backup_version_grant values (?, ?, ?, ?)",
                    target.value().toString(), repository.repositoryId(), workspace, commit);
            audit(actor, target, granted ? "GRANT_VERSION" : "REVOKE_VERSION", repository.repositoryId() + "/" + workspace + "@" + commit);
        });
    }

    private PrincipalId requireAdministrator(Authentication authentication) {
        PrincipalId actor = identities.require(authentication);
        boolean administrator;
        if (authentication.getPrincipal() instanceof PrincipalUserDetails) {
            administrator = jdbc.queryForObject("select count(*) from app_user u join user_roles ur on ur.user_id=u.id join app_role r on r.id=ur.role_id where u.principal_id=? and r.name='ROLE_ADMIN'",
                    Integer.class, actor.value().toString()) > 0;
        } else {
            administrator = (authentication instanceof PrincipalJwtAuthenticationToken || authentication.getPrincipal() instanceof PrincipalOidcUser)
                    && authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        }
        if (!administrator) throw new AccessDeniedException("Current administrator authority required");
        return actor;
    }

    private void requireTarget(PrincipalId target) {
        if (!identities.isEnabled(target)) throw new AccessDeniedException("Active mapped target identity required");
    }

    private void audit(PrincipalId actor, PrincipalId target, String decision, String detail) {
        jdbc.update("insert into principal_access_audit values (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(),
                actor.value().toString(), target.value().toString(), decision, detail, Instant.now().toEpochMilli());
    }
}
