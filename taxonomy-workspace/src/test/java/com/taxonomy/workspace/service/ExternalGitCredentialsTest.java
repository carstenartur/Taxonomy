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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalGitCredentialsTest {
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
    void usernameIsNormalizedButConfiguredSecretIsPreservedExactly() throws Exception {
        Transport transport = mock(Transport.class);
        String configuredSecret = "  fixture-token-not-a-secret\r\n";
        ExternalGitCredentials credentials =
                new ExternalGitCredentials("  alice \n", configuredSecret);

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
        assertArrayEquals(configuredSecret.toCharArray(), password.getValue());
    }

    @Test
    void noneNeverReplacesExistingTransportAuthentication() {
        Transport transport = mock(Transport.class);
        assertFalse(ExternalGitCredentials.none().isConfigured());
        ExternalGitCredentials.none().configure(transport);
        verifyNoInteractions(transport);
    }
}
