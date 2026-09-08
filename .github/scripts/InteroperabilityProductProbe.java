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
            var expected = semantics(profile, before); var actual = semantics(profile, read(profile, Path.of(args[3])));
            if (!expected.equals(actual)) throw new AssertionError("Product changed the declared semantic subset: expected=" + expected + "; actual=" + actual);
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
                    var geometry = new ArrayList<String>();
                    for (var point : ExchangeXml.children(connection)) if (Set.of("sourceAttachment", "targetAttachment", "bendpoint").contains(point.getLocalName()))
                        geometry.add(point.getLocalName() + ":" + point.getAttribute("x") + ":" + point.getAttribute("y"));
                    connections.put(connection.getAttribute("identifier"), List.of(connection.getAttribute("source"), connection.getAttribute("target"), connection.getAttribute("relationshipRef"), geometry));
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
}
