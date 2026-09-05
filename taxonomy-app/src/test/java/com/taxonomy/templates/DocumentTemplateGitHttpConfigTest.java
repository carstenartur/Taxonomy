package com.taxonomy.templates;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTemplateGitHttpConfigTest {
    @Test
    void requiresAnAuthenticatedAdministrator() throws Exception {
        var request = new MockHttpServletRequest();
        assertThrows(ServiceNotAuthorizedException.class,
                () -> DocumentTemplateGitHttpConfig.requireAdministrator(request));
        request.setUserPrincipal(() -> "git-reader");
        assertEquals(403, assertThrows(ServiceMayNotContinueException.class,
                () -> DocumentTemplateGitHttpConfig.requireAdministrator(request)).getStatusCode());
        request.addUserRole("ADMIN");
        assertEquals("git-reader", DocumentTemplateGitHttpConfig.requireAdministrator(request));
    }

    @Test
    void onlyDiscoverAndReadAreAllowedEvenForTheTemplateRepository() {
        var name = new RepositoryName(DocumentTemplateGitRepository.REPOSITORY_NAME);
        for (var operation : RepositoryAccessOperation.values()) {
            var request = operation.refScoped()
                    ? RepositoryAccessRequest.ref(name, operation, "refs/heads/main", null, null)
                    : RepositoryAccessRequest.repository(name, operation);
            if (operation == RepositoryAccessOperation.READ || operation == RepositoryAccessOperation.DISCOVER) {
                assertDoesNotThrow(() -> DocumentTemplateGitHttpConfig.requireTemplateRead("admin", request));
            } else {
                assertThrows(RepositoryAccessDeniedException.class,
                        () -> DocumentTemplateGitHttpConfig.requireTemplateRead("admin", request));
            }
        }
    }

    @Test
    void otherRepositoriesAndEmptyPrincipalsAreNeverGranted() {
        var other = RepositoryAccessRequest.repository(new RepositoryName("other-private-repository"),
                RepositoryAccessOperation.READ);
        assertThrows(RepositoryAccessDeniedException.class,
                () -> DocumentTemplateGitHttpConfig.requireTemplateRead("admin", other));
        var template = RepositoryAccessRequest.repository(
                new RepositoryName(DocumentTemplateGitRepository.REPOSITORY_NAME), RepositoryAccessOperation.READ);
        assertThrows(RepositoryAccessDeniedException.class,
                () -> DocumentTemplateGitHttpConfig.requireTemplateRead(null, template));
        assertThrows(RepositoryAccessDeniedException.class,
                () -> DocumentTemplateGitHttpConfig.requireTemplateRead(" ", template));
    }
}
