package com.taxonomy.security.config;

import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** Seeds local roles and an explicitly configured or one-time administrator. */
@Component
@Profile("!keycloak")
@Order(Ordered.LOWEST_PRECEDENCE)
public class SecurityDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityDataInitializer.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String configuredAdminPassword;
    private final boolean requirePasswordChange;

    public SecurityDataInitializer(RoleRepository roleRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${taxonomy.admin-password:}") String configuredAdminPassword,
                                   @Value("${taxonomy.security.require-password-change:true}")
                                   boolean requirePasswordChange) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuredAdminPassword = configuredAdminPassword;
        this.requirePasswordChange = requirePasswordChange;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppRole roleUser = findOrCreateRole("ROLE_USER");
        AppRole roleArchitect = findOrCreateRole("ROLE_ARCHITECT");
        AppRole roleAdmin = findOrCreateRole("ROLE_ADMIN");

        AppUser admin = userRepository.findByUsername("admin").orElse(null);
        if (admin == null) {
            boolean generatedBootstrapPassword = configuredAdminPassword == null
                    || configuredAdminPassword.isBlank();
            String effectivePassword = generatedBootstrapPassword
                    ? generateBootstrapPassword()
                    : configuredAdminPassword;

            admin = new AppUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(effectivePassword));
            admin.setEnabled(true);
            admin.setDisplayName("Administrator");
            admin.setRoles(Set.of(roleUser, roleArchitect, roleAdmin));
            admin.setMustChangePassword(generatedBootstrapPassword || requirePasswordChange);
            userRepository.save(admin);

            if (generatedBootstrapPassword) {
                log.warn("Generated a one-time local administrator bootstrap password. "
                                + "Sign in as 'admin' with this value and replace it immediately: {}",
                        effectivePassword);
            } else {
                log.info("Created configured administrator account "
                                + "(passwordChangeRequired={}).",
                        requirePasswordChange);
            }
        } else if (!admin.isMustChangePassword()
                && passwordEncoder.matches("admin", admin.getPasswordHash())) {
            // One-time migration for accounts created by historic releases with
            // the removed built-in credential. Do not re-lock normal accounts on
            // every application restart.
            admin.setMustChangePassword(true);
            userRepository.save(admin);
            log.warn("Detected a legacy administrator credential and required replacement.");
        }
    }

    private AppRole findOrCreateRole(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(new AppRole(name)));
    }

    private static String generateBootstrapPassword() {
        byte[] entropy = new byte[24];
        SECURE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
