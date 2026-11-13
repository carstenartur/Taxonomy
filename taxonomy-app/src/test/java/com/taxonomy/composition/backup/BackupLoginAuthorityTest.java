package com.taxonomy.composition.backup;

import com.taxonomy.backup.BackupCapability;
import com.taxonomy.backup.BackupScope;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.backup.jobs.BackupCaptureSource;
import com.taxonomy.backup.runtime.BackupAccessPolicy;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupLoginAuthorityTest {
    @Test void existingLoginLosesBackupDiscoveryAndRequestAccessWhenItsBindingIsRevoked() {
        var login = mock(Authentication.class);
        var principal = PrincipalId.create();
        var identities = mock(PrincipalIdentityService.class);
        when(identities.requireCurrent(login)).thenReturn(principal);
        var access = mock(BackupAccessPolicy.class);
        when(access.hasCapability(principal, BackupCapability.EXPORT_CURRENT, new BackupScope.Installation()))
                .thenReturn(true);
        var sources = new StaticListableBeanFactory();
        sources.addBean("capture", mock(BackupCaptureSource.class));
        var navigation = new BackupNavigationAdvice(sources.getBeanProvider(BackupCaptureSource.class), identities, access);
        var requests = new BackupSecurityConfiguration().backupPrincipalResolver(identities);

        assertThat(requests.require(login)).isEqualTo(principal);
        assertThat(navigation.available(login)).isTrue();

        when(identities.requireCurrent(login)).thenThrow(new AccessDeniedException("Login binding revoked"));
        assertThatThrownBy(() -> requests.require(login)).isInstanceOf(AccessDeniedException.class);
        assertThat(navigation.available(login)).isFalse();
    }
}
