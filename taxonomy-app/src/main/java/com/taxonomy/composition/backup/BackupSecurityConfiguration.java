package com.taxonomy.composition.backup;

import com.taxonomy.backup.BackupAccessPolicy;
import com.taxonomy.backup.BackupAuthorizationService;

import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "true")
public class BackupSecurityConfiguration {
    @Bean BackupAccessPolicy backupAccessPolicy(PrincipalIdentityService identities, SystemRepositoryRepository repositories,
                                               UserWorkspaceRepository workspaces, RepositoryMembershipService memberships) {
        return new PersistentBackupAccessPolicy(identities, repositories, workspaces, memberships);
    }
    @Bean BackupAuthorizationService backupAuthorizationService(BackupAccessPolicy policy) {
        return new BackupAuthorizationService(policy, Clock.systemUTC());
    }
}
