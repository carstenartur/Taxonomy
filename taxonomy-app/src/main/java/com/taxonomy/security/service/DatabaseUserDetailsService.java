package com.taxonomy.security.service;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Loads user details from the database for Spring Security authentication.
 * <p>
 * Only active in form-login mode (without Keycloak). In the Keycloak profile,
 * authentication is handled by the OIDC provider — no local UserDetailsService is needed.
 */
@Service
@Profile("!keycloak")
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PrincipalIdentityService identities;

    public DatabaseUserDetailsService(UserRepository userRepository, PrincipalIdentityService identities) {
        this.userRepository = userRepository;
        this.identities = identities;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        PrincipalId principal = user.isEnabled() ? identities.local(user.getId()).id()
                : new PrincipalId(java.util.UUID.fromString(user.getPrincipalId()));
        return new PrincipalUserDetails(principal, user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                        user.getRoles().stream()
                                .map(role -> new SimpleGrantedAuthority(role.getName()))
                                .collect(Collectors.toSet())
                );
    }
}
