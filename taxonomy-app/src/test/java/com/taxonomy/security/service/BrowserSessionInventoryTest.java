package com.taxonomy.security.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserSessionInventoryTest {
    private final SessionRegistryImpl registry = new SessionRegistryImpl();
    private final BrowserSessionInventory inventory = new BrowserSessionInventory(registry);

    @Test
    void emptyRegistryIsExplicitlyLocalAndDoesNotInventUsers() {
        var snapshot = inventory.snapshot();
        assertThat(snapshot.scope()).isEqualTo("LOCAL_INSTANCE");
        assertThat(snapshot.userCount()).isZero();
        assertThat(snapshot.sessionCount()).isZero();
        assertThat(snapshot.users()).isEmpty();
        assertThat(snapshot.truncated()).isFalse();
    }

    @Test
    void multipleSessionsAreGroupedWithoutSerializingCredentialsOrSessionIds() {
        var user = User.withUsername("architect").password("secret-must-not-leak").roles("ADMIN").build();
        registry.registerNewSession("raw-session-one", user);
        registry.registerNewSession("raw-session-two", user);
        registry.refreshLastRequest("raw-session-two");
        var snapshot = inventory.snapshot();
        assertThat(snapshot.userCount()).isEqualTo(1);
        assertThat(snapshot.sessionCount()).isEqualTo(2);
        assertThat(snapshot.users().getFirst().sessionCount()).isEqualTo(2);
        assertThat(snapshot.users().getFirst().lastRequest()).isNotNull();
        assertThat(snapshot.toString()).doesNotContain("raw-session-", "secret-must-not-leak", "ROLE_ADMIN");
    }

    @Test
    void expiredAndDestroyedSessionsDisappearWithoutRemovingAnotherLogin() {
        var user = User.withUsername("reader").password("unused").roles("USER").build();
        var first = new MockHttpSession();
        var second = new MockHttpSession();
        registry.registerNewSession(first.getId(), user);
        registry.registerNewSession(second.getId(), user);
        registry.getSessionInformation(first.getId()).expireNow();
        assertThat(inventory.snapshot().sessionCount()).isEqualTo(1);
        registry.onApplicationEvent(new HttpSessionDestroyedEvent(second));
        assertThat(inventory.snapshot().sessionCount()).isZero();
        assertThat(inventory.snapshot().users()).isEmpty();
    }

    @Test
    void oidcUsesIssuerAndSubjectRatherThanTokenOrDisplayName() {
        registry.registerNewSession("oidc-one", oidc("https://identity.example.invalid/realm-a", "one", "Same name", "token-one"));
        registry.registerNewSession("oidc-two", oidc("https://identity.example.invalid/realm-a", "one", "New name", "token-two"));
        registry.registerNewSession("oidc-three", oidc("https://identity.example.invalid/realm-b", "one", "Same name", "token-three"));
        var snapshot = inventory.snapshot();
        assertThat(snapshot.userCount()).isEqualTo(2);
        assertThat(snapshot.sessionCount()).isEqualTo(3);
        assertThat(snapshot.users()).extracting(BrowserSessionInventory.UserSessions::sessionCount)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(snapshot.toString()).doesNotContain("token-", "identity.example.invalid", "private@example.invalid");
    }

    @Test
    void unknownPrincipalDoesNotFallBackToItsPotentiallySensitiveString() {
        registry.registerNewSession("opaque", new Object() {
            @Override public String toString() { return "never-export-this-secret"; }
        });
        var snapshot = inventory.snapshot();
        assertThat(snapshot.sessionCount()).isEqualTo(1);
        assertThat(snapshot.userCount()).isZero();
        assertThat(snapshot.unidentifiedSessionCount()).isEqualTo(1);
        assertThat(snapshot.toString()).doesNotContain("never-export-this-secret", "opaque");
    }

    @Test
    void outputIsBoundedAndTruncationDoesNotFalsifyTotals() {
        for (int i = 0; i < BrowserSessionInventory.MAX_ROWS + 3; i++) {
            registry.registerNewSession("session-" + i,
                    User.withUsername("user-" + i).password("unused").roles("USER").build());
        }
        var snapshot = inventory.snapshot();
        assertThat(snapshot.users()).hasSize(BrowserSessionInventory.MAX_ROWS);
        assertThat(snapshot.userCount()).isEqualTo(BrowserSessionInventory.MAX_ROWS + 3);
        assertThat(snapshot.sessionCount()).isEqualTo(BrowserSessionInventory.MAX_ROWS + 3);
        assertThat(snapshot.truncated()).isTrue();
    }

    private static DefaultOidcUser oidc(String issuer, String subject, String name, String token) {
        Instant now = Instant.now();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                new OidcIdToken(token, now, now.plusSeconds(60), Map.of(
                        "iss", issuer, "sub", subject, "preferred_username", name,
                        "email", "private@example.invalid")));
    }
}
