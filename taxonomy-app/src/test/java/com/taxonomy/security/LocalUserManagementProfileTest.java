package com.taxonomy.security;

import com.taxonomy.security.config.LocalUserManagementAccess;
import com.taxonomy.security.controller.AccountContextController;
import com.taxonomy.security.controller.UserManagementController;
import com.taxonomy.security.controller.UserManagementPageController;
import com.taxonomy.security.service.UserManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalUserManagementProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(UserManagementService.class, () -> mock(UserManagementService.class))
            .withUserConfiguration(AccountContextController.class,
                    UserManagementController.class, UserManagementPageController.class);

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "hsqldb;true;false;true",
            "hsqldb,local-user-management;true;true;true",
            "hsqldb,local-user-management;false;false;false",
            "local-user-management,keycloak;true;false;false",
            "keycloak,local-user-management;true;false;false",
            "keycloak;false;false;false"
    })
    void profilePropertyAndExternalAuthenticationAgree(
            String profiles, boolean localAccounts, boolean pagePresent, boolean apiPresent) {
        runner.withPropertyValues("spring.profiles.active=" + profiles,
                "taxonomy.security.local-users-enabled=" + localAccounts).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(UserManagementPageController.class))
                    .hasSize(pagePresent ? 1 : 0);
            assertThat(context.getBeansOfType(UserManagementController.class))
                    .hasSize(apiPresent ? 1 : 0);
            assertThat(LocalUserManagementAccess.isEnabled(context.getEnvironment()))
                    .isEqualTo(pagePresent);
            var controller = context.getBean(AccountContextController.class);
            var administrator = new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN");
            assertThat(((Map<?, ?>) controller.currentAccount(administrator).getBody())
                    .get("localUserManagementAllowed")).isEqualTo(pagePresent);
            var reader = new TestingAuthenticationToken("reader", "unused", "ROLE_USER");
            assertThat(((Map<?, ?>) controller.currentAccount(reader).getBody())
                    .get("localUserManagementAllowed")).isEqualTo(false);
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"on", "yes", "1", "invalid"})
    void navigationUsesTheSameLiteralTrueContractAsConditionalRegistration(String value) {
        runner.withPropertyValues("spring.profiles.active=local-user-management",
                "taxonomy.security.local-users-enabled=" + value).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(UserManagementPageController.class);
            assertThat(LocalUserManagementAccess.isEnabled(context.getEnvironment())).isFalse();
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "TRUE", "True", "tRuE"})
    void trueIsCaseInsensitiveInBothRegistrationAndNavigation(String value) {
        runner.withPropertyValues("spring.profiles.active=local-user-management",
                "taxonomy.security.local-users-enabled=" + value).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UserManagementPageController.class)
                    .hasSingleBean(UserManagementController.class);
            assertThat(LocalUserManagementAccess.isEnabled(context.getEnvironment())).isTrue();
        });
    }

    @Test
    void existingLocalAccountDefaultDoesNotOptInThePage() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UserManagementController.class)
                    .doesNotHaveBean(UserManagementPageController.class);
        });
    }

    @Test
    void profileCanUseTheExistingLocalAccountDefault() {
        runner.withPropertyValues("spring.profiles.active=local-user-management").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UserManagementPageController.class);
        });
    }
}
