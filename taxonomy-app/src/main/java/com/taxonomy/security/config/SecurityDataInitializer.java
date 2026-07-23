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
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** Seeds local roles and the initial administrator in form-login mode. */
@Component
@Profile("!keycloak")
public class SecurityDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityDataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final boolean requirePasswordChange;

    public SecurityDataInitializer(RoleRepository roleRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${taxonomy.admin-password:admin}") String adminPassword,
                                   @Value("${taxonomy.security.require-password-change:false}")
                                   boolean requirePasswordChange) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
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
            admin = new AppUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setEnabled(true);
            admin.setDisplayName("Administrator");
            admin.setRoles(Set.of(roleUser, roleArchitect, roleAdmin));
            admin.setMustChangePassword(requirePasswordChange);
            userRepository.save(admin);
            log.info("Created default admin user (username=admin, passwordChangeRequired={}).",
                    requirePasswordChange);
        } else if (requirePasswordChange
                && passwordEncoder.matches("admin", admin.getPasswordHash())
                && !admin.isMustChangePassword()) {
            admin.setMustChangePassword(true);
            userRepository.save(admin);
            log.warn("Marked existing admin account for mandatory password replacement.");
        }

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
