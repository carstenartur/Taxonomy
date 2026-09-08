import com.taxonomy.exchange.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Uses the actual packaged Taxonomy codecs. Product execution is performed by the companion runner. */
public class InteroperabilityProductProbe {
    public static void main(String[] args) throws Exception {
        String profile = args[0], action = args[1];
        ExchangeDocument before = read(profile, Path.of(args[2]));
        if (action.equals("export")) {
            byte[] output = profile.equals("reqif") ? new ReqifExchangeCodec().write(before) : new ArchiMateExchangeCodec().write(before);
            Files.write(Path.of(args[3]), output); read(profile, Path.of(args[3]));
        } else if (action.equals("compare")) {
            var after = read(profile, Path.of(args[3]));
            var expected = semantics(profile, before); var actual = semantics(profile, after);
            if (!expected.equals(actual)) throw new AssertionError("Product changed the declared semantic subset: expected=" + expected + "; actual=" + actual);
            var losses = profile.equals("archimate") ? compareConnectionGeometry(before, after) : List.<MappingLoss>of();
            System.out.println("PRODUCT_MAPPING_LOSSES " + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(losses));
        } else throw new IllegalArgumentException(action);
        System.out.println(profile + " " + action + ": supported semantic subset verified");
    }
    private static ExchangeDocument read(String profile, Path path) throws Exception {
        return profile.equals("reqif") ? new ReqifExchangeCodec().read(Files.readAllBytes(path), null, true)
                : new ArchiMateExchangeCodec().read(Files.readAllBytes(path), null, true);
    }
    private static Map<String, Object> semantics(String profile, ExchangeDocument document) {
        Map<String, Object> result = new TreeMap<>();
        for (Artifact artifact : document.artifacts()) {
            Map<String, String> attributes = new TreeMap<>();
            if (profile.equals("reqif")) artifact.attributes().forEach((key, value) -> attributes.put(artifact.extensions().getOrDefault("definition:" + key, key), value.startsWith("<") ? ExchangeXml.semantic(value) : value));
            // Product-owned declaration IDs are separate from semantic property names and values.
            if (profile.equals("archimate")) attributes.putAll(properties(artifact.attributes(), artifact.extensions()));
            result.put(artifact.kind() + ":" + artifact.id(), List.of(artifact.title(), artifact.text(), profile.equals("reqif") ? "" : artifact.type(),
                    artifact.extensions().getOrDefault("canonicalType", ""), attributes));
            if (profile.equals("archimate") && artifact.kind() == ArtifactKind.VIEW) {
                var connections = new TreeMap<String, Object>();
                var xml = ExchangeXml.parse(artifact.extensions().get("connectionsXml").getBytes(StandardCharsets.UTF_8));
                for (var connection : ExchangeXml.children(xml.getDocumentElement())) {
                    connections.put(connection.getAttribute("identifier"), List.of(connection.getAttribute("source"), connection.getAttribute("target"), connection.getAttribute("relationshipRef")));
                }
                result.put("connections:" + artifact.id(), connections);
            }
        }
        List<String> relations = document.relations().stream().map(r -> r.source() + " -> " + r.target() + (profile.equals("reqif") ? "" : " : " + r.type())).sorted().toList();
        result.put("relations", relations);
        if (profile.equals("archimate")) for (var relation : document.relations())
            result.put("relationProperties:" + relation.id(), List.of(relation.extensions().getOrDefault("canonicalType", ""), properties(relation.attributes(), relation.extensions())));
        Map<String, Placement> placements = new HashMap<>(); document.placements().forEach(p -> placements.put(p.id(), p));
        result.put("hierarchy", document.placements().stream().map(p -> {
            List<String> path = new ArrayList<>(); Placement cursor = p;
            while (cursor != null) { path.add(cursor.artifactId() + ":" + cursor.position()); cursor = placements.get(cursor.parentId()); }
            Collections.reverse(path);
            String layout = profile.equals("reqif") ? "" : List.of("x", "y", "w", "h").stream().map(k -> k + "=" + p.attributes().getOrDefault(k, "")).toList().toString();
            return p.containerId() + ":" + path + layout;
        }).sorted().toList());
        return result;
    }
    private static Map<String, String> properties(Map<String, String> attributes, Map<String, String> extensions) {
        var result = new TreeMap<String, String>();
        attributes.forEach((id, value) -> {
            String name = extensions.getOrDefault("definition:" + id, id);
            // Taxonomy adds these precision properties on first export. Their resolved canonical values are compared separately above.
            if (Set.of("Taxonomy.ElementType", "Taxonomy.RelationType").contains(name)) return;
            var property = ExchangeXml.parse(value.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
            property.removeAttribute("propertyDefinitionRef");
            result.put(name, ExchangeXml.semantic(ExchangeXml.xml(property)));
        });
        return result;
    }
    private record Point(String kind, java.math.BigDecimal x, java.math.BigDecimal y) {}
    private static Map<String, List<Point>> geometry(ExchangeDocument document) {
        var result = new TreeMap<String, List<Point>>();
        for (var view : document.artifacts()) if (view.kind() == ArtifactKind.VIEW) {
            var xml = ExchangeXml.parse(view.extensions().get("connectionsXml").getBytes(StandardCharsets.UTF_8));
            for (var connection : ExchangeXml.children(xml.getDocumentElement())) {
                var points = new ArrayList<Point>();
                for (var point : ExchangeXml.children(connection)) if (Set.of("sourceAttachment", "targetAttachment", "bendpoint").contains(point.getLocalName()))
                    points.add(new Point(point.getLocalName(), new java.math.BigDecimal(point.getAttribute("x")).stripTrailingZeros(), new java.math.BigDecimal(point.getAttribute("y")).stripTrailingZeros()));
                result.put(view.id() + "/" + connection.getAttribute("identifier"), points);
            }
        }
        return result;
    }
    private static List<MappingLoss> compareConnectionGeometry(ExchangeDocument before, ExchangeDocument after) {
        var original = geometry(before); var returned = geometry(after); var losses = new ArrayList<MappingLoss>();
        if (!original.keySet().equals(returned.keySet())) throw new AssertionError("Product changed view connection identities");
        original.forEach((id, points) -> {
            var actual = returned.get(id); if (points.equals(actual)) return;
            // Observed in the installed Archi 5.10.0 importer/exporter, not a claim of lossless layout equivalence.
            boolean known = points.size() == 2 && actual.size() == 1 && points.getFirst().kind().equals("sourceAttachment")
                    && points.getLast().kind().equals("targetAttachment") && actual.getFirst().kind().equals("bendpoint");
            if (known) {
                var a = points.getFirst(); var b = points.getLast(); var point = actual.getFirst();
                var two = java.math.BigDecimal.valueOf(2);
                known = (a.x().compareTo(b.x()) == 0 || a.y().compareTo(b.y()) == 0)
                        && point.x().compareTo(a.x().add(b.x()).divide(two)) == 0 && point.y().compareTo(a.y().add(b.y()).divide(two)) == 0;
            }
            if (!known) throw new AssertionError("Unrecognized product layout change for " + id + ": " + points + " -> " + actual);
            losses.add(new MappingLoss(id, "attachments", "ARCHI_ATTACHMENTS_TO_BENDPOINT", LossDisposition.TRANSFORMED,
                    "Archi 5.10.0 replaces the two straight-line attachment points with their midpoint bendpoint: " + points + " -> " + actual));
        });
        return losses;
    }
}
