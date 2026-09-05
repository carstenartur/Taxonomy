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
import java.net.URI;
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
