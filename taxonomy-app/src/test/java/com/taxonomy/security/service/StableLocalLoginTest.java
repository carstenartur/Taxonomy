package com.taxonomy.security.service;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.repository.UserRepository;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StableLocalLoginTest {
    @Test void verifiedLocalLoginCarriesStableIdentityAndRevocationIsRechecked() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:local-" + UUID.randomUUID()); database.setUser("sa");
        var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.update("insert into app_user values (1, 'alice', true)");
        PrincipalSchemaMigration.migrate(database);
        var identities = new PrincipalIdentityService(database);
        var user = new AppUser(); user.setId(1L); user.setUsername("alice"); user.setPasswordHash("unused-hash");
        user.setPrincipalId(jdbc.queryForObject("select principal_id from app_user where id=1", String.class));
        var users = mock(UserRepository.class);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        var details = new DatabaseUserDetailsService(users, identities).loadUserByUsername("alice");
        var authentication = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        assertThat(identities.require(authentication)).isEqualTo(identities.local(1L).id());
        jdbc.update("update app_user set enabled=false where id=1");
        assertThatThrownBy(() -> identities.require(authentication)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void authenticatedDisplayNameCannotSelectAStoredPrincipal() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:display-" + UUID.randomUUID()); database.setUser("sa");
        PrincipalSchemaMigration.migrate(database);
        var identities = new PrincipalIdentityService(database);
        identities.oidc("https://idp.example", "alice");
        var authentication = UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        assertThatThrownBy(() -> identities.require(authentication)).isInstanceOf(AccessDeniedException.class);
    }
}
