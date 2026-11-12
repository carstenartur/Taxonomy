package com.taxonomy.security.access;

import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;

import static com.taxonomy.security.access.ApiPermission.*;

/** Ordered, deny-by-default REST rules. No separate OpenAPI permission map is maintained. */
public final class ApiAccessPolicy {
    public enum Access { PUBLIC, AUTHENTICATED, PERMISSION, DENIED }

    /** Runtime guards enforce conditions that depend on the request or retained operation. */
    public record ConditionalPermission(String condition, ApiPermission permission) { }

    public record Rule(String method, String pattern, Access access, ApiPermission permission,
                       String resourceRequirement, PathPattern matcher, List<ApiPermission> additionalPermissions,
                       List<ApiPermission> alternativePermissions, List<ConditionalPermission> conditionalPermissions) {
        /** The primary conjunctive alternative; admission must also consider permissionAlternatives(). */
        public List<ApiPermission> permissions() {
            if (access != Access.PERMISSION) return List.of();
            var permissions = new ArrayList<ApiPermission>();
            permissions.add(permission);
            permissions.addAll(additionalPermissions);
            return List.copyOf(permissions);
        }

        /** Every permission within an alternative is required; satisfying any one alternative admits the request. */
        public List<List<ApiPermission>> permissionAlternatives() {
            if (access != Access.PERMISSION) return List.of();
            var alternatives = new ArrayList<List<ApiPermission>>();
            alternatives.add(permissions());
            alternativePermissions.forEach(permission -> alternatives.add(List.of(permission)));
            return List.copyOf(alternatives);
        }

        boolean matches(String method, String path) {
            return (this.method == null || this.method.equals(method))
                    && matcher.matches(PathContainer.parsePath(path));
        }
    }

    private static final String RESOURCE_CHECKS = "Existing project/repository visibility, membership and workspace "
            + "ownership checks also apply where the operation addresses those resources; see the endpoint description.";
    private static final String OWNER = "Repository OWNER permission is additionally required for the exact repository.";
    private static final String CONTRIBUTOR = "Repository CONTRIBUTOR permission and ownership of the personal workspace are additionally required.";
    private static final String OPTIONAL_AUTOMATION = RESOURCE_CHECKS
            + " Configured automatic analysis/enrichment runs only when the caller also has analysis:run "
            + "(USER OR ARCHITECT). Without that capability, the business write succeeds and optional execution is skipped.";
    private static final AuthenticationTrustResolverImpl TRUST = new AuthenticationTrustResolverImpl();
    private static final List<Rule> RULES = createRules();
    private static final Rule DENIED = rule(null, "/**", Access.DENIED, null, "No permission grants this operation.");

    private ApiAccessPolicy() { }

    public static Rule resolve(String method, String path) {
        if (method == null || path == null || !path.startsWith("/")) return DENIED;
        String effectiveMethod = "HEAD".equals(method) ? "GET" : method;
        return RULES.stream().filter(rule -> rule.matches(effectiveMethod, path)).findFirst().orElse(DENIED);
    }

    /** Roles grant capabilities; a bearer token's scopes can only narrow those capabilities. */
    public static boolean authorize(Authentication authentication, Rule rule) {
        if (rule.access == Access.PUBLIC) return true;
        if (rule.access == Access.DENIED || !authenticated(authentication)) return false;
        if (rule.access == Access.AUTHENTICATED) return true;
        return rule.permissionAlternatives().stream().anyMatch(permissions ->
                permissions.stream().allMatch(permission -> hasPermission(authentication, permission)));
    }

