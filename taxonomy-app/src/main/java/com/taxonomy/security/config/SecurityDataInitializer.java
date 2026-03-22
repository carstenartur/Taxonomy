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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds the default roles and an initial admin user on first startup.
 * <p>
 * If the roles or admin user already exist (e.g. persistent database) this is a no-op.
 * The default admin password can be overridden via the {@code TAXONOMY_ADMIN_PASSWORD}
 * environment variable (or the equivalent Spring property {@code taxonomy.admin-password}).
 * <p>
 * Only active in form-login mode (without Keycloak). In the Keycloak profile,
 * users and roles are managed by the identity provider.
 */
@Component
@Profile("!keycloak")
public class SecurityDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityDataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public SecurityDataInitializer(RoleRepository roleRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${taxonomy.admin-password:admin}") String adminPassword) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Seed roles
        AppRole roleUser = findOrCreateRole("ROLE_USER");
        AppRole roleArchitect = findOrCreateRole("ROLE_ARCHITECT");
        AppRole roleAdmin = findOrCreateRole("ROLE_ADMIN");

        // Seed default admin user (if not present)
        if (userRepository.findByUsername("admin").isEmpty()) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setEnabled(true);
            admin.setDisplayName("Administrator");
            admin.setRoles(Set.of(roleUser, roleArchitect, roleAdmin));
            userRepository.save(admin);
            log.info("Created default admin user (username=admin). Change the password immediately.");
        }

        // Warn if the default password is still in use
        if ("admin".equals(adminPassword)) {
            log.warn("SECURITY WARNING: Default admin password 'admin' is in use. "
                    + "Set TAXONOMY_ADMIN_PASSWORD environment variable to change it.");
        }
    }

    private AppRole findOrCreateRole(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(new AppRole(name)));
    }
}
