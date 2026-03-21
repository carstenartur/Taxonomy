package com.taxonomy.security.service;

import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DatabaseUserDetailsService service;

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed-password-123");
        user.setEnabled(true);
        user.setRoles(Set.of(new AppRole("ROLE_USER")));
    }

    @Test
    void loadUserByUsername_userFoundWithSingleRole_returnsCorrectUserDetails() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("testuser");

        assertEquals("testuser", details.getUsername());
        assertEquals("hashed-password-123", details.getPassword());
        assertTrue(details.isEnabled());
        assertEquals(1, details.getAuthorities().size());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void loadUserByUsername_userFoundWithMultipleRoles_allAuthoritiesMapped() {
        user.setRoles(Set.of(
                new AppRole("ROLE_USER"),
                new AppRole("ROLE_ADMIN"),
                new AppRole("ROLE_ARCHITECT")
        ));
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("testuser");

        Set<String> authorities = details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertEquals(3, authorities.size());
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("ROLE_ARCHITECT"));
    }

    @Test
    void loadUserByUsername_userNotFound_throwsUsernameNotFoundException() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        UsernameNotFoundException ex = assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown")
        );
        assertTrue(ex.getMessage().contains("unknown"));
    }

    @Test
    void loadUserByUsername_userDisabled_userDetailsIsNotEnabled() {
        user.setEnabled(false);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("testuser");

        assertFalse(details.isEnabled());
    }

    @Test
    void loadUserByUsername_userEnabled_userDetailsIsEnabled() {
        user.setEnabled(true);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("testuser");

        assertTrue(details.isEnabled());
    }

    @Test
    void loadUserByUsername_userWithNoRoles_emptyAuthorities() {
        user.setRoles(Set.of());
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("testuser");

        assertTrue(details.getAuthorities().isEmpty());
    }

    @Test
    void loadUserByUsername_passwordHashMappedCorrectly() {
        user.setPasswordHash("$2a$10$someBcryptHash");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("testuser");

        assertEquals("$2a$10$someBcryptHash", details.getPassword());
    }

    @Test
    void loadUserByUsername_usernameMappedCorrectly() {
        user.setUsername("admin@example.com");
        when(userRepository.findByUsername("admin@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("admin@example.com");

        assertEquals("admin@example.com", details.getUsername());
    }
}