    /** Used by capability projections as well as the HTTP request boundary. */
    public static boolean hasPermission(Authentication authentication, ApiPermission permission) {
        if (!authenticated(authentication)) return false;
        var authorities = authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).toList();
        boolean permittedRole = permission.roles().stream().anyMatch(role -> authorities.contains("ROLE_" + role));
        return permittedRole && (!(authentication instanceof JwtAuthenticationToken)
                || authorities.contains("SCOPE_" + permission.scope()));
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !TRUST.isAnonymous(authentication);
    }

    private static List<Rule> createRules() {
        var rules = new ArrayList<Rule>();
        rules.add(rule("OPTIONS", "/api/**", Access.PUBLIC, null, "API preflight; grants no data access."));
        rules.add(rule("POST", "/api/account/change-password", Access.AUTHENTICATED, null, "Own local account only; browser CSRF protection applies."));
        rules.add(rule("GET", "/api/account/me", Access.AUTHENTICATED, null, "Own authentication status only; grants no access to business data."));
        add(rules, "GET", ARCHITECTURE_READ, "/api/admin/status");
        rules.add(rule("POST", "/api/admin/verify", Access.AUTHENTICATED, null, "Verification does not grant administrator rights."));

        add(rules, null, ADMIN_USERS, "/api/admin/users/**", "/api/admin/sessions/**");
        add(rules, null, ADMIN_DIAGNOSTICS, "/api/diagnostics", "/api/workspace/active");
        add(rules, null, ADMIN_CONFIGURATION, "/api/admin/**", "/api/preferences/**", "/api/prompts/**");
        add(rules, "GET", BACKUP_READ, "/api/backups/jobs", "/api/backups/jobs/*", "/api/backups/jobs/*/download");
        add(rules, "POST", BACKUP_CREATE, "/api/backups/jobs", "/api/backups/jobs/*/cancel");
        rules.add(rule(null, "/api/backups/**", Access.DENIED, null, "Unclassified backup operation is denied."));

        for (String method : List.of("GET", "PUT", "DELETE")) {
            rules.add(rule(method, "/api/repositories/*/members/**", Access.PERMISSION, REPOSITORY_MANAGE, OWNER));
        }
        add(rules, "POST", ADMIN_CONFIGURATION, "/api/repositories");
        for (String path : List.of("/api/repositories/*/workspaces", "/api/architecture/editor/workspaces",
                "/api/workspace/provision", "/api/workspace/upstream/fetch", "/api/workspace/upstream/fetch-tag")) {
            rules.add(rule("POST", path, Access.PERMISSION, ANALYSIS_RUN, CONTRIBUTOR));
        }
        for (String path : List.of("/api/workspace/upstream", "/api/workspace/upstream/tag")) {
            rules.add(rule("GET", path, Access.PERMISSION, ARCHITECTURE_READ, CONTRIBUTOR));
        }
        for (String path : List.of("/api/repositories/*/upstream", "/api/repositories/*/upstream/tag")) {
            rules.add(rule("GET", path, Access.PERMISSION, ARCHITECTURE_READ, CONTRIBUTOR));
        }
        for (String path : List.of("/api/repositories/*/upstream/fetch", "/api/repositories/*/upstream/fetch-tag")) {
            rules.add(rule("POST", path, Access.PERMISSION, ARCHITECTURE_WRITE, CONTRIBUTOR));
        }
        add(rules, "POST", ARCHITECTURE_WRITE, "/api/repositories/*/forks");
        add(rules, "POST", ADMIN_CONFIGURATION, "/api/workspace/external/fetch");
        for (String path : List.of("/api/workspace/external/push", "/api/workspace/external/full-sync")) {
            rules.add(new Rule("POST", path, Access.PERMISSION, ARCHITECTURE_PUBLISH, RESOURCE_CHECKS,
                    PathPatternParser.defaultInstance.parse(path), List.of(ADMIN_CONFIGURATION), List.of(), List.of()));
        }
        add(rules, "PUT", ADMIN_CONFIGURATION, "/api/workspace/external/configure");
        add(rules, "POST", ARCHITECTURE_PUBLISH, "/api/workspace/sync-from-shared", "/api/workspace/publish", "/api/workspace/resolve-diverged");
        add(rules, "POST", ADMIN_CONFIGURATION, "/api/workspace/evict");
        add(rules, "POST", ANALYSIS_RUN, "/api/workspace/create", "/api/workspace/*/archive");
        add(rules, "PUT", ANALYSIS_RUN, "/api/workspace/*/rename", "/api/workspace/*/description");
        add(rules, "POST", ARCHITECTURE_READ, "/api/workspace/compare", "/api/workspace/*/switch", "/api/workspace/history/return-to");
        add(rules, "DELETE", ANALYSIS_RUN, "/api/workspace/history");
        add(rules, "DELETE", ARCHITECTURE_DELETE, "/api/workspace/*");

        add(rules, "GET", INTEGRATION_EXPORT, "/api/integrations/**", "/oslc/**");
        add(rules, "POST", INTEGRATION_EXPORT, "/api/integrations/*/export-previews", "/api/integrations/*/files");
        add(rules, "POST", ARCHITECTURE_PUBLISH, "/api/integrations/*/publish");
        String retry = "/api/integrations/*/operations/*/retry";
        rules.add(new Rule("POST", retry, Access.PERMISSION, INTEGRATION_IMPORT,
                RESOURCE_CHECKS + " Retry permission is selected from the persisted operation direction: "
                        + "INBOUND requires integration:import, OUTBOUND requires integration:export, "
                        + "PUSH or SYNCHRONIZE requires architecture:publish. Request parameters cannot change "
                        + "the retained operation kind; publication retains its exact-branch checks.",
                PathPatternParser.defaultInstance.parse(retry), List.of(),
                List.of(INTEGRATION_EXPORT, ARCHITECTURE_PUBLISH), List.of(
                        new ConditionalPermission("persistedOperation.direction=INBOUND", INTEGRATION_IMPORT),
                        new ConditionalPermission("persistedOperation.direction=OUTBOUND", INTEGRATION_EXPORT),
                        new ConditionalPermission("persistedOperation.direction=PUSH or SYNCHRONIZE", ARCHITECTURE_PUBLISH))));
        add(rules, "POST", INTEGRATION_IMPORT, "/api/integrations", "/api/integrations/**");
        rules.add(rule("OPTIONS", "/oslc/**", Access.AUTHENTICATED, null, RESOURCE_CHECKS));
        rules.add(rule(null, "/api/integrations/**", Access.DENIED, null, "Unclassified integration operation is denied."));
        rules.add(rule(null, "/oslc/**", Access.DENIED, null, "OSLC is read-only."));

        add(rules, "POST", INTEGRATION_EXPORT, "/api/scores/export", "/api/architecture/editor.admbw.qea");
        add(rules, "POST", ARCHITECTURE_READ, "/api/dsl/parse", "/api/dsl/validate", "/api/dsl/format", "/api/dsl/project/compare-current", "/api/graph/**", "/api/scores/**");
        for (String path : List.of("/api/diagram/visio", "/api/diagram/archimate", "/api/diagram/mermaid",
                "/api/diagram/structurizr", "/api/diagram/export/*")) {
            rules.add(new Rule("POST", path, Access.PERMISSION, ANALYSIS_RUN, RESOURCE_CHECKS,
                    PathPatternParser.defaultInstance.parse(path), List.of(INTEGRATION_EXPORT), List.of(), List.of()));
        }
        add(rules, "POST", INTEGRATION_EXPORT, "/api/diagram/**", "/api/export/**", "/api/report/**", "/api/decision-report/**");
        add(rules, "GET", INTEGRATION_EXPORT, "/api/export/**", "/api/report/**", "/api/decision-report/**", "/api/diagram/**");
        // Capability metadata and saved-snapshot options precede the dynamic report format route.
        add(rules, "GET", ARCHITECTURE_READ, "/api/projects/*/snapshots/*/decision-report/formats");
        add(rules, "GET", ANALYSIS_READ, "/api/projects/*/snapshots/*/decision-report/options");
        // Downloads must precede both saved-analysis reads and the generic architecture read fallback.
        add(rules, "GET", INTEGRATION_EXPORT, "/api/architecture/editor.svg", "/api/architecture/editor.pdf",
                "/api/architecture/editor.admbw.json",
                "/api/dsl/export", "/api/dsl/project/catalogue-source", "/api/projects/git/export", "/api/projects/*/reports/*",
                "/api/projects/*/snapshots/*/architecture-report/docx",
                "/api/projects/*/snapshots/*/decision-report/*", "/api/projects/*/architecture-workbench/*.svg",
                "/api/projects/*/architecture-workbench/*.pdf", "/api/projects/*/architecture-workbench/*.archimate.xml",
                "/api/projects/*/architecture-workbench/*.archimate.zip", "/api/projects/*/architecture-workbench/*.vsdx",
                "/api/projects/*/architecture-workbench/*.visio.zip",
                "/api/projects/*/requirements/*/reformulations/*/revisions/*/export",
                "/api/projects/*/requirements/*/reformulations/*/adoptions/*/export");
        // These complete operations accept model execution, including focus requests with useAi.
        add(rules, "POST", ANALYSIS_RUN, "/api/architecture/focus/suggestions", "/api/architecture/editor/focus/suggestions",
                "/api/documents/extract-ai", "/api/documents/map-regulation");
        // These GET handlers start new LLM work; HTTP method alone does not make them read-only.
        add(rules, "GET", ANALYSIS_RUN, "/api/analyze-stream", "/api/analyze-node");
        add(rules, "POST", ANALYSIS_RUN, "/api/analyze", "/api/justify-leaf", "/api/recommend", "/api/gap/**", "/api/patterns/**", "/api/explain/**",
                "/api/analysis-runs/*/cancel", "/api/analysis-continuations/*/cancel", "/api/architecture/lifecycle/intake-review",
                "/api/import/preview/**", "/api/projects/*/analyses", "/api/projects/*/requirements/*/analyses",
                "/api/projects/*/analysis-jobs/*/retry-failed", "/api/projects/*/analysis-jobs/*/items/*/continue",
                "/api/projects/*/copilot-operations/*/cancel");
        for (String path : List.of("/api/projects/*/requirements/*/copilot", "/api/projects/*/autopilot/run",
                "/api/projects/*/copilot-operations/*/resume")) {
            rules.add(new Rule("POST", path, Access.PERMISSION, ARCHITECTURE_WRITE, RESOURCE_CHECKS,
                    PathPatternParser.defaultInstance.parse(path), List.of(ANALYSIS_RUN), List.of(), List.of()));
        }
        add(rules, "POST", ANALYSIS_RUN, "/api/analysis-adapters/*/cancel");
        add(rules, "GET", ANALYSIS_READ, "/api/analysis-adapters/*", "/api/analysis-drafts/**", "/api/analysis-runs/**", "/api/analysis-continuations/**", "/api/analysis-progress/**");
        add(rules, "GET", ANALYSIS_READ, "/api/projects/*/analysis-jobs/**", "/api/projects/*/requirements/*/snapshots",
                "/api/projects/*/snapshots/**", "/api/projects/*/architecture-workbench/*",
                "/api/projects/*/requirements/*/copilot/latest", "/api/projects/*/copilot-operations/*");
        for (String method : List.of("POST", "PUT", "DELETE")) add(rules, method, ANALYSIS_RUN, "/api/analysis-drafts/**");
        // An unadopted reformulation is an analysis draft; adoption remains a business edit below.
        String proposals = "/api/projects/*/requirements/*/reformulations";
        add(rules, "GET", ANALYSIS_READ, proposals, proposals + "/*", proposals + "/*/revisions/*",
                proposals + "/*/synthesis-runs", proposals + "/*/synthesis-runs/**");
        add(rules, "POST", ANALYSIS_RUN, proposals, proposals + "/*/revisions", proposals + "/*/answers",
                proposals + "/*/statements/*", proposals + "/*/variants", proposals + "/*/synthesis-runs",
                proposals + "/*/synthesis-runs/*/cancel");
        add(rules, "POST", INTEGRATION_IMPORT, "/api/import/**");

        for (String path : List.of("/api/projects/*/requirements/import", "/api/projects/*/requirements/import-review")) {
            rules.add(new Rule("POST", path, Access.PERMISSION, ARCHITECTURE_WRITE,
                    path.endsWith("/import") ? OPTIONAL_AUTOMATION : RESOURCE_CHECKS,
                    PathPatternParser.defaultInstance.parse(path), List.of(), List.of(),
                    List.of(new ConditionalPermission("analyzeAfterImport=true", ANALYSIS_RUN))));
        }
        for (String path : List.of("/api/projects/*/requirements", "/api/projects/*/requirements/*/versions")) {
            rules.add(rule("POST", path, Access.PERMISSION, ARCHITECTURE_WRITE, OPTIONAL_AUTOMATION));
        }

        for (String path : List.of("/api/relations/**", "/api/proposals/**", "/api/architecture/relations/**",
                "/api/architecture/proposals/**", "/api/dsl/**", "/api/git/**", "/api/provenance/**", "/api/projects/**",
                "/api/solutions/**", "/api/products/**")) {
            add(rules, "POST", ARCHITECTURE_WRITE, path);
            add(rules, "PUT", ARCHITECTURE_WRITE, path);
            add(rules, "DELETE", ARCHITECTURE_DELETE, path);
        }
        add(rules, "PATCH", ARCHITECTURE_WRITE, "/api/projects/**", "/api/solutions/**", "/api/products/**");
        add(rules, "GET", ARCHITECTURE_READ, "/api/inventory-comparisons/**");
        add(rules, "POST", ARCHITECTURE_WRITE, "/api/architecture/editor/**", "/api/architecture/lifecycle/**", "/api/context/**",
                "/api/documents/**", "/api/coverage/**", "/api/architecture/metadata/recompute", "/api/product-demo/**", "/api/inventory-comparisons/**");
        add(rules, "DELETE", ARCHITECTURE_DELETE, "/api/coverage/**");
        rules.add(rule(null, "/api/inventory-comparisons/**", Access.DENIED, null, "Only explicitly classified comparison operations are available."));
        // Read fallback follows all privileged read namespaces; writes have no such fallback.
        add(rules, "GET", ARCHITECTURE_READ, "/api/**");
        return List.copyOf(rules);
    }

    private static void add(List<Rule> rules, String method, ApiPermission permission, String... paths) {
        for (String path : paths) rules.add(rule(method, path, Access.PERMISSION, permission, RESOURCE_CHECKS));
    }

    private static Rule rule(String method, String path, Access access, ApiPermission permission, String resource) {
        return new Rule(method, path, access, permission, resource, PathPatternParser.defaultInstance.parse(path),
                List.of(), List.of(), List.of());
    }
}
