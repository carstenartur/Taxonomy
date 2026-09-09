package com.taxonomy.export;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioLoss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Executable metadata/loss contract shared by the OPC package and downloadable handoff bundle. */
public final class VisioHandoffProfile {
    public static final String ID = "visio-2012-opc-supported-subset-v2";
    static final int MAX_XML_BYTES = 32 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final String PROFILE = loadProfile();

    private VisioHandoffProfile() { }

    public static String profileJson() { return PROFILE; }

    public static String manifest(VisioDocument document) {
        List<Object> pages = new ArrayList<>();
        List<VisioLoss> losses = new ArrayList<>(document.getLosses());
        for (var page : document.getPages()) {
            List<Object> shapes = new ArrayList<>();
            for (var shape : page.getShapes()) {
                shapes.add(Map.of("shapeId", shape.getId(), "label", shape.getText(), "properties", shape.getProperties()));
                if (!shape.getProperties().containsKey("taxonomy.id")) losses.add(new VisioLoss("shape", page.getId() + ":" + shape.getId(),
                        "semanticIdentity", "OMITTED", "Direct Visio model contains no original Taxonomy identity."));
            }
            List<Object> relationships = new ArrayList<>();
            long connectorId = page.getShapes().stream().mapToLong(s -> Long.parseLong(s.getId())).max().orElse(0);
            for (var connector : page.getConnects()) {
                relationships.add(Map.of("shapeId", Long.toString(++connectorId), "sourceShapeId", connector.getFromShape(),
                        "targetShapeId", connector.getToShape(), "label", connector.getRelationType(), "properties", connector.getProperties()));
                if (!connector.getProperties().containsKey("taxonomy.id")) losses.add(new VisioLoss("connector", page.getId() + ":" + connectorId,
                        "semanticIdentity", "OMITTED", "Direct Visio model contains no original Taxonomy relationship identity."));
            }
            pages.add(Map.of("pageId", page.getId(), "name", page.getName(), "shapes", shapes, "relationships", relationships));
        }
        losses.sort(Comparator.comparing(VisioLoss::scope).thenComparing(VisioLoss::id)
                .thenComparing(VisioLoss::field).thenComparing(VisioLoss::kind).thenComparing(VisioLoss::rationale));
        return json(Map.of("schemaVersion", 1, "exportProfile", ID,
                "profileSha256", sha256(PROFILE.getBytes(StandardCharsets.UTF_8)),
                "authority", document.getProperties(), "pages", pages, "losses", losses,
                "certification", "EXPERIMENTAL_NOT_MICROSOFT_VISIO_CERTIFIED"));
    }

    static String bundleManifest(byte[] vsdx, String handoff) {
        try {
            return json(Map.of("schemaVersion", 1, "artifact", Map.of("file", "diagram.vsdx", "sha256", sha256(vsdx)),
                    "mappingProfile", Map.of("file", "mapping-profile.json", "sha256", sha256(PROFILE.getBytes(StandardCharsets.UTF_8))),
                    "handoff", JSON.readTree(handoff)));
        } catch (IOException e) {
            throw new IllegalStateException("Could not construct Visio manifest", e);
        }
    }

    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalArgumentException("Could not serialize Visio metadata", e); }
    }

    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String loadProfile() {
        try (var input = VisioHandoffProfile.class.getResourceAsStream("/visio/handoff-profile-v2.json")) {
            if (input == null) throw new IllegalStateException("Missing Visio profile");
            String profile = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!ID.equals(JSON.readTree(profile).path("profileVersion").asText())) throw new IllegalStateException("Visio profile version mismatch");
            return profile;
        } catch (IOException e) { throw new IllegalStateException("Could not load Visio profile", e); }
    }
}
