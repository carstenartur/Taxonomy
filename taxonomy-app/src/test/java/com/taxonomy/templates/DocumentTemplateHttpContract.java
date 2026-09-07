package com.taxonomy.templates;

import tools.jackson.databind.json.JsonMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Real HTTP assertions shared by Failsafe and a loopback-only diagnostic runner. */
final class DocumentTemplateHttpContract implements AutoCloseable {
    private static final String API = "/api/admin/document-templates/";
    private static final String DAV = "/dav/templates/";
    private static final String DOTX = "application/vnd.openxmlformats-officedocument.wordprocessingml.template";
    private static final byte[] EMPTY = new byte[0];
    private final URI base;
    private final String password;
    private final String authorization;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    DocumentTemplateHttpContract(String base, String password) {
        this.base = URI.create(base);
        require(List.of("http", "https").contains(this.base.getScheme())
                && List.of("localhost", "127.0.0.1", "[::1]").contains(this.base.getHost())
                && this.base.getUserInfo() == null && this.base.getQuery() == null
                && this.base.getFragment() == null, "Use an isolated loopback test instance");
        this.password = password;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                ("admin:" + password).getBytes(StandardCharsets.UTF_8));
    }

    void webDavRoundTrip() throws Exception {
        var options = send("OPTIONS", DAV, EMPTY);
        status(options, 200);
        require(options.headers().firstValue("Allow").orElse("").contains("PROPFIND"), "DAV advertised");
        for (String depth : List.of("0", "1")) {
            var listing = send("PROPFIND", DAV, EMPTY, "Depth", depth);
            status(listing, 207);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(listing.body()));
            require("DAV:".equals(xml.getDocumentElement().getNamespaceURI()), "DAV namespace");
            require("multistatus".equals(xml.getDocumentElement().getLocalName()), "DAV multistatus body");
            int responses = xml.getElementsByTagNameNS("DAV:", "response").getLength();
            require(depth.equals("0") ? responses == 1 : responses > 1, "Depth-specific resource listing");
        }
        byte[] original = original();
        String id = fixtureId();
        var created = send("PUT", API + id + "?displayName=HTTP-contract", original, "Content-Type", DOTX);
        status(created, 201);
        String resource = DAV + id + ".dotx";
        String head = etag(created);
        status(send("HEAD", resource, EMPTY), 200);
        var get = send("GET", resource, EMPTY);
        status(get, 200);
        require(etag(get).equals(head), "Same template revision across API and DAV");
        status(send("GET", resource, EMPTY, "If-None-Match", head), 304);
        byte[] lockBody = ("<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:locktype><D:write/></D:locktype><D:owner>HTTP contract</D:owner></D:lockinfo>")
                .getBytes(StandardCharsets.UTF_8);
        var lock = send("LOCK", resource, lockBody, "Content-Type", "application/xml", "Timeout", "Second-60");
        status(lock, 200);
        String token = lock.headers().firstValue("Lock-Token").orElseThrow();
        require(token.startsWith("<opaquelocktoken:"), "Opaque lock token");
        try {
            status(send("PUT", resource, modified(original, "locked"), "Content-Type", DOTX, "If-Match", head), 423);
            var refresh = send("LOCK", resource, EMPTY, "If", "(" + token + ")", "Timeout", "Second-60");
            status(refresh, 200);
            var saved = send("PUT", resource, modified(original, "accepted-dav"),
                    "Content-Type", DOTX, "If-Match", head, "If", "(" + token + ")");
            status(saved, 204);
            require(!etag(saved).equals(head), "DAV save creates a new revision");
            var current = send("GET", resource, EMPTY);
            status(current, 200);
            require(etag(current).equals(etag(saved)) && documentXml(current.body()).contains("accepted-dav"),
                    "Acknowledged DAV save is downloadable");
            status(send("PUT", resource, modified(original, "stale"), "Content-Type", DOTX,
                    "If-Match", head, "If", "(" + token + ")"), 412);
        } finally {
            status(send("UNLOCK", resource, EMPTY, "Lock-Token", token), 204);
        }
    }

    void apiConflictAndHistory() throws Exception {
        status(send("GET", API + fixtureId() + "/download", EMPTY), 404);
        byte[] original = original();
        String path = API + fixtureId();
        String upload = path + "?displayName=HTTP-contract";
        var first = send("PUT", upload, original, "Content-Type", DOTX);
        status(first, 201);
        String head = etag(first);
        status(send("PUT", upload, modified(original, "no-precondition"), "Content-Type", DOTX), 412);
        status(send("PUT", upload, "not-a-dotx".getBytes(StandardCharsets.UTF_8),
                "Content-Type", DOTX, "If-Match", head), 400);
        require(etag(send("GET", path + "/download", EMPTY)).equals(head), "Invalid data does not change head");
        var one = http.sendAsync(request("PUT", upload, modified(original, "winner-one"),
                "Content-Type", DOTX, "If-Match", head), HttpResponse.BodyHandlers.ofByteArray());
        var two = http.sendAsync(request("PUT", upload, modified(original, "winner-two"),
                "Content-Type", DOTX, "If-Match", head), HttpResponse.BodyHandlers.ofByteArray());
        var a = one.get(30, TimeUnit.SECONDS);
        var b = two.get(30, TimeUnit.SECONDS);
        require(List.of(201, 412).equals(List.of(a.statusCode(), b.statusCode()).stream().sorted().toList()),
                "Concurrent saves: one 201 and one 412; observed " + a.statusCode() + "/" + b.statusCode());
        var winner = a.statusCode() == 201 ? a : b;
        String marker = a.statusCode() == 201 ? "winner-one" : "winner-two";
        var current = send("GET", path + "/download", EMPTY);
        status(current, 200);
        require(etag(current).equals(etag(winner)) && documentXml(current.body()).contains(marker), "Winning content is preserved");
        var history = send("GET", path + "/history", EMPTY);
        status(history, 200);
        require(JsonMapper.builder().build().readTree(history.body()).size() == 2, "Only one accepted update in history");
        status(send("PUT", upload, modified(original, "stale-after-race"),
                "Content-Type", DOTX, "If-Match", head), 412);
        var historical = send("GET", path + "/download?revision=" + head.replace("\"", ""), EMPTY);
        status(historical, 200);
        require(etag(historical).equals(head), "Original revision remains downloadable");
        status(send("POST", path + "/restore?revision=" + head.replace("\"", ""), EMPTY,
                "If-Match", head), 412);
        var restored = send("POST", path + "/restore?revision=" + head.replace("\"", ""), EMPTY,
                "If-Match", etag(winner));
        status(restored, 200);
        var restoredFile = send("GET", path + "/download", EMPTY);
        status(restoredFile, 200);
        require(documentXml(restoredFile.body()).equals(documentXml(historical.body())), "Restore preserves original content");
        require(JsonMapper.builder().build().readTree(send("GET", path + "/history", EMPTY).body()).size() == 3,
                "Restore appends history instead of rewriting it");
    }

    void methodAndPathRestrictions() throws Exception {
        for (String method : List.of("PROPFIND", "LOCK", "UNLOCK")) {
            for (String path : List.of("/api/admin/document-templates", "/login", "/dav/templates-extra/")) {
                status(send(method, path, EMPTY), 400);
            }
        }
        for (String method : List.of("MKCOL", "PROPPATCH", "COPY", "MOVE")) {
            status(send(method, DAV, EMPTY), 400);
        }
        // Tomcat rejects TRACE before the security chain with 405; either layer must deny it.
        int trace = send("TRACE", DAV, EMPTY).statusCode();
        require(trace == 400 || trace == 405, "TRACE remains disabled");
        for (String path : List.of("/dav/templates/../login", "/dav/templates/%2e%2e/login",
                "/dav/templates/a;ignored/", "/dav/templates//child", "/dav/templates/%2fescape")) {
            status(send("PROPFIND", path, EMPTY), 400);
        }
        var unauthenticated = HttpRequest.newBuilder(URI.create(base + DAV)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/xml").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build();
        // Without an explicit protocol credential, the normal CSRF boundary rejects this unsafe verb.
        status(http.send(unauthenticated, HttpResponse.BodyHandlers.ofByteArray()), 403);
    }

    void sessionCsrfRemainsRequired() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var browser = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build()) {
            var login = browser.send(HttpRequest.newBuilder(URI.create(base + "/login")).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            status(login, 200);
            String token = htmlValue(new String(login.body(), StandardCharsets.UTF_8), "input", "_csrf", "value");
            String form = "username=admin&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                    + "&_csrf=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            var loggedIn = browser.send(HttpRequest.newBuilder(URI.create(base + "/login")).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofByteArray());
            status(loggedIn, 302);
            require(!loggedIn.headers().firstValue("Location").orElse("").contains("error"), "Session login succeeds");
            var page = browser.send(HttpRequest.newBuilder(URI.create(base + "/admin/document-templates"))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            status(page, 200);
            String html = new String(page.body(), StandardCharsets.UTF_8);
            String csrfHeader = htmlValue(html, "meta", "_csrf_header", "content");
            String csrf = htmlValue(html, "meta", "_csrf", "content");
            for (String method : List.of("PROPFIND", "LOCK", "UNLOCK")) {
                var response = browser.send(HttpRequest.newBuilder(URI.create(base + DAV))
                        .timeout(Duration.ofSeconds(20)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                status(response, 403);
            }
            var permitted = browser.send(HttpRequest.newBuilder(URI.create(base + DAV)).timeout(Duration.ofSeconds(20))
                    .header(csrfHeader, csrf).header("Depth", "0").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            status(permitted, 207);
            var rejected = browser.send(HttpRequest.newBuilder(URI.create(base + API + fixtureId() + "?displayName=CSRF"))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", DOTX)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(original())).build(), HttpResponse.BodyHandlers.ofByteArray());
            status(rejected, 403);
        }
    }

    void readOnlyCredentialsRemainScopedAndRevocable() throws Exception {
        String endpoint = "/api/admin/webdav-credentials";
        var created = send("POST", endpoint,
                "{\"description\":\"HTTP-contract\",\"readAllowed\":true,\"writeAllowed\":false,\"lifetimeDays\":1}"
                        .getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json");
        status(created, 201);
        var json = JsonMapper.builder().build().readTree(created.body());
        String id = json.path("credential").path("id").asText();
        String secret = json.path("secret").asText();
        require(!id.isBlank() && !secret.isBlank(), "Scoped credential created");
        String readOnly = "Basic " + Base64.getEncoder().encodeToString(("admin:" + secret).getBytes(StandardCharsets.UTF_8));
        try {
            status(scoped(readOnly, "GET", DAV + "decision-rationale-report.dotx"), 200);
            status(scoped(readOnly, "PROPFIND", DAV), 207);
            status(scoped(readOnly, "PUT", DAV + "decision-rationale-report.dotx"), 403);
            status(scoped(readOnly, "LOCK", DAV + "decision-rationale-report.dotx"), 403);
            status(scoped(readOnly, "UNLOCK", DAV + "decision-rationale-report.dotx"), 403);
            status(scoped(readOnly, "GET", API.substring(0, API.length() - 1)), 401);
        } finally {
            status(send("DELETE", endpoint + "/" + id, EMPTY), 204);
        }
        status(scoped(readOnly, "GET", DAV + "decision-rationale-report.dotx"), 401);
    }

    private HttpResponse<byte[]> scoped(String credential, String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", credential).header("Depth", "0")
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Override
    public void close() {
        http.close();
    }

    private byte[] original() throws Exception {
        var response = send("GET", API + "decision-rationale-report/download", EMPTY);
        status(response, 200);
        return response.body();
    }

    private HttpRequest request(String method, String path, byte[] body, String... headers) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length != 0) builder.headers(headers);
        return builder.build();
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body, String... headers) throws Exception {
        return http.send(request(method, path, body, headers), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String etag(HttpResponse<?> response) { return response.headers().firstValue("ETag").orElseThrow(); }
    private static String fixtureId() { return "qa-http-" + UUID.randomUUID().toString().replace("-", ""); }
    private static void status(HttpResponse<?> response, int expected) {
        require(response.statusCode() == expected, response.request().method() + " " + response.request().uri().getPath()
                + " expected HTTP " + expected + ", received " + response.statusCode());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static String htmlValue(String html, String tag, String name, String attribute) {
        var tags = Pattern.compile("<" + tag + "\\b[^>]*>").matcher(html);
        while (tags.find()) {
            String value = tags.group();
            if (value.contains("name=\"" + name + "\"")) {
                var match = Pattern.compile(attribute + "=\"([^\"]*)\"").matcher(value);
                if (match.find()) return match.group(1);
            }
        }
        throw new AssertionError("Missing HTML field " + name);
    }

    private static String documentXml(byte[] dotx) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(dotx))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("word/document.xml")) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("No document part");
    }

    private static byte[] modified(byte[] dotx, String marker) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(dotx)); var out = new ZipOutputStream(buffer)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(data, StandardCharsets.UTF_8);
                    require(xml.contains("</w:body>"), "Expected bundled Word body");
                    data = xml.replace("</w:body>", "<w:p><w:r><w:t>" + marker + "</w:t></w:r></w:p></w:body>")
                            .getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(entry.getName())); out.write(data); out.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** Does not replace Maven verification; creates only uniquely named isolated test templates. */
    public static void main(String[] args) throws Exception {
        String password = System.getenv("QA_PASSWORD");
        require(args.length == 1 && password != null && !password.isBlank(), "Specify loopback base URL and QA_PASSWORD");
        int failures = 0;
        try (var contract = new DocumentTemplateHttpContract(args[0], password)) {
            for (String method : List.of("webDavRoundTrip", "apiConflictAndHistory", "methodAndPathRestrictions", "sessionCsrfRemainsRequired", "readOnlyCredentialsRemainScopedAndRevocable")) {
                try {
                    DocumentTemplateHttpContract.class.getDeclaredMethod(method).invoke(contract);
                    System.out.println(method + ": PASS");
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    System.out.println(method + ": FAIL: " + failure.getCause().getMessage());
                    failures++;
                }
            }
        }
        if (failures != 0) throw new AssertionError(failures + " HTTP contract groups failed");
    }
}
