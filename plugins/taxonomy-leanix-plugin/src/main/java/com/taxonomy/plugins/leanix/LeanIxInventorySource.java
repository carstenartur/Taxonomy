package com.taxonomy.plugins.leanix;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.taxonomy.extension.api.inventory.InventorySourceExtension;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded SAP LeanIX query-profile 1. No mutations, source inference, selection or canonical writes. */
public final class LeanIxInventorySource implements InventorySourceExtension {
    private static final long serialVersionUID = 1L;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(65536).maxNumberLength(20).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Override public String id() { return "leanix"; }
    @Override public String displayName() { return "SAP LeanIX inventory (read-only)"; }
    @Override public String description() { return "Application, IT component and lifecycle source observations; no suitability verdict or remote write"; }

    @Override public Observation read(Request request, ReadAccess access) {
        Objects.requireNonNull(request); Objects.requireNonNull(access); LeanIxQueries.validate(request);
        Instant started = Instant.now(); long startedNanos = System.nanoTime();
        String api = credential(access);
        String basic = Base64.getEncoder().encodeToString(("apitoken:" + api).getBytes(StandardCharsets.UTF_8));
        try (var http = new LeanIxHttp(request, access, startedNanos)) {
            long tokenStarted = System.nanoTime();
            JsonNode tokenResponse = parse(http.post(LeanIxHttp.Endpoint.TOKEN, "grant_type=client_credentials", "Basic " + basic));
            if (!tokenResponse.path("token_type").asText().equalsIgnoreCase("bearer") || tokenResponse.path("expired").asBoolean(false))
                throw new ReadFailure("TOKEN_RESPONSE");
            String token = requiredText(tokenResponse.get("access_token"), 8192);
            if (!token.matches("[A-Za-z0-9._~+/=-]{16,8192}")) throw new ReadFailure("TOKEN_RESPONSE");
            JsonNode expiry = tokenResponse.get("expires_in");
            if (expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToInt()) throw new ReadFailure("TOKEN_RESPONSE");
            http.tokenLifetime(expiry.intValue(), tokenStarted);
            var secrets = List.of(api, token, basic);
            var objects = new ArrayList<ObservedObject>();
            var objectIds = new HashSet<ExternalKey>();
            String query = LeanIxQueries.objects(request, !request.selectedIds().isEmpty());
            if (!request.selectedIds().isEmpty()) {
                for (String id : request.selectedIds().stream().sorted().toList()) {
                    JsonNode object = execute(http, query, Map.of("id", id), token, secrets).get("factSheet");
                    if (object == null || !object.isObject() || !id.equals(requiredText(object.get("id"), 2048)))
                        throw new ReadFailure("SELECTED_OBJECT_UNAVAILABLE");
                    addObject(request, object, objects, objectIds);
                }
            } else {
                String cursor = null; var cursors = new HashSet<String>();
                do {
                    var vars = new LinkedHashMap<String, Object>(); vars.put("first", request.limits().pageSize()); vars.put("after", cursor);
                    vars.put("filter", Map.of("facetFilters", List.of(Map.of("facetKey", "FactSheetTypes", "operator", "OR",
                            "keys", request.projections().stream().map(TypeProjection::sourceType).toList()))));
                    JsonNode connection = execute(http, query, vars, token, secrets).get("allFactSheets");
                    for (JsonNode edge : edges(connection, request.limits().pageSize())) addObject(request, edge.get("node"), objects, objectIds);
                    cursor = next(connection, cursors);
                } while (cursor != null);
            }
            var relations = new ArrayList<ObservedRelation>(); var relationIds = new HashSet<ExternalKey>();
            if (request.readUsageRelations()) for (ObservedObject owner : objects) {
                if (!"Application".equals(owner.sourceType())) continue;
                String cursor = null; var cursors = new HashSet<String>();
                do {
                    var vars = new LinkedHashMap<String, Object>(); vars.put("id", owner.key().id());
                    vars.put("first", request.limits().pageSize()); vars.put("after", cursor);
                    JsonNode parent = execute(http, LeanIxQueries.RELATIONS, vars, token, secrets).get("factSheet");
                    if (parent == null || !parent.isObject() || !owner.key().id().equals(requiredText(parent.get("id"), 2048))
                            || !owner.revision().equals(revision(parent))) throw new ReadFailure("SOURCE_CHANGED");
                    JsonNode connection = parent.get("relApplicationToITComponent");
                    for (JsonNode edge : edges(connection, request.limits().pageSize())) {
                        if (relations.size() >= request.limits().maxRelations()) throw new ReadFailure("READ_LIMIT");
                        JsonNode value = edge.get("node"); requireObject(value);
                        ExternalKey key = key(request, "relation", requiredText(value.get("id"), 2048));
                        if (!relationIds.add(key)) throw new ReadFailure("DUPLICATE_ID");
                        JsonNode target = value.get("factSheet"); requireObject(target);
                        if (!"ITComponent".equals(requiredText(target.get("type"), 200))) throw new ReadFailure("UNEXPECTED_RELATION_TARGET");
                        relations.add(new ObservedRelation(key.id(), key, owner.key(), key(request, "factsheet", requiredText(target.get("id"), 2048)),
                                "relApplicationToITComponent", "USES", Map.of("activeFrom", field(value, "activeFrom", "activeFrom"),
                                "activeUntil", field(value, "activeUntil", "activeUntil"))));
                    }
                    cursor = next(connection, cursors);
                } while (cursor != null);
            }
            http.checkTime();
            var limitations = new HashSet<>(Set.of("NO_ATOMIC_SNAPSHOT", "NO_DELETION_AUTHORITY", "NO_AVAILABILITY_OR_SUITABILITY_VERDICT",
                    "WORKSPACE_BINDING_OPERATOR_DECLARED", "NO_VENDOR_SUPPORT_OR_TRANSFORMATION_PLANS"));
            if (!request.readUsageRelations()) limitations.add("RELATIONS_NOT_REQUESTED");
            if (relations.stream().anyMatch(r -> !objectIds.contains(r.target()))) limitations.add("RELATION_TARGETS_OUTSIDE_OBSERVATION");
            return new Observation(request, started, Instant.now(), Completeness.SELECTED_SCOPE_READ_NON_ATOMIC,
                    http.requests(), http.bytes(), objects, relations, limitations);
        }
    }
    private void addObject(Request request, JsonNode node, List<ObservedObject> objects, Set<ExternalKey> ids) {
        requireObject(node);
        if (objects.size() >= request.limits().maxObjects()) throw new ReadFailure("READ_LIMIT");
        String type = requiredText(node.get("type"), 200);
        TypeProjection p = request.projections().stream().filter(v -> v.sourceType().equals(type)).findFirst()
                .orElseThrow(() -> new ReadFailure("UNSUPPORTED_SOURCE_TYPE"));
        ExternalKey key = key(request, "factsheet", requiredText(node.get("id"), 2048));
        if (!ids.add(key)) throw new ReadFailure("DUPLICATE_ID");
        Field subtype = p.subtypeField() == null ? Field.missing(State.NOT_SUPPORTED, "subtype") : field(node, "inventorySubtype", p.subtypeField());
        var fields = new TreeMap<String, Field>(); fields.put("name", field(node, "name", "name")); fields.put("description", field(node, "description", "description"));
        int i = 0;
        for (var field : LeanIxQueries.fields(p)) fields.put(field.getKey(), field(node, "inventoryScalar" + i++, field.getValue()));
        JsonNode lifecycle = node.get("lifecycle");
        State lifecycleState = lifecycle == null ? State.ABSENT : lifecycle.isNull() ? State.NULL : State.PRESENT;
        var phases = new ArrayList<LifecyclePhase>();
        if (lifecycleState == State.PRESENT) {
            requireObject(lifecycle); JsonNode list = lifecycle.get("phases");
            if (list == null || !list.isArray() || list.size() > 32) throw new ReadFailure("LIFECYCLE_SHAPE");
            for (JsonNode phase : list) {
                requireObject(phase);
                phases.add(new LifecyclePhase(field(phase, "phase", "lifecycle.phases.phase"),
                        field(phase, "startDate", "lifecycle.phases.startDate"), AssertionKind.DECLARED));
            }
        }
        objects.add(new ObservedObject(key, type, subtype, p.classify(subtype), revision(node), fields, lifecycleState, phases));
    }
    private JsonNode execute(LeanIxHttp http, String query, Map<String, ?> variables, String token, List<String> secrets) {
        String body;
        try { body = json.writeValueAsString(Map.of("query", query, "variables", variables)); }
        catch (IOException e) { throw new ReadFailure("REQUEST_ENCODING"); }
        JsonNode root = parse(http.post(LeanIxHttp.Endpoint.GRAPHQL, body, "Bearer " + token));
        JsonNode errors = root.get("errors");
        if (errors != null && (!errors.isArray() || !errors.isEmpty())) throw new ReadFailure("GRAPHQL_ERRORS");
        rejectReflection(root, secrets); JsonNode data = root.get("data"); requireObject(data); return data;
    }
    private JsonNode parse(byte[] content) {
        try { JsonNode value = json.readTree(content); requireObject(value); return value; }
        catch (IOException e) { throw new ReadFailure("INVALID_JSON"); }
    }
    private static JsonNode edges(JsonNode connection, int maximum) {
        requireObject(connection); JsonNode edges = connection.get("edges");
        if (edges == null || !edges.isArray()) throw new ReadFailure("CONNECTION_SHAPE");
        if (edges.size() > maximum) throw new ReadFailure("READ_LIMIT");
        for (JsonNode edge : edges) { requireObject(edge); requireObject(edge.get("node")); }
        return edges;
    }
    private static String next(JsonNode connection, Set<String> seen) {
        JsonNode info = connection.get("pageInfo"); requireObject(info);
        JsonNode more = info.get("hasNextPage");
        if (more == null || !more.isBoolean()) throw new ReadFailure("CONNECTION_SHAPE");
        if (!more.booleanValue()) return null;
        String cursor = requiredText(info.get("endCursor"), 8192);
        if (!seen.add(cursor)) throw new ReadFailure("CURSOR_REPEATED");
        return cursor;
    }
    private static Field field(JsonNode node, String wireName, String sourceField) {
        JsonNode value = node.get(wireName);
        if (value == null) return Field.missing(State.ABSENT, sourceField);
        if (value.isNull()) return Field.missing(State.NULL, sourceField);
        if (!value.isTextual()) throw new ReadFailure("EXPECTED_TEXT_FIELD");
        return Field.text(value.textValue(), sourceField);
    }
    private static String revision(JsonNode node) {
        JsonNode revision = node.get("rev");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 0)
            throw new ReadFailure("REVISION_UNAVAILABLE");
        return revision.asText();
    }
    private static ExternalKey key(Request request, String namespace, String id) { return new ExternalKey(request.instanceId(), namespace, id); }
    private static void requireObject(JsonNode node) { if (node == null || !node.isObject()) throw new ReadFailure("RESPONSE_SHAPE"); }
    private static String requiredText(JsonNode node, int maximum) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maximum
                || node.textValue().chars().anyMatch(Character::isISOControl)) throw new ReadFailure("RESPONSE_SHAPE");
        return node.textValue();
    }
    private static String credential(ReadAccess access) {
        char[] value = null;
        try {
            value = access.credential();
            if (value == null || value.length < 16 || value.length > 8192) throw new ReadFailure("CREDENTIAL_UNAVAILABLE");
            String text = new String(value);
            if (text.chars().anyMatch(Character::isISOControl)) throw new ReadFailure("CREDENTIAL_UNAVAILABLE");
            return text;
        } catch (RuntimeException e) { throw new ReadFailure("CREDENTIAL_UNAVAILABLE"); }
        finally { if (value != null) Arrays.fill(value, '\0'); }
    }
    private static void rejectReflection(JsonNode node, List<String> secrets) {
        if (node.isTextual()) rejectText(node.textValue(), secrets);
        else if (node.isObject()) node.properties().forEach(e -> { rejectText(e.getKey(), secrets); rejectReflection(e.getValue(), secrets); });
        else if (node.isArray()) node.forEach(v -> rejectReflection(v, secrets));
    }
    private static void rejectText(String value, List<String> secrets) {
        for (int i = 0; i < 3; i++) {
            for (String secret : secrets) if (value.contains(secret)) throw new ReadFailure("CREDENTIAL_REFLECTION");
            String decoded;
            try { decoded = URLDecoder.decode(value, StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { return; }
            if (decoded.equals(value)) return;
            value = decoded;
        }
    }
}
