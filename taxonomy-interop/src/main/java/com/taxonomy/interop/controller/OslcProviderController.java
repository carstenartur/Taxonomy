package com.taxonomy.interop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.oslc.OslcProviderService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.IOException;
import java.util.*;

@RestController
@Tag(name = "OSLC provider")
@RequestMapping("/oslc/scopes/{scope}")
public class OslcProviderController {
    private final OslcProviderService service;
    private final WorkspaceResolver resolver;
    public OslcProviderController(OslcProviderService service, WorkspaceResolver resolver) { this.service = service; this.resolver = resolver; }
    @GetMapping("/catalog") @Operation(summary = "Read the scoped OSLC service catalog",
            description = "Returns the read-only OSLC service catalog for the authenticated repository context. Supports RDF/XML, Turtle and JSON-LD through Accept negotiation, with representation-specific ETags.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> catalog(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, HttpServletRequest request) { return response(scope, request, (c, links) -> service.catalog(c, links)); }
    @GetMapping("/projects/{project}/service") @Operation(summary = "Read a project OSLC service provider",
            description = "Returns service and query links for the authorized project in the selected OSLC scope. The service describes the supported read-only profile; it does not create or update requirements.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> provider(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, @Parameter(description = "Project identifier in the selected OSLC scope") @PathVariable long project, HttpServletRequest request) { return response(scope, request, (c, links) -> service.service(c, project, links)); }
    @GetMapping("/projects/{project}/requirements") @Operation(summary = "Query project requirements through OSLC",
            description = "Returns a bounded requirement page in the selected scope. page is zero-based and oslc.pageSize controls the page size. Only the declared paging and repository-context options are accepted; unsupported OSLC query options return 400.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> query(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, @Parameter(description = "Project identifier in the selected OSLC scope") @PathVariable long project,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue="0") int page, @Parameter(description = "Requested OSLC page size (query parameter oslc.pageSize)") @RequestParam(name="oslc.pageSize", defaultValue="50") int size, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.query(c, project, page, size, links));
    }
    @GetMapping("/projects/{project}/requirements/{requirement}") @Operation(summary = "Read the current OSLC requirement resource",
            description = "Returns the current requirement representation in the authorized project and scope. Use the versions endpoint for immutable historical text; conditional headers can return 304 or 412.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> requirement(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, @Parameter(description = "Project identifier in the selected OSLC scope") @PathVariable long project, @Parameter(description = "Requirement identifier in that project") @PathVariable long requirement, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.requirement(c, project, requirement, null, links));
    }
    @GetMapping("/projects/{project}/requirements/{requirement}/versions/{version}") @Operation(summary = "Read an immutable OSLC requirement version",
            description = "Returns the selected retained requirement version, not the current text, after project and scope authorization. Content negotiation and conditional representation ETags apply.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> version(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, @Parameter(description = "Project identifier in the selected OSLC scope") @PathVariable long project, @Parameter(description = "Requirement identifier in that project") @PathVariable long requirement, @Parameter(description = "Immutable requirement version identifier") @PathVariable long version, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.requirement(c, project, requirement, version, links));
    }
    @GetMapping("/shapes/requirement") @Operation(summary = "Read the OSLC requirement resource shape",
            description = "Describes the supported requirement RDF properties for this read-only provider profile. The authenticated scope is checked even though the shape is shared.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> shape(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, HttpServletRequest request) { return response(scope, request, (c, links) -> service.shape(links)); }
    @GetMapping("/configurations/current") @Operation(summary = "Read the current OSLC configuration context",
            description = "Returns the configuration resource for the selected authorized repository/workspace/branch. Supplied Configuration-Context must match this exact configuration or the request returns 404.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> configuration(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.configuration(c, links));
    }
    @GetMapping("/architecture/versions/{commit}") @Operation(summary = "Read an OSLC architecture checkpoint",
            description = "Returns RDF for the exact architecture Git commit within the authorized scope. Does not substitute a different branch or current version. Temporarily unavailable version storage returns 503.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> architecture(@Parameter(description = "Authorized OSLC scope identifier; routing data alone never grants access") @PathVariable String scope, @Parameter(description = "Exact retained architecture Git commit; required when no semantic revision is supplied") @PathVariable String commit, HttpServletRequest request) {
        return response(scope, request, (c, links) -> { try { return service.architectureVersion(c, commit, links); } catch (IOException failure) { throw new IntegrationProblem("VERSION_UNAVAILABLE", 503, "Architecture version is temporarily unavailable"); } });
    }
    private interface Resource { OslcRdf read(com.taxonomy.workspace.service.RepositoryContext context, OslcProviderService.Links links); }
    private ResponseEntity<byte[]> response(String scope, HttpServletRequest request, Resource resource) {
        var context = resolver.resolveCurrentRepositoryContext(); service.authorize(context, scope);
        if (request.getHeader("OSLC-Core-Version") != null && !request.getHeader("OSLC-Core-Version").equals("3.0")) throw new IllegalArgumentException("Unsupported OSLC Core version");
        // Spring Security's form token is transport metadata, never an OSLC query or resource field.
        for (String name : request.getParameterMap().keySet()) if (!Set.of("repositoryId", "workspaceId", "branch", "page", "oslc.pageSize", "oslc.paging", "_csrf").contains(name))
            throw new IllegalArgumentException("OSLC query option is outside the declared read-only profile");
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + "/oslc/scopes/" + scope;
        OslcProviderService.Links links = path -> UriComponentsBuilder.fromUriString(base + path)
                .queryParam("repositoryId", context.repositoryId()).queryParam("workspaceId", context.workspaceId()).queryParam("branch", context.branch()).build().encode().toUriString();
        String configuration = links.uri("/configurations/current");
        if (request.getHeader("Configuration-Context") != null && !configuration.equals(request.getHeader("Configuration-Context")))
            throw new IntegrationProblem("CONFIGURATION_UNAVAILABLE", 404, "Requested configuration is not available in this scope");
        String type = negotiate(request.getHeader(HttpHeaders.ACCEPT)); OslcRdf graph = resource.read(context, links);
        byte[] body = switch (type) { case "text/turtle" -> graph.turtle(); case "application/ld+json" -> graph.jsonLd(); default -> graph.xml(); };
        String etag = "\"" + ReqifExchangeCodec.digest(body) + "\"";
        if (request.getHeader(HttpHeaders.IF_MATCH) != null && !matches(request.getHeader(HttpHeaders.IF_MATCH), etag, false))
            throw new IntegrationProblem("RESOURCE_VERSION_CHANGED", 412, "Resource no longer has the expected version");
        boolean unchanged = matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag, true);
        var response = ResponseEntity.status(unchanged ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, type).header("OSLC-Core-Version", "3.0").header(HttpHeaders.ETAG, etag)
                .header("Configuration-Context", configuration)
                .header(HttpHeaders.VARY, "Accept, Authorization, Configuration-Context").header(HttpHeaders.CACHE_CONTROL, "private, no-cache").header("X-Content-Type-Options", "nosniff");
        return response.body(unchanged ? null : body);
    }
    static boolean matches(String condition, String etag, boolean weak) {
        if (condition == null) return false;
        for (String token : condition.split(",")) {
            String value = token.strip();
            if (weak && value.startsWith("W/")) value = value.substring(2);
            if (value.equals("*") || value.equals(etag)) return true;
        }
        return false;
    }
    public static String negotiate(String accept) {
        List<MediaType> requested = accept == null ? List.of(MediaType.ALL) : MediaType.parseMediaTypes(accept);
        String best = null; double quality = 0;
        for (String supported : List.of("application/rdf+xml", "text/turtle", "application/ld+json")) {
            MediaType representation = MediaType.parseMediaType(supported), match = null;
            int specificity = -1;
            for (MediaType candidate : requested) if (candidate.isCompatibleWith(representation)) {
                int rank = candidate.isWildcardType() ? 0 : candidate.isWildcardSubtype() ? 1 : 2;
                if (rank > specificity || rank == specificity && match != null && candidate.getQualityValue() > match.getQualityValue()) {
                    specificity = rank; match = candidate;
                }
            }
            if (match != null && match.getQualityValue() > quality) { quality = match.getQualityValue(); best = supported; }
        }
        if (best != null) return best;
        throw new IntegrationProblem("UNSUPPORTED_REPRESENTATION", 406, "Supported representations are RDF/XML, Turtle and JSON-LD");
    }
    // Failures use bounded JSON regardless of the requested RDF representation.
    @ExceptionHandler(IntegrationProblem.class) public ResponseEntity<Map<String, String>> problem(IntegrationProblem failure) { return ResponseEntity.status(failure.status()).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", failure.code(), "message", failure.getMessage())); }
    @ExceptionHandler(ExchangeFormatException.class) public ResponseEntity<Map<String, String>> format(ExchangeFormatException failure) {
        return ResponseEntity.unprocessableContent().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", failure.code(), "message", failure.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "UNSUPPORTED_OSLC_REQUEST", "message", "Request is outside the declared OSLC profile")); }
}
