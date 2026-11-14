package com.taxonomy.testsupport;

import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.repository.UserRepository;
import com.taxonomy.security.service.DatabaseUserDetailsService;
import com.taxonomy.security.service.UserManagementService;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.TestSecurityContextHolder;

import java.util.List;
import java.util.Map;

/** Authenticates fixture accounts against the application's persisted local identity. */
public final class LocalTestLogin implements AutoCloseable {
    private static final String PASSWORD = "Local-Stable-Identity-Fixture-72!";

    private final SecurityContext previousRequestContext;
    private final SecurityContext previousTestContext;

    private LocalTestLogin(Authentication authentication) {
        previousRequestContext = SecurityContextHolder.getContext();
        previousTestContext = TestSecurityContextHolder.getContext();
        install(authentication);
    }

    public static LocalTestLogin open(ApplicationContext app, String username, String... roles) {
        return new LocalTestLogin(authentication(app, username, roles));
    }

    public static LocalTestLogin openExisting(ApplicationContext app, String username, String password) {
        return new LocalTestLogin(authenticate(app, username, password));
    }

    public static void login(ApplicationContext app, String username, String... roles) {
        install(authentication(app, username, roles));
    }

    public static void loginExisting(ApplicationContext app, String username, String password) {
        install(authenticate(app, username, password));
    }

    public static Authentication authentication(ApplicationContext app, String username, String... roles) {
        if (app.getBean(UserRepository.class).findByUsername(username).isEmpty()) {
            app.getBean(UserManagementService.class).createUser(
                    Map.of("username", username, "roles", List.of(roles)), PASSWORD, "test-fixture");
        }
        return authenticate(app, username, PASSWORD);
    }

    private static Authentication authenticate(ApplicationContext app, String username, String password) {
        var provider = new DaoAuthenticationProvider(app.getBean(DatabaseUserDetailsService.class));
        provider.setPasswordEncoder(app.getBean(PasswordEncoder.class));
        var authentication = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        if (!(authentication.getPrincipal() instanceof PrincipalUserDetails)) {
            throw new IllegalStateException("Expected a verified local principal");
        }
        return authentication;
    }

    private static void install(Authentication authentication) {
        TestSecurityContextHolder.setAuthentication(authentication);
    }

    @Override
    public void close() {
        TestSecurityContextHolder.setContext(previousTestContext);
        SecurityContextHolder.setContext(previousRequestContext);
    }
}
