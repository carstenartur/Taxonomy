package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Supported central reads remain available without provisioning a workspace. */
class CentralPortfolioReadInterceptorTest {
    private static final RepositoryContext CENTRAL =
            RepositoryContext.centralRead("repository-a", "draft", "admin");
    private static final WorkspaceContext CENTRAL_VIEW =
            new WorkspaceContext("admin", null, "draft", "repository-a");

    @Test
    void explicitCentralSelectionReadsHistoricalPortfolioAndSnapshots() {
        for (String path : List.of("/api/projects", "/api/projects/1",
                "/api/projects/1/requirements/2/versions",
                "/api/projects/1/snapshots/3", "/api/projects/1/reports/docx",
                "/api/projects/1/architecture-workbench/snapshot-old",
                "/api/projects/1/architecture-workbench/snapshot-old.svg",
                "/api/projects/1/architecture-workbench/snapshot-old.pdf",
                "/api/solutions", "/api/solutions/4", "/api/products/5")) {
            var fixture = fixture(CENTRAL, CENTRAL_VIEW);
            assertTrue(fixture.interceptor.preHandle(request("GET", path, "", ""), null, null), path);
            assertEquals(0, fixture.state.provisions, "Central reads must not create workspace state");
        }
    }

    @Test
    void headReadAlsoWorksBehindContextPath() {
        var fixture = fixture(CENTRAL, CENTRAL_VIEW);
        assertTrue(fixture.interceptor.preHandle(
                request("HEAD", "/taxonomy/api/projects/1", "/taxonomy", ""), null, null));
        assertEquals(0, fixture.state.provisions);
    }

    @Test
    void explicitCentralGraphReadsDoNotProvisionWorkspace() {
        for (String method : List.of("GET", "HEAD")) {
            for (String context : List.of("", "/taxonomy")) {
                var fixture = fixture(CENTRAL, CENTRAL_VIEW);
                assertTrue(fixture.interceptor.preHandle(request(method,
                        context + "/api/search/graph", context, ""), null, null));
                assertEquals(0, fixture.state.provisions);
            }
        }
    }

