package com.taxonomy.interop.alfabet;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.taxonomy.interop.alfabet.AlfabetHttpTransport.problem;
import static com.taxonomy.interop.alfabet.AlfabetHttpTransport.requireTime;

/**
 * Preparatory read adapter in the existing interoperability module. Explicit invocation only:
 * no startup calls, controller, scheduler, canonical writes, implicit classification or publication.
 * Callers must authorize the profile against the existing repository/organization context first.
 */
public final class AlfabetReadClient {
    private final Function<String, String> credentials;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65536).maxNumberLength(20).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Supply a server-side secret resolver, e.g. Environment::getProperty; never a browser request map. */
    public AlfabetReadClient(Function<String, String> credentials) { this.credentials = java.util.Objects.requireNonNull(credentials); }

    public AlfabetSnapshot read(AlfabetReadProfile profile) {
        Instant started = Instant.now();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.limits().timeoutSeconds());
        String secret = credential(profile.authentication().secretEnvironmentVariable());
        var reports = new ArrayList<AlfabetSnapshot.ReportData>();
        int pages = 0, totalRows = 0; long totalBytes = 0;
        try (var transport = new AlfabetHttpTransport(profile)) {
            String token = secret;
            if (profile.authentication().grant() != AlfabetReadProfile.Grant.BEARER) {
                String principal = credential(profile.authentication().principalEnvironmentVariable());
                boolean client = profile.authentication().grant() == AlfabetReadProfile.Grant.CLIENT_CREDENTIALS;
                String form = client ? "grant_type=client_credentials&client_id=" : "grant_type=password&username=";
                form += encode(principal) + (client ? "&client_secret=" : "&password=") + encode(secret);
                byte[] bytes = transport.post(AlfabetHttpTransport.Endpoint.TOKEN, form, null,
                        Math.min(32768, profile.limits().maxResponseBytes()), deadline);
                totalBytes += bytes.length;
                JsonNode authentication = parse(bytes);
                if (!authentication.path("token_type").asText().equalsIgnoreCase("bearer")) throw problem("ALFABET_AUTH_RESPONSE");
                token = text(authentication.get("access_token"), 8192);
                JsonNode expires = authentication.get("expires_in");
                if (expires == null || !expires.isIntegralNumber() || !expires.canConvertToInt() || expires.intValue() < 2)
                    throw problem("ALFABET_AUTH_RESPONSE");
                deadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(expires.intValue() - 1, 120)));
            }
            if (!token.matches("[A-Za-z0-9._~+/=-]{1,8192}")) throw problem("ALFABET_CREDENTIAL_UNAVAILABLE");
            for (var report : profile.reports()) {
                var rows = new ArrayList<AlfabetSnapshot.Row>();
                var identities = new HashSet<Object>();
                int offset = 0;
                while (true) {
                    requireTime(deadline);
                    if (pages >= profile.limits().maxPages() || totalBytes >= profile.limits().maxTotalBytes())
                        throw problem("ALFABET_READ_LIMIT");
                    var body = new LinkedHashMap<String, Object>();
                    body.put("CurrentProfile", profile.currentProfile()); body.put("Report", report.name());
                    body.put("ReportResult", "DataSet"); body.put("EmptyValues", true);
                    body.put("Limit", profile.limits().pageSize()); body.put("Offset", offset);
                    int bound = (int) Math.min(profile.limits().maxResponseBytes(), profile.limits().maxTotalBytes() - totalBytes);
                    byte[] bytes = transport.post(AlfabetHttpTransport.Endpoint.OBJECTS, write(body), token, bound, deadline);
                    pages++; totalBytes += bytes.length;
                    JsonNode response = parse(bytes);
                    rejectReflection(response, List.of(secret, token));
                    if (!report.name().equals(text(response.get("Name"), 200)) || !response.path("Objects").isArray())
                        throw problem("ALFABET_REPORT_SHAPE");
                    JsonNode values = response.get("Objects");
                    if (values.size() > profile.limits().pageSize() || totalRows + values.size() > profile.limits().maxRows())
                        throw problem("ALFABET_READ_LIMIT");
                    // Count is NOT assumed to be a global total, a stable snapshot version or a deletion warrant.
                    for (JsonNode value : values) {
                        requireTime(deadline);
                        var row = row(report, value);
                        Object identity = report.kind() == AlfabetReadProfile.Kind.OBJECT ? row.refStr() : row;
                        if (!identities.add(identity)) throw problem("ALFABET_DUPLICATE_ROW");
                        rows.add(row);
                    }
                    totalRows += values.size(); offset += values.size();
                    if (values.size() < profile.limits().pageSize()) break;
                }
                reports.add(new AlfabetSnapshot.ReportData(report.name(), report.kind(), rows));
            }
        }
        requireTime(deadline);
        return new AlfabetSnapshot(profile.scope(), profile.mappingVersion(), started, Instant.now(), pages, totalBytes, reports);
    }

    private AlfabetSnapshot.Row row(AlfabetReadProfile.Report report, JsonNode value) {
        String className = text(value.get("ClassName"), 200), ref = text(value.get("RefStr"), 2048);
        if (!report.className().equals(className)) throw problem("ALFABET_UNEXPECTED_CLASS");
        JsonNode nested = value.get("NestedObjects");
        if (nested != null && !nested.isNull() && !(nested.isContainerNode() && nested.isEmpty()))
            throw problem("ALFABET_UNSUPPORTED_NESTED_OBJECTS");
        JsonNode values = value.get("Values");
        if (values == null || !values.isObject() || values.size() > 256) throw problem("ALFABET_REPORT_SHAPE");
        var columns = new TreeMap<String, String>(); var nulls = new HashSet<String>();
        values.properties().forEach(entry -> {
            String column = entry.getKey();
            if (column.isBlank() || column.length() > 256 || column.chars().anyMatch(Character::isISOControl))
                throw problem("ALFABET_REPORT_SHAPE");
            if (entry.getValue().isNull()) nulls.add(column);
            else if (entry.getValue().isTextual()) columns.put(column, entry.getValue().textValue());
            else throw problem("ALFABET_REPORT_SHAPE");
        });
        var fields = new TreeMap<String, String>();
        report.fields().forEach((name, column) -> { if (columns.containsKey(column)) fields.put(name, columns.get(column)); });
        if (report.kind() == AlfabetReadProfile.Kind.RELATION)
            for (String endpoint : List.of("sourceRef", "targetRef"))
                if (!fields.containsKey(endpoint) || fields.get(endpoint).isBlank()) throw problem("ALFABET_RELATION_ENDPOINT");
        return new AlfabetSnapshot.Row(className, ref, fields, columns, nulls);
    }
    private JsonNode parse(byte[] bytes) {
        try {
            JsonNode value = json.readTree(bytes);
            if (value == null || !value.isObject()) throw problem("ALFABET_REPORT_SHAPE");
            return value;
        } catch (IOException failure) { throw problem("ALFABET_INVALID_JSON"); }
    }
    private String write(Object body) {
        try { return json.writeValueAsString(body); }
        catch (IOException failure) { throw problem("ALFABET_REQUEST_INVALID"); }
    }
    private String credential(String reference) {
        String value;
        try { value = credentials.apply(reference); }
        catch (RuntimeException failure) { throw problem("ALFABET_CREDENTIAL_UNAVAILABLE"); }
        if (value == null || value.isBlank() || value.length() > 8192 || value.chars().anyMatch(Character::isISOControl))
            throw problem("ALFABET_CREDENTIAL_UNAVAILABLE");
        return value;
    }
    private static String text(JsonNode value, int max) {
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max
                || value.textValue().chars().anyMatch(Character::isISOControl)) throw problem("ALFABET_REPORT_SHAPE");
        return value.textValue();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void rejectReflection(JsonNode node, List<String> secrets) {
        if (node.isTextual()) {
            rejectReflectedText(node.textValue(), secrets);
        } else if (node.isObject()) {
            node.properties().forEach(entry -> {
                rejectReflectedText(entry.getKey(), secrets);
                rejectReflection(entry.getValue(), secrets);
            });
        } else if (node.isArray()) node.forEach(child -> rejectReflection(child, secrets));
    }
    private static void rejectReflectedText(String text, List<String> secrets) {
        // Jackson has already decoded JSON escapes. Also cover percent-encoded response diagnostics.
        for (int pass = 0; pass < 3; pass++) {
            for (String secret : secrets) if (text.contains(secret)) throw problem("ALFABET_CREDENTIAL_REFLECTION");
            String decoded;
            try { decoded = URLDecoder.decode(text, StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalidEscape) { return; }
            if (decoded.equals(text)) return;
            text = decoded;
        }
    }

}
