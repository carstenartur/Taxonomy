package com.taxonomy.workspace.service;

import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Write-only deployment credentials scoped to configured external Git repositories.
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
    private final Environment environment;

    public ExternalGitCredentials(String username, String token) {
        this(username, token, null);
    }

    @Autowired
    public ExternalGitCredentials(
            @Value("${TAXONOMY_EXTERNAL_GIT_USERNAME:oauth2}") String username,
            @Value("${TAXONOMY_EXTERNAL_GIT_TOKEN:}") String token,
            Environment environment) {
        String normalizedUsername = username == null ? "" : username.strip();
        this.username = normalizedUsername.isEmpty() ? "oauth2" : normalizedUsername;
        this.token = token == null ? "" : token;
        this.environment = environment;
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

    /** Global legacy credentials are available only to the primary repository. */
    public void configure(Transport transport, String repositoryId, boolean primary) {
        scoped(repositoryId, primary).configure(transport);
    }

    public boolean isConfigured(String repositoryId, boolean primary) {
        return scoped(repositoryId, primary).isConfigured();
    }

    private ExternalGitCredentials scoped(String repositoryId, boolean primary) {
        if (environment != null && repositoryId != null) {
            String prefix = "taxonomy.dsl.git.remotes." + repositoryId + ".";
            String scopedToken = environment.getProperty(prefix + "token");
            String scopedUsername = environment.getProperty(prefix + "username");
            if (scopedToken != null || scopedUsername != null) {
                return new ExternalGitCredentials(scopedUsername, scopedToken);
            }
        }
        return primary ? this : none();
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }
}