    @Test
    void centralWritesAndAnalysisStillRequireAnIsolatedWorkspace() {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (String path : List.of("/api/projects/1/analyses", "/api/search/graph")) {
                var fixture = fixture(CENTRAL, CENTRAL_VIEW);
                var failure = assertThrows(ResponseStatusException.class,
                        () -> fixture.interceptor.preHandle(request(method, path, "", ""), null, null));
                assertEquals(403, failure.getStatusCode().value());
            }
        }
    }

    @Test
    void unrelatedReadsAndGitOperationsKeepTheirIsolationContract() {
        for (String path : List.of("/api/analyze", "/api/search/graph/", "/api/search/graph/other",
                "/api/search/graphical", "/api/dsl/current",
                "/api/projects/git/export", "/api/projects-unrelated", "/api/products-other")) {
            var fixture = fixture(CENTRAL, CENTRAL_VIEW);
            var failure = assertThrows(ResponseStatusException.class,
                    () -> fixture.interceptor.preHandle(request("GET", path, "", ""), null, null), path);
            assertEquals(403, failure.getStatusCode().value());
        }
    }

    @Test
    void statusReadsThatCanScheduleOrFinalizeCopilotRemainWorkspaceOnly() {
        for (String method : List.of("GET", "HEAD")) {
            for (String path : List.of("/api/projects/1/requirements/2/copilot/latest",
                    "/api/projects/1/copilot-operations/operation-3",
                    "/api/projects/1/future-operation")) {
                var fixture = fixture(CENTRAL, CENTRAL_VIEW);
                var failure = assertThrows(ResponseStatusException.class,
                        () -> fixture.interceptor.preHandle(request(method, path, "", ""), null, null), path);
                assertEquals(403, failure.getStatusCode().value());
            }
        }
    }

    @Test
    void centralReadRejectsMismatchedRepositoryBranchOrActor() {
        for (WorkspaceContext view : List.of(
                new WorkspaceContext("admin", null, "draft", "repository-b"),
                new WorkspaceContext("admin", null, "other-branch", "repository-a"),
                new WorkspaceContext("someone-else", null, "draft", "repository-a"),
                new WorkspaceContext("admin", "workspace-a", "draft", "repository-a"))) {
            for (String path : List.of("/api/projects/1", "/api/search/graph")) {
                var fixture = fixture(CENTRAL, view);
                assertThrows(IllegalStateException.class, () -> fixture.interceptor.preHandle(
                        request("GET", path, "", ""), null, null));
                assertEquals(0, fixture.state.provisions);
            }
        }
    }

    @Test
    void centralReadRejectsAnActorDifferentFromTheAuthenticatedUser() {
        var repository = RepositoryContext.centralRead("repository-a", "draft", "someone-else");
        var view = new WorkspaceContext("someone-else", null, "draft", "repository-a");
        for (String path : List.of("/api/projects/1", "/api/search/graph")) {
            var fixture = fixture(repository, view);
            assertThrows(IllegalStateException.class, () -> fixture.interceptor.preHandle(
                    request("GET", path, "", ""), null, null));
            assertEquals(0, fixture.state.provisions);
        }
    }

    @Test
    void explicitCentralSelectionRequiresCentralReadScope() {
        for (RepositoryContext repository : List.of(
                RepositoryContext.workspace("repository-a", "workspace-a", "draft", "admin"),
                RepositoryContext.centralWrite("repository-a", "draft", "admin"),
                new RepositoryContext("repository-a", null, "draft", "admin", RepositoryScope.FORK))) {
            for (String path : List.of("/api/projects/1", "/api/search/graph")) {
                var fixture = fixture(repository, CENTRAL_VIEW);
                assertThrows(IllegalStateException.class, () -> fixture.interceptor.preHandle(
                        request("GET", path, "", ""), null, null));
                assertEquals(0, fixture.state.provisions);
            }
        }
    }

    @Test
    void missingPinDoesNotChangeTheExistingWorkspaceResolutionPath() {
        var repository = RepositoryContext.workspace("repository-a", "workspace-a", "draft", "admin");
        for (String path : List.of("/api/projects/1", "/api/search/graph")) {
            var fixture = fixture(repository,
                    new WorkspaceContext("admin", "workspace-a", "draft", "repository-a"));
            assertTrue(fixture.interceptor.preHandle(request("GET", path, "", null), null, null));
            assertEquals(1, fixture.state.provisions);
        }
        var central = fixture(CENTRAL, CENTRAL_VIEW);
        assertThrows(IllegalStateException.class, () -> central.interceptor.preHandle(
                request("GET", "/api/search/graph", "", null), null, null));
        assertEquals(1, central.state.provisions);
    }

    private static Fixture fixture(RepositoryContext repository, WorkspaceContext view) {
        var resolver = new WorkspaceResolver(null) {
            @Override public String resolveCurrentUsername() { return "admin"; }
            @Override public RepositoryContext resolveCurrentRepositoryContext() { return repository; }
            @Override public WorkspaceContext resolveCurrentContext() { return view; }
        };
        var state = new State();
        return new Fixture(new DslWorkspacePreResolutionInterceptor(resolver, state), state);
    }

    private static HttpServletRequest request(String method, String uri, String context, String pin) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "getMethod" -> method;
                    case "getRequestURI" -> uri;
                    case "getContextPath" -> context;
                    case "getHeader" -> WorkspaceContextResolver.WORKSPACE_HEADER.equals(args[0]) ? pin : null;
                    case "getParameter" -> null;
                    case "toString" -> method + " " + uri;
                    default -> throw new UnsupportedOperationException(called.getName());
                });
    }

    private static final class State extends RepositoryStateService {
        int provisions;
        State() { super(null, null, null); }
        @Override public void ensureWorkspaceState(String username) { provisions++; }
    }

    private record Fixture(DslWorkspacePreResolutionInterceptor interceptor, State state) { }
}
