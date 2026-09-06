package com.taxonomy.templates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP with all controller advice and security filters, independent of WebDAV. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.servlet.context-path=/taxonomy",
        "spring.datasource.url=jdbc:hsqldb:mem:template_api_http",
        "embedding.enabled=false",
        "llm.mock=true",
        "taxonomy.init.async=false",
        "taxonomy.security.require-password-change=false",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
})
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentTemplateApiHttpIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String API = "/api/admin/document-templates/";
    private static final String DOTX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template";
    private static final byte[] EMPTY = new byte[0];
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("taxonomy.admin-password", () -> PASSWORD);
    }

    @Value("${local.server.port}") private int port;
    private HttpClient http;

    @BeforeEach
    void openClient() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void closeClient() {
        if (http != null) http.close();
    }

    @Test
    void missingTemplateIs404AfterAuthenticationNotAnInternalError() throws Exception {
        String path = API + "missing-" + UUID.randomUUID() + "/download";
        var anonymous = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        assertEquals(401, http.send(anonymous, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertDomainError(send("GET", path, EMPTY), 404);
    }

    @Test
    void absentOrStalePreconditionIs412AndInvalidPackageIs400() throws Exception {
        byte[] original = original();
        String path = API + "api-errors-" + UUID.randomUUID();
        var created = send("PUT", path, original);
        assertEquals(201, created.statusCode());
        String initial = etag(created);
        assertDomainError(send("PUT", path, modified(original, "missing-precondition")), 412);
        assertDomainError(send("PUT", path, "not-a-template".getBytes(StandardCharsets.UTF_8),
                "If-Match", initial), 400);
        assertEquals(initial, etag(send("GET", path + "/download", EMPTY)));
        assertEquals(1, historySize(path));

        var saved = send("PUT", path, modified(original, "accepted"), "If-Match", initial);
        assertEquals(201, saved.statusCode());
        assertNotEquals(initial, etag(saved));
        assertDomainError(send("PUT", path, modified(original, "stale"), "If-Match", initial), 412);
        assertEquals(etag(saved), etag(send("GET", path + "/download", EMPTY)));
        assertEquals(2, historySize(path));
    }

    @Test
    void concurrentWritesRetainWinnerAndRestoreAppendsOnlyAgainstCurrentHead() throws Exception {
        byte[] original = original();
        String path = API + "api-race-" + UUID.randomUUID();
        var created = send("PUT", path, original);
        assertEquals(201, created.statusCode());
        String initial = etag(created);
        byte[] first = modified(original, "first-writer");
        byte[] second = modified(original, "second-writer");
        var one = http.sendAsync(request("PUT", path, first, "If-Match", initial),
                HttpResponse.BodyHandlers.ofByteArray());
        var two = http.sendAsync(request("PUT", path, second, "If-Match", initial),
                HttpResponse.BodyHandlers.ofByteArray());
        var a = one.get(30, TimeUnit.SECONDS);
        var b = two.get(30, TimeUnit.SECONDS);
        assertEquals(List.of(201, 412), List.of(a.statusCode(), b.statusCode()).stream().sorted().toList());
        var winner = a.statusCode() == 201 ? a : b;
        assertDomainError(a.statusCode() == 412 ? a : b, 412);
        var current = send("GET", path + "/download", EMPTY);
        assertEquals(200, current.statusCode());
        assertEquals(etag(winner), etag(current));
        assertPartsEqual(a.statusCode() == 201 ? first : second, current.body());
        assertEquals(2, historySize(path));

        String revision = initial.replace("\"", "");
        var old = send("GET", path + "/download?revision=" + revision, EMPTY);
        assertEquals(200, old.statusCode());
        assertPartsEqual(original, old.body());
        assertDomainError(send("POST", path + "/restore?revision=" + revision, EMPTY,
                "If-Match", initial), 412);
        assertEquals(2, historySize(path));
        var restored = send("POST", path + "/restore?revision=" + revision, EMPTY,
                "If-Match", etag(winner));
        assertEquals(200, restored.statusCode());
        assertNotEquals(etag(winner), etag(restored));
        assertPartsEqual(original, send("GET", path + "/download", EMPTY).body());
        assertEquals(3, historySize(path));
    }

    @Test
    void browserRestoreRequiresConfirmationAndCsrfAndRetainsAStaleSelection() throws Exception {
        byte[] original = original();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var browser = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            var login = browserSend(browser, "GET", "/login", "");
            assertEquals(200, login.statusCode());
            var signedIn = browserSend(browser, "POST", "/login",
                    "username=admin&password=" + formEncode(PASSWORD) + "&_csrf=" + formEncode(csrfToken(login.body())));
            assertEquals(302, signedIn.statusCode());
            assertFalse(signedIn.headers().firstValue("Location").orElse("").contains("error"));

            for (String language : List.of("de", "en")) {
                String id = "restore-ui-" + UUID.randomUUID();
                String api = API + id;
                String page = "/admin/document-templates/" + id;
                var created = send("PUT", api, original);
                assertEquals(201, created.statusCode());
                String target = etag(created).replace("\"", "");
                byte[] secondContent = modified(original, "second-version");
                var second = send("PUT", api, secondContent, "If-Match", etag(created));
                assertEquals(201, second.statusCode());
                String expected = etag(second).replace("\"", "");
                String confirmation = page + "/restore?revision=" + target
                        + "&expectedHead=" + expected + "&lang=" + language;
                var confirmedPage = browserSend(browser, "GET", confirmation, "");
                assertEquals(200, confirmedPage.statusCode());
                String html = confirmedPage.body();
                assertTrue(html.contains(language.equals("de") ? "Wiederherstellung bestätigen" : "Confirm restoration"));
                assertTrue(html.contains("API-QA"));
                assertTrue(html.contains("id=\"restoreConfirmed\""));
                assertTrue(html.contains("id=\"restoreConfirmationForm\""));
                assertFalse(html.contains("??document.template.restore."));
                assertDisplayedRevision(html, "restoreTargetRevision", target);
                assertDisplayedRevision(html, "restoreExpectedRevision", expected);
                assertEquals(2, historySize(api));
                assertEquals(etag(second), etag(send("GET", api + "/download", EMPTY)));

                String post = page + "/restore?lang=" + language;
                String fields = "revision=" + target + "&expectedHead=" + expected;
                assertEquals(403, browserSend(browser, "POST", post, fields + "&confirmed=true").statusCode());
                assertEquals(400, browserSend(browser, "POST", post,
                        fields + "&_csrf=" + formEncode(csrfToken(html))).statusCode());
                assertEquals(2, historySize(api));

                byte[] winnerContent = modified(original, "concurrent-winner");
                var winner = send("PUT", api, winnerContent, "If-Match", etag(second));
                assertEquals(201, winner.statusCode());
                String winnerHead = etag(winner).replace("\"", "");
                var stale = browserSend(browser, "POST", post,
                        fields + "&confirmed=true&_csrf=" + formEncode(csrfToken(html)));
                assertEquals(412, stale.statusCode());
                assertStaleConfirmation(stale.body(), target, expected, winnerHead);
                assertEquals(3, historySize(api));
                var preserved = send("GET", api + "/download", EMPTY);
                assertEquals(etag(winner), etag(preserved));
                assertPartsEqual(winnerContent, preserved.body());

                var reloaded = browserSend(browser, "GET", confirmation, "");
                assertEquals(200, reloaded.statusCode());
                assertStaleConfirmation(reloaded.body(), target, expected, winnerHead);
                var targetDownload = send("GET", api + "/download?revision=" + target, EMPTY);
                assertEquals(200, targetDownload.statusCode());
                assertPartsEqual(original, targetDownload.body());
                assertEquals(3, historySize(api));

                // Only an explicit new confirmation uses the new head; never retry a stale write automatically.
                var fresh = browserSend(browser, "GET", page + "/restore?revision=" + target
                        + "&expectedHead=" + winnerHead + "&lang=" + language, "");
                assertEquals(200, fresh.statusCode());
                assertTrue(fresh.body().contains("id=\"restoreConfirmationForm\""));
                assertDisplayedRevision(fresh.body(), "restoreExpectedRevision", winnerHead);
                String acceptedForm = "revision=" + target + "&expectedHead=" + winnerHead
                        + "&confirmed=true&_csrf=" + formEncode(csrfToken(fresh.body()));
                var restored = browserSend(browser, "POST", post, acceptedForm);
                assertEquals(302, restored.statusCode());
                String location = restored.headers().firstValue("Location").orElseThrow();
                URI redirect = uri(post).resolve(location);
                assertEquals("127.0.0.1", redirect.getHost());
                assertEquals(port, redirect.getPort());
                assertEquals("/taxonomy" + page, redirect.getPath());
                var saved = send("GET", api + "/download", EMPTY);
                assertEquals(200, saved.statusCode());
                assertNotEquals(etag(winner), etag(saved));
                assertPartsEqual(original, saved.body());
                assertEquals(4, historySize(api));
                assertPartsEqual(winnerContent,
                        send("GET", api + "/download?revision=" + winnerHead, EMPTY).body());
                var success = browserSend(browser, "GET", page + "?lang=" + language, "");
                assertEquals(200, success.statusCode());
                assertTrue(success.body().contains("id=\"restoreSuccess\""));
                assertTrue(success.body().contains(etag(saved).replace("\"", "")));
                assertFalse(success.body().contains("??document.template.restore."));

                assertEquals(412, browserSend(browser, "POST", post, acceptedForm).statusCode());
                assertEquals(4, historySize(api));
                assertEquals(etag(saved), etag(send("GET", api + "/download", EMPTY)));
            }
        }
    }

    private HttpResponse<String> browserSend(HttpClient browser, String method, String path, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "text/html");
        if (method.equals("POST")) {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String csrfToken(String html) {
        var input = Pattern.compile("<input\\b(?=[^>]*\\bname=\"_csrf\")(?=[^>]*\\bvalue=\"([^\"]+)\")[^>]*>")
                .matcher(html);
        assertTrue(input.find(), "Server-rendered form must include its CSRF token");
        return input.group(1);
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void assertDisplayedRevision(String html, String id, String revision) {
        assertTrue(Pattern.compile("<code\\b[^>]*\\bid=\"" + Pattern.quote(id)
                + "\"[^>]*>" + Pattern.quote(revision) + "</code>").matcher(html).find(), id);
    }

    private static void assertStaleConfirmation(String html, String target, String expected, String current) {
        assertDisplayedRevision(html, "restoreTargetRevision", target);
        assertDisplayedRevision(html, "restoreExpectedRevision", expected);
        assertDisplayedRevision(html, "restoreCurrentRevision", current);
        assertTrue(html.contains("id=\"restoreConflict\""));
        assertTrue(html.contains("id=\"restoreReviewCurrent\""));
        assertFalse(html.contains("id=\"restoreConfirmationForm\""));
        assertFalse(html.contains("id=\"restoreSubmit\""));
        assertFalse(html.contains("TemplateConflictException"));
    }

    private byte[] original() throws Exception {
        var response = send("GET", API + "decision-rationale-report/download", EMPTY);
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private int historySize(String path) throws Exception {
        var response = send("GET", path + "/history", EMPTY);
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body()).size();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/taxonomy" + path);
    }

    private HttpRequest request(String method, String path, byte[] body, String... headers) {
        var request = HttpRequest.newBuilder(uri(path + ("PUT".equals(method) ? "?displayName=API-QA" : "")))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "*/*")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        ("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", DOTX)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length != 0) request.headers(headers);
        return request.build();
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body, String... headers)
            throws Exception {
        return http.send(request(method, path, body, headers), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String etag(HttpResponse<?> response) {
        return response.headers().firstValue("ETag").orElseThrow();
    }

    private static void assertDomainError(HttpResponse<byte[]> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        Map<?, ?> error = JSON.readValue(response.body(), Map.class);
        assertEquals(java.util.Set.of("error"), error.keySet());
        assertInstanceOf(String.class, error.get("error"));
        assertFalse(((String) error.get("error")).isBlank());
        assertFalse(((String) error.get("error")).contains("com.taxonomy."));
    }

    private static Map<String, byte[]> parts(byte[] archive) throws Exception {
        Map<String, byte[]> parts = new TreeMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!entry.isDirectory()) assertNull(parts.put(entry.getName(), zip.readAllBytes()));
            }
        }
        assertTrue(parts.containsKey("word/document.xml"));
        return parts;
    }

    private static void assertPartsEqual(byte[] expected, byte[] actual) throws Exception {
        var before = parts(expected);
        var after = parts(actual);
        assertEquals(before.keySet(), after.keySet());
        for (String part : before.keySet()) assertArrayEquals(before.get(part), after.get(part), part);
    }

    private static byte[] modified(byte[] original, String marker) throws Exception {
        var entries = parts(original);
        String xml = new String(entries.get("word/document.xml"), StandardCharsets.UTF_8);
        // The final section properties must remain the last body child in valid WordprocessingML.
        int position = xml.lastIndexOf("<w:sectPr");
        if (position < 0) position = xml.lastIndexOf("</w:body>");
        assertTrue(position >= 0);
        String paragraph = "<w:p><w:r><w:t>" + marker + "</w:t></w:r></w:p>";
        entries.put("word/document.xml", (xml.substring(0, position) + paragraph + xml.substring(position))
                .getBytes(StandardCharsets.UTF_8));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
