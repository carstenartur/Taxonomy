package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Request-aware repository state service that provisions a user's workspace at
 * most once per HTTP request.
 *
 * <p>This prevents a controller from successfully provisioning a workspace and
 * then repeating the same persistence operation later in the request. A failed
 * first attempt still propagates and is never cached.</p>
 */
@Service
@Primary
public class RequestCachingRepositoryStateService extends RepositoryStateService {

    private static final String PROVISIONED_ATTRIBUTE_PREFIX =
            RequestCachingRepositoryStateService.class.getName() + ".provisioned.";

    public RequestCachingRepositoryStateService(DslGitRepositoryFactory repositoryFactory,
                                                WorkspaceManager workspaceManager,
                                                SystemRepositoryService systemRepositoryService) {
        super(repositoryFactory, workspaceManager, systemRepositoryService);
    }

    @Override
    public void ensureWorkspaceState(String username) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String attribute = PROVISIONED_ATTRIBUTE_PREFIX + username;
        if (attributes != null && Boolean.TRUE.equals(attributes.getAttribute(
                attribute, RequestAttributes.SCOPE_REQUEST))) {
            return;
        }

        super.ensureWorkspaceState(username);
        if (attributes != null) {
            attributes.setAttribute(
                    attribute, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
