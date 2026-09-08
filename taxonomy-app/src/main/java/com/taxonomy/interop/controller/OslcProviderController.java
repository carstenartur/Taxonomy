package com.taxonomy.interop.controller;

import com.taxonomy.exchange.OslcRdf;
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
@RequestMapping("/oslc/scopes/{scope}")
public class OslcProviderController {
    private final OslcProviderService service;
    private final WorkspaceResolver resolver;
    public OslcProviderController(OslcProviderService service, WorkspaceResolver resolver) { this.service = service; this.resolver = resolver; }
    @GetMapping("/catalog") public ResponseEntity<byte[]> catalog(@PathVariable String scope, HttpServletRequest request) { return response(scope, request, (c, links) -> service.catalog(c, links)); }
    @GetMapping("/projects/{project}/service") public ResponseEntity<byte[]> provider(@PathVariable String scope, @PathVariable long project, HttpServletRequest request) { return response(scope, request, (c, links) -> service.service(c, project, links)); }
    @GetMapping("/projects/{project}/requirements") public ResponseEntity<byte[]> query(@PathVariable String scope, @PathVariable long project,
            @RequestParam(defaultValue="0") int page, @RequestParam(name="oslc.pageSize", defaultValue="50") int size, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.query(c, project, page, size, links));
    }
    @GetMapping("/projects/{project}/requirements/{requirement}") public ResponseEntity<byte[]> requirement(@PathVariable String scope, @PathVariable long project, @PathVariable long requirement, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.requirement(c, project, requirement, null, links));
    }
    @GetMapping("/projects/{project}/requirements/{requirement}/versions/{version}") public ResponseEntity<byte[]> version(@PathVariable String scope, @PathVariable long project, @PathVariable long requirement, @PathVariable long version, HttpServletRequest request) {
        return response(scope, request, (c, links) -> service.requirement(c, project, requirement, version, links));
    }
    @GetMapping("/shapes/requirement") public ResponseEntity<byte[]> shape(@PathVariable String scope, HttpServletRequest request) { return response(scope, request, (c, links) -> service.shape(links)); }
    @GetMapping("/architecture/versions/{commit}") public ResponseEntity<byte[]> architecture(@PathVariable String scope, @PathVariable String commit, HttpServletRequest request) {
        return response(scope, request, (c, links) -> { try { return service.architectureVersion(c, commit, links); } catch (IOException failure) { throw new IntegrationProblem("VERSION_UNAVAILABLE", 503, "Architecture version is temporarily unavailable"); } });
    }
    private interface Resource { OslcRdf read(com.taxonomy.workspace.service.RepositoryContext context, OslcProviderService.Links links); }
    private ResponseEntity<byte[]> response(String scope, HttpServletRequest request, Resource resource) {
        var context = resolver.resolveCurrentRepositoryContext(); service.authorize(context, scope);
        if (request.getHeader("OSLC-Core-Version") != null && !request.getHeader("OSLC-Core-Version").equals("3.0")) throw new IllegalArgumentException("Unsupported OSLC Core version");
        for (String name : request.getParameterMap().keySet()) if (!Set.of("repositoryId", "workspaceId", "branch", "page", "oslc.pageSize", "oslc.paging").contains(name))
            throw new IllegalArgumentException("OSLC query option is outside the declared read-only profile");
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + "/oslc/scopes/" + scope;
        OslcProviderService.Links links = path -> UriComponentsBuilder.fromUriString(base + path)
                .queryParam("repositoryId", context.repositoryId()).queryParam("workspaceId", context.workspaceId()).queryParam("branch", context.branch()).build().encode().toUriString();
        String type = negotiate(request.getHeader(HttpHeaders.ACCEPT)); OslcRdf graph = resource.read(context, links);
        byte[] body = switch (type) { case "text/turtle" -> graph.turtle(); case "application/ld+json" -> graph.jsonLd(); default -> graph.xml(); };
        String etag = "\"" + ReqifExchangeCodec.digest(body) + "\"";
        var response = ResponseEntity.status(etag.equals(request.getHeader(HttpHeaders.IF_NONE_MATCH)) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, type).header("OSLC-Core-Version", "3.0").header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.VARY, "Accept, Authorization, Configuration-Context").header(HttpHeaders.CACHE_CONTROL, "private, no-cache").header("X-Content-Type-Options", "nosniff");
        return response.body(etag.equals(request.getHeader(HttpHeaders.IF_NONE_MATCH)) ? null : body);
    }
    public static String negotiate(String accept) {
        List<MediaType> requested = accept == null ? List.of(MediaType.ALL) : MediaType.parseMediaTypes(accept);
        List<MediaType> ordered = new ArrayList<>(requested); ordered.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
        for (MediaType candidate : ordered) if (candidate.getQualityValue() > 0) for (String supported : List.of("application/rdf+xml", "text/turtle", "application/ld+json"))
            if (candidate.isCompatibleWith(MediaType.parseMediaType(supported))) return supported;
        throw new IntegrationProblem("UNSUPPORTED_REPRESENTATION", 406, "Supported representations are RDF/XML, Turtle and JSON-LD");
    }
    @ExceptionHandler(IntegrationProblem.class) public ResponseEntity<Map<String, String>> problem(IntegrationProblem failure) { return ResponseEntity.status(failure.status()).body(Map.of("code", failure.code(), "message", failure.getMessage())); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("code", "UNSUPPORTED_OSLC_REQUEST", "message", "Request is outside the declared OSLC profile")); }
}
