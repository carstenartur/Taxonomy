package com.taxonomy.templates;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real Git CLI, actual Spring security and the same database repository as the template API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.servlet.context-path=/taxonomy",
        "spring.datasource.url=jdbc:hsqldb:mem:template_git_http",
        "embedding.enabled=false",
        "llm.mock=true",
        "taxonomy.init.async=false",
        "taxonomy.security.require-password-change=false",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
})
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentTemplateGitHttpIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String GIT_PATH = "/api/admin/git/taxonomy-document-templates.git";
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("taxonomy.admin-password", () -> PASSWORD);
    }
    @Value("${local.server.port}") private int port;
    @Autowired private DocumentTemplateService templates;
    @Autowired private HibernateRepositoryFactory repositories;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder encoder;
    @TempDir Path temporary;

    @Test
    void gitCliClonesFetchesAndCannotPushOverTheSameHttpEndpoint() throws Exception {
        var originalTemplate = templates.downloadCurrent("decision-rationale-report");
        byte[] original = originalTemplate.content();
        String repositoryHead = templates.headCommit();
        assertTrue(git("ls-remote", base() + GIT_PATH, "refs/heads/main").output()
                .contains(repositoryHead + "\trefs/heads/main"));
        git("clone", "--branch", "main", base() + GIT_PATH, "checkout");
        Path checkout = temporary.resolve("checkout");
        assertEquals(repositoryHead, git("-C", "checkout", "rev-parse", "HEAD").output().strip());
        assertArrayEquals(documentXml(original), Files.readAllBytes(checkout.resolve(
                "templates/decision-rationale-report/package/word/document.xml")));
        git("-C", "checkout", "fsck", "--full");

        // Upload via the ordinary application API, not a parallel Git writer or mirror.
        String id = "git-read-" + UUID.randomUUID();
        var upload = request("PUT", "/api/admin/document-templates/" + id + "?displayName=Git-read-QA",
                original, basic("admin", PASSWORD), OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
        assertEquals(201, upload.statusCode());
        String uploadedCommit = templates.headCommit();
        git("-C", "checkout", "fetch", "origin");
        assertEquals(uploadedCommit, git("-C", "checkout", "rev-parse", "origin/main").output().strip());
        assertEquals(repositoryHead, git("-C", "checkout", "rev-parse", "HEAD").output().strip());
        assertArrayEquals(documentXml(original), git("-C", "checkout", "show",
                "origin/main:templates/" + id + "/package/word/document.xml").bytes());
        assertTrue(git("-C", "checkout", "log", "--format=%H", "origin/main", "--",
                "templates/decision-rationale-report/").output().contains(originalTemplate.commitId()));

        git("-C", "checkout", "-c", "user.name=Git QA", "-c", "user.email=qa@example.invalid",
                "commit", "--allow-empty", "-m", "Must not reach server");
        GitResult rejected = runGit("-C", "checkout", "push", "origin", "HEAD:refs/heads/qa-must-not-exist");
        assertNotEquals(0, rejected.exitCode(), "Read-only remote must reject an actual Git push");
        assertEquals(uploadedCommit, templates.headCommit());
        assertFalse(git("ls-remote", base() + GIT_PATH).output().contains("qa-must-not-exist"));
    }

    @Test
    void transportKeepsAuthenticationAdminIsolationAndNoDumbHttp() throws Exception {
        String discovery = GIT_PATH + "/info/refs?service=git-upload-pack";
        assertEquals(401, request("GET", discovery, null, null, null).statusCode());
        assertEquals(403, request("POST", GIT_PATH + "/git-upload-pack", new byte[0],
                null, "application/x-git-upload-pack-request").statusCode());
        assertEquals(401, request("GET", discovery, null, basic("admin", "incorrect"), null).statusCode());
        AppUser reader = new AppUser();
        reader.setUsername("git-reader-" + UUID.randomUUID());
        reader.setPasswordHash(encoder.encode(PASSWORD));
        reader.setEnabled(true);
        reader.setMustChangePassword(false);
        reader.setRoles(Set.of(roles.findByName("ROLE_USER").orElseThrow()));
        users.saveAndFlush(reader);
        assertEquals(403, request("GET", discovery, null, basic(reader.getUsername(), PASSWORD), null).statusCode());

        var authenticated = request("GET", discovery, null, basic("admin", PASSWORD), null);
        assertEquals(200, authenticated.statusCode());
        assertTrue(authenticated.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/x-git-upload-pack-advertisement"));
        // A real existing sibling repository must be just as undiscoverable as a missing one.
        String hidden = "git-private-" + UUID.randomUUID();
        try (var ignored = repositories.open(new RepositoryName(hidden))) {
            assertEquals(404, request("GET", "/api/admin/git/" + hidden
                    + ".git/info/refs?service=git-upload-pack", null, basic("admin", PASSWORD), null).statusCode());
        }
        assertEquals(404, request("GET", GIT_PATH + "/HEAD", null, basic("admin", PASSWORD), null).statusCode());
        assertEquals(403, request("GET", GIT_PATH + "/info/refs?service=git-receive-pack",
                null, basic("admin", PASSWORD), null).statusCode());
        assertEquals(403, request("POST", GIT_PATH + "/git-receive-pack", new byte[0],
                basic("admin", PASSWORD), "application/x-git-receive-pack-request").statusCode());
    }

    private String base() { return "http://127.0.0.1:" + port + "/taxonomy"; }
    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private HttpResponse<byte[]> request(String method, String path, byte[] body,
                                         String authorization, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/x-git-upload-pack-advertisement")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorization != null) builder.header("Authorization", authorization);
        if (contentType != null) builder.header("Content-Type", contentType);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
    private GitResult git(String... args) throws Exception {
        GitResult result = runGit(args);
        assertEquals(0, result.exitCode(), () -> "Git " + args[0] + " failed: " + result.diagnostic());
        return result;
    }
    private GitResult runGit(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(temporary, "git-output-", ".txt");
        Path error = Files.createTempFile(temporary, "git-error-", ".txt");
        Path emptyConfig = temporary.resolve("empty-git-config");
        Files.writeString(emptyConfig, "");
        var builder = new ProcessBuilder(command).directory(temporary.toFile())
                .redirectError(error.toFile()).redirectOutput(output.toFile());
        var env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("GIT_"));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
        // No password in a process argument, repository URL, Git config file or retained log.
        env.put("GIT_CONFIG_COUNT", "3");
        env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: " + basic("admin", PASSWORD));
        env.put("GIT_CONFIG_KEY_1", "credential.helper"); env.put("GIT_CONFIG_VALUE_1", "");
        env.put("GIT_CONFIG_KEY_2", "http.followRedirects"); env.put("GIT_CONFIG_VALUE_2", "false");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Git command exceeded its bounded timeout");
            String diagnostic = Files.readString(error, StandardCharsets.UTF_8)
                    .replace(basic("admin", PASSWORD), "[redacted]").replace(PASSWORD, "[redacted]");
            Files.writeString(error, diagnostic, StandardCharsets.UTF_8);
            return new GitResult(process.exitValue(), Files.readAllBytes(output), diagnostic);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    private static byte[] documentXml(byte[] archive) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) return zip.readAllBytes();
            }
        }
        throw new AssertionError("Bundled template lacks word/document.xml");
    }
    private record GitResult(int exitCode, byte[] bytes, String diagnostic) {
        String output() { return new String(bytes, StandardCharsets.UTF_8); }
    }
}
