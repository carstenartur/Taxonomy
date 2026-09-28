package com.taxonomy.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import com.taxonomy.security.service.UserManagementService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserCreationCredentialsTest {
    private static final String PASSWORD = "Separate-test-password-1139";

    @Test
    void separateCredentialIsHashedButNeverAddedToProfileResultOrAuditArguments() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        var encoder = new BCryptPasswordEncoder();
        var service = new UserManagementService(users, roles, encoder);
        ReflectionTestUtils.setField(service, "requirePasswordChange", true);
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        when(roles.findByName("ROLE_USER")).thenReturn(Optional.of(new AppRole("ROLE_USER")));
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        Map<String, Object> profile = Map.of("username", "alice", "displayName", "Alice",
                "email", "alice@example.test", "roles", List.of("ROLE_USER"));
        Logger logger = (Logger) LoggerFactory.getLogger(UserManagementService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, Object> result = service.createUser(profile, PASSWORD, "operator");
            var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(users).save(saved.capture());
            String hash = saved.getValue().getPasswordHash();
            assertThat(encoder.matches(PASSWORD, hash)).isTrue();
            assertThat(saved.getValue().isMustChangePassword()).isTrue();
            assertThat(profile).doesNotContainKeys("password", "passwordHash");
            assertThat(result).containsEntry("username", "alice")
                    .containsEntry("mustChangePassword", true)
                    .doesNotContainKeys("password", "passwordHash");
            assertThat(result.toString()).doesNotContain(PASSWORD, hash);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD, hash);
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(PASSWORD, hash);
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void missingOrShortSeparateCredentialIsRejectedBeforeRepositoryAccess() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        var service = new UserManagementService(users, roles, new BCryptPasswordEncoder());
        Map<String, Object> profile = Map.of("username", "alice");
        assertThatThrownBy(() -> service.createUser(profile, null, "operator"))
                .isInstanceOf(UserManagementService.ValidationException.class);
        assertThatThrownBy(() -> service.createUser(profile, "short", "operator"))
                .isInstanceOf(UserManagementService.ValidationException.class);
        verifyNoInteractions(users, roles);
    }
}
