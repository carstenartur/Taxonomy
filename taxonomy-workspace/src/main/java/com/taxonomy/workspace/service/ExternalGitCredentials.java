package com.taxonomy.workspace.service;

import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Write-only deployment credential source for the single configured external
 * canonical Git repository.
 *
 * <p>The token is read from the process environment/property sources and is
 * never stored in JPA entities, returned by APIs, or written to logs. Usernames
 * are normalized for deployment convenience; any nonblank token is preserved
 * exactly as configured before it is passed to JGit.</p>
 */
@Component
public class ExternalGitCredentials {

    private final String username;
    private final String token;

    public ExternalGitCredentials(
            @Value("${TAXONOMY_EXTERNAL_GIT_USERNAME:oauth2}") String username,
            @Value("${TAXONOMY_EXTERNAL_GIT_TOKEN:}") String token) {
        String normalizedUsername = username == null ? "" : username.strip();
        this.username = normalizedUsername.isEmpty() ? "oauth2" : normalizedUsername;
        this.token = token == null ? "" : token;
    }

    static ExternalGitCredentials none() {
        return new ExternalGitCredentials("oauth2", "");
    }

    /** Configure JGit transport only when a deployment secret is present. */
    public void configure(Transport transport) {
        if (isConfigured()) {
            transport.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(username, token));
        }
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }
}
