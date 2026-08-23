package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.LockConflictException;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.LockedWriteResult;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.TemplateLock;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
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
    private static final String DAV_NAMESPACE = "DAV:";
    private static final Pattern LOCK_TOKEN =
            Pattern.compile("opaquelocktoken:[^>\\s)]+", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final XMLOutputFactory XML_OUTPUT_FACTORY =
            XMLOutputFactory.newFactory();

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
        response.setContentType("application/xml");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        try {
            XMLStreamWriter writer = XML_OUTPUT_FACTORY.createXMLStreamWriter(
                    response.getOutputStream(), StandardCharsets.UTF_8.name());
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            start(writer, "multistatus");
            writer.writeNamespace("D", DAV_NAMESPACE);

            if (resource.collection()) {
                writeCollectionResponse(writer, collectionHref(request));
                if (!"0".equals(depth)) {
                    for (TemplateDescriptor descriptor : templates.list()) {
                        TemplateFile file = templates.downloadCurrent(
                                descriptor.templateId());
                        byte[] content = file.content();
                        writeTemplateResponse(
                                writer,
                                templateHref(request, file.manifest().fileName()),
                                file.manifest().displayName(),
                                file.commitId(),
                                file.manifest().updatedAt(),
                                content.length,
                                locks.find(descriptor.templateId()));
                    }
                }
            } else {
                TemplateFile file = templates.downloadCurrent(resource.templateId());
                byte[] content = file.content();
                writeTemplateResponse(
                        writer,
                        templateHref(request, file.manifest().fileName()),
                        file.manifest().displayName(),
                        file.commitId(),
                        file.manifest().updatedAt(),
                        content.length,
                        locks.find(resource.templateId()));
            }

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        } catch (XMLStreamException exception) {
            throw new IOException("Could not produce WebDAV multistatus XML", exception);
        }
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
        byte[] content = file.content();
        response.setContentType(OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
        response.setHeader("ETag", file.etag());
        response.setHeader("Last-Modified", HTTP_DATE.format(file.lastModified()));
        response.setHeader("Content-Disposition",
                "inline; filename=\"" + file.manifest().fileName() + "\"");
        if (etagMatches(request.getHeader("If-None-Match"), file.commitId())) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        response.setContentLengthLong(content.length);
        response.setStatus(HttpServletResponse.SC_OK);
        if (includeBody) {
            response.getOutputStream().write(content);
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
        String suppliedToken = requestLockToken(request);
        TemplateLock observedLock = locks.find(resource.templateId());

        if (current != null
                && etagMatches(request.getHeader("If-None-Match"), current.commitId())) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED,
                    "If-None-Match precondition failed for the current template");
            return;
        }

        String ifMatch = request.getHeader("If-Match");
        String expectedWithoutLock = null;
        if (ifMatch != null && !ifMatch.isBlank()) {
            // Evaluate every If-Match precondition even when a DAV lock is active.
            // The service implements strong comparison and comma-separated tag lists.
            expectedWithoutLock = templates.resolveExpectedVersion(
                    resource.templateId(), ifMatch);
        } else if (observedLock == null && current != null) {
            response.sendError(SC_PRECONDITION_REQUIRED,
                    "Use WebDAV LOCK or If-Match before replacing a template");
            return;
        }

        String displayName = current == null
                ? resource.templateId()
                : current.manifest().displayName();
        String fallbackExpected = observedLock == null ? expectedWithoutLock : null;
        TemplateDescriptor saved = locks.executeWrite(
                resource.templateId(),
                suppliedToken,
                owner,
                fallbackExpected,
                expectedCommit -> {
                    TemplateDescriptor committed = templates.upload(
                            resource.templateId(),
                            displayName,
                            request.getInputStream(),
                            expectedCommit,
                            owner,
                            "Update document template through WebDAV");
                    return new LockedWriteResult<>(
                            committed, committed.headCommit());
                });

        response.setHeader("ETag", "\"" + saved.headCommit() + "\"");
        response.setHeader("Location",
                templateHref(request, saved.fileName()));
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
        response.setContentType("application/xml");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        writeLockResponse(
                response,
                lock,
                templateHref(request, resource.templateId() + ".dotx"));
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
                || request.isUserInRole("ROLE_ADMIN")) {
            return;
        }
        throw new SecurityException(
                "Administrator role is required to modify document templates");
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
        String contextPath = request.getContextPath();
        return (contextPath == null ? "" : contextPath) + "/dav/templates/";
    }

    private static String templateHref(HttpServletRequest request, String fileName) {
        return collectionHref(request) + encode(fileName);
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

    private static void writeCollectionResponse(
            XMLStreamWriter writer,
            String href) throws XMLStreamException {
        start(writer, "response");
        element(writer, "href", href);
        start(writer, "propstat");
        start(writer, "prop");
        element(writer, "displayname", "Taxonomy document templates");
        start(writer, "resourcetype");
        empty(writer, "collection");
        writer.writeEndElement();
        writeSupportedLock(writer);
        writer.writeEndElement();
        element(writer, "status", "HTTP/1.1 200 OK");
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeTemplateResponse(
            XMLStreamWriter writer,
            String href,
            String displayName,
            String commitId,
            String updatedAt,
            long contentLength,
            TemplateLock lock) throws XMLStreamException {
        start(writer, "response");
        element(writer, "href", href);
        start(writer, "propstat");
        start(writer, "prop");
        element(writer, "displayname", safeXmlText(displayName));
        empty(writer, "resourcetype");
        element(writer, "getcontenttype",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
        element(writer, "getcontentlength", Long.toString(contentLength));
        element(writer, "getetag", "\"" + commitId + "\"");
        element(writer, "getlastmodified", httpDate(updatedAt));
        writeSupportedLock(writer);
        writeLockDiscovery(writer, lock, href);
        writer.writeEndElement();
        element(writer, "status", "HTTP/1.1 200 OK");
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeSupportedLock(XMLStreamWriter writer)
            throws XMLStreamException {
        start(writer, "supportedlock");
        start(writer, "lockentry");
        start(writer, "lockscope");
        empty(writer, "exclusive");
        writer.writeEndElement();
        start(writer, "locktype");
        empty(writer, "write");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeLockDiscovery(
            XMLStreamWriter writer,
            TemplateLock lock,
            String href) throws XMLStreamException {
        start(writer, "lockdiscovery");
        if (lock != null) {
            writeActiveLock(writer, lock, href);
        }
        writer.writeEndElement();
    }

    private static void writeActiveLock(
            XMLStreamWriter writer,
            TemplateLock lock,
            String href) throws XMLStreamException {
        start(writer, "activelock");
        start(writer, "locktype");
        empty(writer, "write");
        writer.writeEndElement();
        start(writer, "lockscope");
        empty(writer, "exclusive");
        writer.writeEndElement();
        element(writer, "depth", "0");
        start(writer, "owner");
        element(writer, "href", safeXmlText(lock.owner()));
        writer.writeEndElement();
        element(writer, "timeout", "Second-" + Math.max(1,
                Duration.between(Instant.now(), lock.expiresAt()).toSeconds()));
        start(writer, "locktoken");
        element(writer, "href", lock.token());
        writer.writeEndElement();
        start(writer, "lockroot");
        element(writer, "href", href);
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeLockResponse(
            HttpServletResponse response,
            TemplateLock lock,
            String href) throws IOException {
        try {
            XMLStreamWriter writer = XML_OUTPUT_FACTORY.createXMLStreamWriter(
                    response.getOutputStream(), StandardCharsets.UTF_8.name());
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            start(writer, "prop");
            writer.writeNamespace("D", DAV_NAMESPACE);
            start(writer, "lockdiscovery");
            writeActiveLock(writer, lock, href);
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        } catch (XMLStreamException exception) {
            throw new IOException("Could not produce WebDAV lock XML", exception);
        }
    }

    private static void start(XMLStreamWriter writer, String localName)
            throws XMLStreamException {
        writer.writeStartElement("D", localName, DAV_NAMESPACE);
    }

    private static void empty(XMLStreamWriter writer, String localName)
            throws XMLStreamException {
        writer.writeEmptyElement("D", localName, DAV_NAMESPACE);
    }

    private static void element(
            XMLStreamWriter writer,
            String localName,
            String value) throws XMLStreamException {
        start(writer, localName);
        writer.writeCharacters(safeXmlText(value));
        writer.writeEndElement();
    }

    private static String safeXmlText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            safe.appendCodePoint(DocumentTemplateService.isValidXml10CodePoint(codePoint)
                    ? codePoint : 0xFFFD);
            offset += Character.charCount(codePoint);
        }
        return safe.toString();
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

    private record Resource(boolean collection, String templateId) {
    }
}
