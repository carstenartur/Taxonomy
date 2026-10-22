package com.taxonomy.workspace.service;

import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalGitCredentialsTest {
    @Test
    void globalCredentialNeverReachesANonPrimaryRepository() {
        Transport transport = mock(Transport.class);
        ExternalGitCredentials credentials = new ExternalGitCredentials(
                "legacy-user", "fixture-global-token", new MockEnvironment());

        credentials.configure(transport, "secondary", false);

        assertFalse(credentials.isConfigured("secondary", false));
        verifyNoInteractions(transport);
        assertTrue(credentials.isConfigured("primary", true));
    }

    @Test
    void repositoryCredentialOverridesGlobalAndStaysScopedToItsRepository() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("taxonomy.dsl.git.remotes.secondary.username", "  secondary-user  ")
                .withProperty("taxonomy.dsl.git.remotes.secondary.token", "fixture-scoped-token");
        ExternalGitCredentials credentials = new ExternalGitCredentials(
                "legacy-user", "fixture-global-token", environment);
        Transport transport = mock(Transport.class);

        credentials.configure(transport, "secondary", false);

        ArgumentCaptor<CredentialsProvider> provider = ArgumentCaptor.forClass(CredentialsProvider.class);
        verify(transport).setCredentialsProvider(provider.capture());
        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(provider.getValue().get(new URIish("https://example.invalid/repo.git"), username, password));
        assertEquals("secondary-user", username.getValue());
        assertArrayEquals("fixture-scoped-token".toCharArray(), password.getValue());
        assertFalse(credentials.isConfigured("other", false));
    }

    @Test
    void explicitEmptyPrimaryMappingDisablesLegacyCredential() {
        ExternalGitCredentials credentials = new ExternalGitCredentials("legacy-user", "fixture-global-token",
                new MockEnvironment().withProperty("taxonomy.dsl.git.remotes.primary.token", ""));
        assertFalse(credentials.isConfigured("primary", true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentSecretDoesNotChangeTransportCredentials(String token) {
        Transport transport = mock(Transport.class);
        ExternalGitCredentials credentials = new ExternalGitCredentials("alice", token);
        assertFalse(credentials.isConfigured());
        credentials.configure(transport);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "alice"})
    void configuredSecretReachesOnlyTheTransportWithTheResolvedUsername(String username) throws Exception {
        Transport transport = mock(Transport.class);
        ExternalGitCredentials credentials = new ExternalGitCredentials(username, "fixture-token-not-a-secret");
        assertTrue(credentials.isConfigured());
        credentials.configure(transport);
        ArgumentCaptor<CredentialsProvider> provider = ArgumentCaptor.forClass(CredentialsProvider.class);
        verify(transport).setCredentialsProvider(provider.capture());
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(provider.getValue().get(new URIish("https://example.invalid/repo.git"), user, password));
        assertEquals(username == null || username.isBlank() ? "oauth2" : username, user.getValue());
        assertArrayEquals("fixture-token-not-a-secret".toCharArray(), password.getValue());
        assertFalse(provider.getValue().isInteractive());
    }

    @Test
    void deploymentUsernameWhitespaceIsRemovedButTokenIsPreservedExactly() throws Exception {
        Transport transport = mock(Transport.class);
        String token = "  fixture-token-not-a-secret\r\n";
        ExternalGitCredentials credentials =
                new ExternalGitCredentials("  alice \n", token);

        assertTrue(credentials.isConfigured());
        credentials.configure(transport);

        ArgumentCaptor<CredentialsProvider> provider =
                ArgumentCaptor.forClass(CredentialsProvider.class);
        verify(transport).setCredentialsProvider(provider.capture());
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(provider.getValue().get(
                new URIish("https://example.invalid/repo.git"), user, password));
        assertEquals("alice", user.getValue());
        assertArrayEquals(token.toCharArray(), password.getValue());
    }

    @Test
    void noneNeverReplacesExistingTransportAuthentication() {
        Transport transport = mock(Transport.class);
        assertFalse(ExternalGitCredentials.none().isConfigured());
        ExternalGitCredentials.none().configure(transport);
        verifyNoInteractions(transport);
    }
}
