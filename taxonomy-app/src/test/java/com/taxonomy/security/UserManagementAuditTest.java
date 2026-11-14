package com.taxonomy.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.controller.UserManagementController;
import com.taxonomy.security.controller.UserManagementPageController;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import com.taxonomy.security.service.DatabaseUserDetailsService;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.security.service.UserManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserManagementAuditTest {
    private static final String PASSWORD = "Private-marked-credential!";
    private static final String SPOOFED = "f56cb293-aa72-4d1c-99e3-ac7328c5f692";
    private final Logger logger = (Logger) LoggerFactory.getLogger(UserManagementService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final AppRole role = new AppRole("ROLE_USER");
    private AppUser target;
    private MockMvc http;
    private UserManagementService service;

    @BeforeEach void start() {
        var roles = mock(RoleRepository.class);
        when(roles.findByName("ROLE_USER")).thenReturn(Optional.of(role));
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> {
            target = invocation.getArgument(0);
            target.setId(42L);
            return target;
        });
        when(users.findById(42L)).thenAnswer(invocation -> Optional.ofNullable(target));
        service = new UserManagementService(users, roles, passwords);
        http = MockMvcBuilders.standaloneSetup(new UserManagementController(service),
                new UserManagementPageController(service, false)).build();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void stop() { logger.detachAppender(logs); logs.stop(); }

    @Test void restAndHtmlAuditTheDistinctIdentitiesBehindTheSameOperatorName() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var first = login(1L, firstId);
        var second = login(2L, secondId);
        http.perform(post("/api/admin/users").principal(first).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"member","password":"%s","actorPrincipalId":"%s","targetPrincipalId":"%s"}
                        """.formatted(PASSWORD, SPOOFED, SPOOFED))).andExpect(status().isCreated());
        http.perform(post("/admin/users/42").principal(second).param("username", "member")
                .param("roles", "ROLE_USER").param("displayName", "Updated")).andExpect(status().is3xxRedirection());
        http.perform(put("/api/admin/users/42/password").principal(second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isOk());
        http.perform(post("/admin/users/42/status").principal(first).param("enabled", "false"))
                .andExpect(status().is3xxRedirection());
        String targetField = "targetPrincipalId=" + target.getPrincipalId();
        String firstField = "actorPrincipalId=" + firstId;
        String secondField = "actorPrincipalId=" + secondId;
        assertThat(messages()).satisfiesExactly(
                s -> assertThat(s).startsWith("USER_CREATED ").contains(firstField, targetField),
                s -> assertThat(s).startsWith("USER_UPDATED ").contains(secondField, targetField),
                s -> assertThat(s).startsWith("USER_PASSWORD_CHANGED ").contains(secondField, targetField),
                s -> assertThat(s).startsWith("USER_DISABLED ").contains(firstField, targetField));
        assertThat(messages()).allSatisfy(s -> assertThat(s).contains("by=operator", "user=member")
                .doesNotContain(PASSWORD, target.getPasswordHash(), SPOOFED));
    }

    @Test void historicalAndSystemCallsDoNotClaimAStableActor() {
        service.createUser(Map.of("username", "member", "password", PASSWORD), "operator");
        service.disableUser(42L, "system");
        assertThat(messages()).satisfiesExactly(
                s -> assertThat(s).contains("by=operator", "targetPrincipalId=" + target.getPrincipalId()).doesNotContain("actorPrincipalId="),
                s -> assertThat(s).contains("by=system", "targetPrincipalId=" + target.getPrincipalId()).doesNotContain("actorPrincipalId="));
    }

    @Test void anUntypedOrUnauthenticatedPrincipalHasNoVerifiedAuditId() {
        var verified = login(1L, UUID.randomUUID());
        var untrusted = UsernamePasswordAuthenticationToken.unauthenticated(verified.getPrincipal(), null);
        var nameOnly = UsernamePasswordAuthenticationToken.authenticated("operator", null, List.of());
        for (var auth : List.of(untrusted, nameOnly)) {
            var actor = UserManagementService.AuditActor.from(auth);
            assertThat(actor.name()).isEqualTo("operator");
            assertThat(actor.principalId()).isNull();
        }
    }

    private Authentication login(long accountId, UUID id) {
        var account = new AppUser();
        account.setId(accountId);
        account.setPrincipalId(id.toString());
        account.setUsername("operator");
        account.setEnabled(true);
        account.setRoles(Set.of(new AppRole("ROLE_ADMIN")));
        account.setPasswordHash(passwords.encode(PASSWORD));
        when(users.findByUsername("operator")).thenReturn(Optional.of(account));
        var identities = mock(PrincipalIdentityService.class);
        when(identities.local(accountId)).thenReturn(new AppPrincipal(new PrincipalId(id), "operator", true));
        var provider = new DaoAuthenticationProvider(new DatabaseUserDetailsService(users, identities));
        provider.setPasswordEncoder(passwords);
        return provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("operator", PASSWORD));
    }

    private List<String> messages() { return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }
}
