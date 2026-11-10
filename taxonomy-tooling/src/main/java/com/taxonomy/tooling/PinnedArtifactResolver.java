package com.taxonomy.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves immutable product-test inputs; only verified complete bytes become cache entries. */
final class PinnedArtifactResolver {
    record Artifact(String id, String producer, String version, URI source, String sha256, long maxBytes) {
        Artifact {
            require(id != null && id.matches("[a-z0-9][a-z0-9-]{0,79}"), "Invalid artifact identity");
            require(producer != null && !producer.isBlank() && version != null && !version.isBlank(), "Missing producer/version");
            require(source != null && "https".equals(source.getScheme()) && source.getHost() != null
                    && source.getUserInfo() == null && source.getFragment() == null, "Artifact source must be an HTTPS URL");
            require(sha256 != null && sha256.matches("[a-f0-9]{64}"), "Invalid pinned SHA-256");
            require(maxBytes > 0 && maxBytes <= 1_073_741_824L, "Invalid artifact byte limit");
        }
    }

    @FunctionalInterface interface Download { void copy(Artifact artifact, Path target) throws IOException; }

    private PinnedArtifactResolver() { }

    static int run(String[] arguments, Path workingDirectory, PrintStream output, PrintStream error) {
        try {
            var options = TaxonomyTooling.Arguments.parse(arguments);
            Path manifest = workingDirectory.resolve(options.required("manifest")).normalize();
            Artifact artifact = readManifest(manifest, options.required("artifact"), options.required("producer"));
            Path cache = workingDirectory.resolve(options.required("cache")).normalize();
            String supplied = options.optional("local");
            Path local = supplied == null ? null : workingDirectory.resolve(supplied).normalize();
            Map<String, Object> receipt = resolve(artifact, cache, local,
                    options.booleanValue("offline", false), PinnedArtifactResolver::download);
            output.println(FlatJson.pretty(receipt));
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("Pinned product artifact unavailable: " + failure.getMessage());
            return 1;
        }
    }

    static Artifact readManifest(Path manifest, String id, String expectedProducer) throws IOException {
        require(Files.size(manifest) <= 65_536, "Product artifact manifest exceeds 64 KiB");
        Map<String, Object> document = FlatJson.parseObject(Files.readString(manifest));
        require(wholeNumber(document.get("schemaVersion"), "manifest version") == 1,
                "Unsupported product artifact manifest version");
        require(document.get("artifacts") instanceof List<?>, "Product artifact list is missing");
        var seen = new java.util.HashSet<String>();
        Artifact selected = null;
        for (Object entry : (List<?>) document.get("artifacts")) {
            require(entry instanceof Map<?, ?>, "Invalid product artifact entry");
            Map<?, ?> fields = (Map<?, ?>) entry;
            var artifact = new Artifact(text(fields, "id"), text(fields, "producer"), text(fields, "version"),
                    URI.create(text(fields, "source")), text(fields, "sha256"), wholeNumber(fields.get("maxBytes"), "artifact byte limit"));
            require(seen.add(artifact.id()), "Duplicate product artifact identity");
            if (id.equals(artifact.id())) selected = artifact;
        }
        require(selected != null, "Requested artifact is not pinned in the manifest");
        require(expectedProducer.equals(selected.producer()), "Selected artifact producer does not match the requested producer");
        return selected;
    }

    static Map<String, Object> resolve(Artifact artifact, Path cache, Path local, boolean offline, Download download)
            throws IOException {
        Path directory = cache.toAbsolutePath().normalize();
        Path cached = directory.resolve(artifact.sha256());
        String origin;
        // An explicit local selection is an assertion, not an optional hint that may be silently ignored.
        if (local != null) {
            verify(local, artifact);
            origin = "LOCAL";
        } else if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) {
            verify(cached, artifact);
            return receipt(artifact, cached, "CACHE");
        } else {
            if (offline) throw new IOException("No verified cached artifact; preseed the pinned bytes with --local before offline verification");
            origin = "DOWNLOAD";
        }
        Files.createDirectories(directory);
        Path pending = Files.createTempFile(directory, ".pending-", ".part");
        try {
            if (local != null) copyBounded(local, pending, artifact.maxBytes());
            else download.copy(artifact, pending);
            // Validate copied bytes, not just the source before copying (which may have changed).
            verify(pending, artifact);
            Files.move(pending, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            verify(cached, artifact);
            return receipt(artifact, cached, origin);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static Map<String, Object> receipt(Artifact artifact, Path path, String origin) throws IOException {
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("schemaVersion", 1);
        receipt.put("artifact", artifact.id());
        receipt.put("producer", artifact.producer());
        receipt.put("version", artifact.version());
        receipt.put("source", artifact.source().toString());
        receipt.put("sha256", artifact.sha256());
        receipt.put("bytes", Files.size(path));
        receipt.put("path", path.toString());
        receipt.put("resolvedFrom", origin);
        return receipt;
    }

    private static void verify(Path path, Artifact artifact) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Artifact must be a regular file, without a symbolic link");
        if (Files.size(path) > artifact.maxBytes()) throw new IOException("Artifact exceeds its pinned byte limit");
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65_536]; long count = 0;
            for (int size; (size = input.read(buffer)) != -1;) {
                count += size;
                if (count > artifact.maxBytes()) throw new IOException("Artifact exceeds its pinned byte limit");
                digest.update(buffer, 0, size);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256()))
            throw new IOException("Artifact SHA-256 differs from the pinned bytes; supply the exact verified artifact");
    }

    private static void copyBounded(Path source, Path target, long limit) throws IOException {
        try (InputStream input = Files.newInputStream(source)) { copy(input, target, limit, Long.MAX_VALUE); }
    }

    private static void download(Artifact artifact, Path target) throws IOException {
        URI source = artifact.source();
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        for (int redirects = 0; redirects <= 5; redirects++) {
            var connection = (HttpURLConnection) source.toURL().openConnection();
            connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Taxonomy-pinned-product-verifier");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Artifact redirect lacks a location");
                    URI next = source.resolve(location);
                    if (!"https".equals(next.getScheme()) || next.getHost() == null || next.getUserInfo() != null)
                        throw new IOException("Artifact redirect must retain HTTPS without credentials");
                    source = next;
                    if (System.nanoTime() > deadline) throw new IOException("Artifact download deadline exceeded");
                    continue;
                }
                if (status != 200) throw new IOException("Pinned artifact download returned HTTP " + status + "; preseed verified bytes with --local");
                if (connection.getContentLengthLong() > artifact.maxBytes()) throw new IOException("Artifact exceeds its pinned byte limit");
                try (InputStream input = connection.getInputStream()) { copy(input, target, artifact.maxBytes(), deadline); }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many artifact download redirects");
    }

    private static void copy(InputStream input, Path target, long limit, long deadline) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65_536]; long count = 0;
            for (int size; (size = input.read(buffer)) != -1;) {
                count += size;
                if (count > limit) throw new IOException("Artifact exceeds its pinned byte limit");
                if (System.nanoTime() > deadline) throw new IOException("Artifact download deadline exceeded");
                output.write(buffer, 0, size);
            }
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(Map<?, ?> values, String key) {
        require(values.get(key) instanceof String, "Missing product artifact " + key);
        return (String) values.get(key);
    }
    private static long wholeNumber(Object value, String field) {
        require(value instanceof Number, "Missing numeric " + field);
        try { return new java.math.BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid integer " + field, invalid);
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
