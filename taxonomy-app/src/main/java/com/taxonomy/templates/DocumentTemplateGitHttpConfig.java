package com.taxonomy.templates;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SecuredSmartHttp;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.hibernate.SessionFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Read-only standard Git transport over the existing canonical template repository.
 * Authentication and CSRF stay with the existing /api/admin/** security boundary.
 * No second server, mirror, credential store or Git protocol implementation is introduced.
 */
@Configuration(proxyBeanMethods = false)
public class DocumentTemplateGitHttpConfig {

    @Bean
    ServletRegistrationBean<GitServlet> documentTemplateGitServlet(
            EntityManagerFactory entityManagerFactory) {
        var factory = new SecuredHibernateRepositoryFactory<String>(
                entityManagerFactory.unwrap(SessionFactory.class),
                DocumentTemplateGitHttpConfig::requireTemplateRead);
        // The library's two-argument factory disables receive-pack and dumb HTTP.
        GitServlet servlet = SecuredSmartHttp.servlet(
                factory, DocumentTemplateGitHttpConfig::requireAdministrator);
        var registration = new ServletRegistrationBean<>(servlet, "/api/admin/git/*");
        registration.setName("documentTemplateGitServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    static String requireAdministrator(HttpServletRequest request)
            throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
        var principal = request.getUserPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ServiceNotAuthorizedException();
        }
        if (!request.isUserInRole("ADMIN")) {
            throw new ServiceMayNotContinueException("Administrator access required", 403);
        }
        return principal.getName();
    }

    static void requireTemplateRead(String principal, RepositoryAccessRequest request) {
        if (principal == null || principal.isBlank()
                || !DocumentTemplateGitRepository.REPOSITORY_NAME.equals(request.repositoryName().value())
                || (request.operation() != RepositoryAccessOperation.DISCOVER
                    && request.operation() != RepositoryAccessOperation.READ)) {
            throw new RepositoryAccessDeniedException(
                    request, "TEMPLATE_GIT_READ_ONLY", "template-git-http", 1);
        }
    }
}
