package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.LockConflictException;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.TemplateLock;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Virtual WebDAV collection exposing only real DOTX resources.
 *
 * <p>The unzipped package files remain available through Git and the Taxonomy
 * inspection API, but are intentionally not projected as WebDAV children.</p>
 */
public final class DocumentTemplateWebDavServlet extends HttpServlet {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentTemplateWebDavServlet.class);

    private static final int SC_MULTI_STATUS = 207;
    private static final int SC_LOCKED = 423;
    private static final int SC_PRECONDITION_REQUIRED = 428;
    private static final Pattern LOCK_TOKEN =
            Pattern.compile("opaquelocktoken:[^>\\s)]+", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final DocumentTemplateService templates;
    private final DocumentTemplateWebDavLockManager locks;

    public DocumentTemplateWebDavServlet(
            DocumentTemplateService templates,
            DocumentTemplateWebDavLockManager locks) {
        this.templates = templates;
        this.locks = locks;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        addDavHeaders(response);
        try {
            switch (request.getMethod().toUpperCase(Locale.ROOT)) {
                case "OPTIONS" -> options(response);
                case "PROPFIND" -> propfind(request, response);
                case "GET" -> get(request, response, true);
                case "HEAD" -> get(request, response, false);
                case "PUT" -> put(request, response);
                case "LOCK" -> lock(request, response);
                case "UNLOCK" -> unlock(request, response);
                default -> {
                    response.setHeader("Allow",
                            "OPTIONS, PROPFIND, GET, HEAD, PUT, LOCK, UNLOCK");
                    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            }
        } catch (TemplateNotFoundException exception) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, exception.getMessage());
        } catch (TemplateConflictException exception) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED,
                    exception.getMessage());
        } catch (LockConflictException exception) {
            response.sendError(SC_LOCKED, exception.getMessage());
        } catch (SecurityException exception) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
        } catch (IOException exception) {
            log.warn("WebDAV template operation failed: {}", exception.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Template operation failed");
            }
        }
    }

    private void options(HttpServletResponse response) {
        response.setHeader("Allow", "OPTIONS, PROPFIND, GET, HEAD, PUT, LOCK, UNLOCK");
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void propfind(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Resource resource = resource(request);
        String depth = request.getHeader("Depth");
        response.setStatus(SC_MULTI_STATUS);
        response.setContentType("application/xml; charset=UTF-8");

        StringBuilder xml = new StringBuilder(4_096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<D:multistatus xmlns:D=\"DAV:\">");

        if (resource.collection()) {
            appendCollectionResponse(xml, collectionHref(request));
            if (!"0".equals(depth)) {
                for (TemplateDescriptor template : templates.list()) {
                    appendTemplateResponse(
                            xml,
                            collectionHref(request) + encode(template.fileName()),
                            template.displayName(),
                            template.headCommit(),
                            template.updatedAt(),
                            template.uncompressedSize(),
                            locks.find(template.templateId()));
                }
            }
        } else {
            TemplateFile file = templates.downloadCurrent(resource.templateId());
            appendTemplateResponse(
                    xml,
                    request.getRequestURI(),
                    file.manifest().displayName(),
                    file.commitId(),
                    file.manifest().updatedAt(),
                    file.content().length,
                    locks.find(resource.templateId()));
        }

        xml.append("</D:multistatus>");
        response.getWriter().write(xml.toString());
    }

    private void get(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean includeBody) throws IOException {
        Resource resource = resource(request);
        if (resource.collection()) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        TemplateFile file = templates.downloadCurrent(resource.templateId());
        if (etagMatches(request.getHeader("If-None-Match"), file.commitId())) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        response.setContentType(OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
        response.setContentLengthLong(file.content().length);
        response.setHeader("ETag", file.etag());
        response.setHeader("Last-Modified", HTTP_DATE.format(file.lastModified()));
        response.setHeader("Content-Disposition",
                "inline; filename=\"" + file.manifest().fileName() + "\"");
        response.setStatus(HttpServletResponse.SC_OK);
        if (includeBody) {
            response.getOutputStream().write(file.content());
        }
    }

    private void put(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        requireTemplateWriter(request);
        Resource resource = resource(request);
        if (resource.collection()) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        String owner = principalName(request);
        TemplateFile current = currentOrNull(resource.templateId());
        TemplateLock activeLock = locks.find(resource.templateId());
        String suppliedToken = requestLockToken(request);
        String expectedHead;

        if (activeLock != null) {
            TemplateLock verified = locks.require(
                    resource.templateId(), suppliedToken, owner);
            expectedHead = verified.currentCommit();
        } else if (current != null) {
            String ifMatch = request.getHeader("If-Match");
            if (ifMatch == null || ifMatch.isBlank()) {
                response.sendError(SC_PRECONDITION_REQUIRED,
                        "Use WebDAV LOCK or If-Match before replacing a template");
                return;
            }
            String condition = DocumentTemplateService.stripEtag(ifMatch);
            if ("*".equals(condition)) {
                expectedHead = current.commitId();
            } else {
                expectedHead = condition;
            }
        } else {
            expectedHead = null;
        }

        String displayName = current == null
                ? resource.templateId()
                : current.manifest().displayName();
        TemplateDescriptor saved = templates.upload(
                resource.templateId(),
                displayName,
                request.getInputStream(),
                expectedHead,
                owner,
                "Update document template through WebDAV");

        if (activeLock != null) {
            locks.advance(resource.templateId(), suppliedToken, owner, saved.headCommit());
        }

        response.setHeader("ETag", "\"" + saved.headCommit() + "\"");
        response.setHeader("Location", request.getRequestURI());
        response.setStatus(current == null
                ? HttpServletResponse.SC_CREATED
                : HttpServletResponse.SC_NO_CONTENT);
    }

    private void lock(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        requireTemplateWriter(request);
        Resource resource = resource(request);
        if (resource.collection()) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        String owner = principalName(request);
        TemplateFile current = currentOrNull(resource.templateId());
        String refreshToken = requestLockToken(request);
        TemplateLock lock = locks.acquire(
                resource.templateId(),
                owner,
                current == null ? null : current.commitId(),
                parseTimeout(request.getHeader("Timeout")),
                refreshToken);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Lock-Token", "<" + lock.token() + ">");
        response.setContentType("application/xml; charset=UTF-8");
        response.getWriter().write(lockResponse(lock));
    }

    private void unlock(HttpServletRequest request, HttpServletResponse response) {
        requireTemplateWriter(request);
        Resource resource = resource(request);
        if (resource.collection()) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        locks.release(
                resource.templateId(),
                request.getHeader("Lock-Token"),
                principalName(request));
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private TemplateFile currentOrNull(String templateId) throws IOException {
        try {
            return templates.downloadCurrent(templateId);
        } catch (TemplateNotFoundException exception) {
            return null;
        }
    }

    private static void requireTemplateWriter(HttpServletRequest request) {
        if (request.isUserInRole("ADMIN")
                || request.isUserInRole("ARCHITECT")
                || request.isUserInRole("ROLE_ADMIN")
                || request.isUserInRole("ROLE_ARCHITECT")) {
            return;
        }
        throw new SecurityException("Template write permission is required");
    }

    private static String principalName(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null || principal.getName() == null
                || principal.getName().isBlank()) {
            throw new SecurityException("Authentication is required");
        }
        return principal.getName();
    }

    private static Resource resource(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return new Resource(true, null);
        }
        if (!path.startsWith("/") || path.indexOf('/', 1) >= 0
                || !path.endsWith(".dotx")) {
            throw new IllegalArgumentException(
                    "WebDAV exposes only one level of .dotx template files");
        }
        String templateId = path.substring(1, path.length() - ".dotx".length());
        DocumentTemplateGitRepository.validateTemplateId(templateId);
        return new Resource(false, templateId);
    }

    private static String collectionHref(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private static String requestLockToken(HttpServletRequest request) {
        String direct = request.getHeader("Lock-Token");
        if (direct != null && !direct.isBlank()) {
            return DocumentTemplateWebDavLockManager.normalizeToken(direct);
        }
        String condition = request.getHeader("If");
        if (condition == null) {
            return null;
        }
        Matcher matcher = LOCK_TOKEN.matcher(condition);
        return matcher.find() ? matcher.group() : null;
    }

    private static Duration parseTimeout(String header) {
        if (header == null || header.isBlank()) {
            return DocumentTemplateWebDavLockManager.DEFAULT_TIMEOUT;
        }
        for (String candidate : header.split(",")) {
            String value = candidate.strip();
            if ("Infinite".equalsIgnoreCase(value)) {
                return DocumentTemplateWebDavLockManager.MAX_TIMEOUT;
            }
            if (value.regionMatches(true, 0, "Second-", 0, 7)) {
                try {
                    return Duration.ofSeconds(Long.parseLong(value.substring(7)));
                } catch (NumberFormatException ignored) {
                    // Fall through to the safe default.
                }
            }
        }
        return DocumentTemplateWebDavLockManager.DEFAULT_TIMEOUT;
    }

    private static boolean etagMatches(String header, String commitId) {
        if (header == null) {
            return false;
        }
        for (String candidate : header.split(",")) {
            String value = DocumentTemplateService.stripEtag(candidate);
            if ("*".equals(value) || commitId.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void addDavHeaders(HttpServletResponse response) {
        response.setHeader("DAV", "1, 2");
        response.setHeader("MS-Author-Via", "DAV");
        response.setHeader("Cache-Control", "private, no-cache");
    }

    private static void appendCollectionResponse(StringBuilder xml, String href) {
        xml.append("<D:response><D:href>").append(xml(href))
                .append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>Taxonomy document templates</D:displayname>")
                .append("<D:resourcetype><D:collection/></D:resourcetype>")
                .append("<D:supportedlock>")
                .append("<D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
                .append("<D:locktype><D:write/></D:locktype></D:lockentry>")
                .append("</D:supportedlock>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status>")
                .append("</D:propstat></D:response>");
    }

    private static void appendTemplateResponse(
            StringBuilder xml,
            String href,
            String displayName,
            String commitId,
            String updatedAt,
            long contentLength,
            TemplateLock lock) {
        xml.append("<D:response><D:href>").append(xml(href))
                .append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(xml(displayName)).append("</D:displayname>")
                .append("<D:resourcetype/>")
                .append("<D:getcontenttype>")
                .append(OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE)
                .append("</D:getcontenttype>")
                .append("<D:getcontentlength>").append(contentLength)
                .append("</D:getcontentlength>")
                .append("<D:getetag>&quot;").append(xml(commitId))
                .append("&quot;</D:getetag>")
                .append("<D:getlastmodified>")
                .append(xml(httpDate(updatedAt)))
                .append("</D:getlastmodified>")
                .append("<D:supportedlock>")
                .append("<D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
                .append("<D:locktype><D:write/></D:locktype></D:lockentry>")
                .append("</D:supportedlock>");
        if (lock != null) {
            xml.append("<D:lockdiscovery><D:activelock>")
                    .append("<D:locktype><D:write/></D:locktype>")
                    .append("<D:lockscope><D:exclusive/></D:lockscope>")
                    .append("<D:depth>0</D:depth>")
                    .append("<D:owner><D:href>").append(xml(lock.owner()))
                    .append("</D:href></D:owner>")
                    .append("<D:timeout>Second-")
                    .append(Math.max(1, Duration.between(
                            Instant.now(), lock.expiresAt()).toSeconds()))
                    .append("</D:timeout>")
                    .append("<D:locktoken><D:href>").append(xml(lock.token()))
                    .append("</D:href></D:locktoken>")
                    .append("</D:activelock></D:lockdiscovery>");
        } else {
            xml.append("<D:lockdiscovery/>");
        }
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status>")
                .append("</D:propstat></D:response>");
    }

    private static String lockResponse(TemplateLock lock) {
        long seconds = Math.max(1,
                Duration.between(Instant.now(), lock.expiresAt()).toSeconds());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype>"
                + "<D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:depth>0</D:depth>"
                + "<D:owner><D:href>" + xml(lock.owner()) + "</D:href></D:owner>"
                + "<D:timeout>Second-" + seconds + "</D:timeout>"
                + "<D:locktoken><D:href>" + xml(lock.token())
                + "</D:href></D:locktoken>"
                + "</D:activelock></D:lockdiscovery></D:prop>";
    }

    private static String httpDate(String instant) {
        try {
            return HTTP_DATE.format(Instant.parse(instant));
        } catch (RuntimeException exception) {
            return HTTP_DATE.format(Instant.EPOCH);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String xml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private record Resource(boolean collection, String templateId) {
    }
}
