package com.taxonomy.security.webdav;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A controlled authority for tests of credential formats, persistence and transaction callbacks. */
final class WebDavCredentialTestIdentity {
    private WebDavCredentialTestIdentity() { }

    static String actor(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static WebDavCredentialAuthority fixture() {
        var authority = mock(WebDavCredentialAuthority.class);
        when(authority.owner(any())).thenAnswer(call -> {
            org.springframework.security.core.Authentication login = call.getArgument(0);
            return new WebDavCredentialAuthority.Owner(actor(login.getName()), "test-binding", null);
        });
        when(authority.currentRoles(any(), any())).thenAnswer(call -> call.getArgument(1));
        return authority;
    }
}
