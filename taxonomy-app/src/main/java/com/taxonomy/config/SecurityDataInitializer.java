package com.taxonomy.config;

import com.taxonomy.model.AppRole;
import com.taxonomy.model.AppUser;
import com.taxonomy.repository.RoleRepository;
import com.taxonomy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds the default roles and an initial admin user on first startup.
 * <p>
 * If the roles or admin user already exist (e.g. persistent database) this is a no-op.
 * The default admin password can be overridden via the {@code TAXONOMY_ADMIN_PASSWORD}
 * environment variable (or the equivalent Spring property {@code taxonomy.admin-password}).
 */
@Component
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
    }

    private AppRole findOrCreateRole(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(new AppRole(name)));
    }
}
