package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

/** The same executable behavior checks are also run by the ordinary JUnit suite. */
public final class SparxDiagramHandoffChecks {
    private static final UUID NAMESPACE = UUID.fromString("2da4e8f2-afc4-4f5b-87ae-e1b295a913d4");
    private SparxDiagramHandoffChecks() {}
    public static void run() throws Exception {
        Class<?> service;
        try { service = Class.forName("com.taxonomy.export.SparxDiagramHandoff"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Current-view Sparx handoff is not implemented", missing); }
        var method = service.getMethod("build", DiagramModel.class, UUID.class);
        var model = graph("Original <ä> \" & emoji 🐦", "CONSUMES");
        var parts = unzip((byte[]) method.invoke(null, model, NAMESPACE));
        require(parts.keySet().equals(Set.of("architecture.xmi", "manifest.json", "README.txt")), "Complete handoff bundle");
        var decoded = new SparxXmiCodec().read(parts.get("architecture.xmi"), null, false);
        require(decoded.artifacts().size() == 2 && decoded.relations().size() == 1, "Exact membership");
        require(decoded.artifacts().stream().anyMatch(a -> a.title().equals(model.nodes().getFirst().label())), "Full escaped Unicode label");
        require(decoded.artifacts().stream().allMatch(a -> a.extensions().containsKey("taxonomy:sourceId")), "Source identity evidence");
        require(decoded.artifacts().stream().noneMatch(a -> a.attributes().containsKey("tag:taxonomy.id")), "Never forge persisted integration identities");
        var first = decoded.artifacts().stream().filter(a -> "n1".equals(a.extensions().get("taxonomy:sourceId"))).findFirst().orElseThrow();
        require(decoded.relations().getFirst().source().equals(first.id()), "Direction preserved");
        require(decoded.relations().getFirst().extensions().get("taxonomy:sourceType").equals("CONSUMES"), "Exact relation type evidence");
        String manifest = new String(parts.get("manifest.json"), StandardCharsets.UTF_8);
        require(manifest.contains("fresh-copy") && manifest.contains("not-certified") && manifest.contains("sha256"), "Scope, product status and checksum");
        require(manifest.contains("display-only") && manifest.contains("sourceId"), "Layout loss and original IDs");
        byte[] again = (byte[]) method.invoke(null, model, NAMESPACE);
        require(Arrays.equals(again, (byte[]) method.invoke(null, model, NAMESPACE)), "Deterministic for frozen graph and namespace");
        var renamed = new SparxXmiCodec().read(unzip((byte[]) method.invoke(null, graph("Renamed", "CONSUMES"), NAMESPACE)).get("architecture.xmi"), null, false);
        require(new HashSet<>(decoded.artifacts().stream().map(a->a.id()).toList()).equals(new HashSet<>(renamed.artifacts().stream().map(a->a.id()).toList())), "Rename preserves scoped IDs");
        var other = new SparxXmiCodec().read(unzip((byte[]) method.invoke(null, model, UUID.randomUUID())).get("architecture.xmi"), null, false);
        require(Collections.disjoint(decoded.artifacts().stream().map(a->a.id()).toList(), other.artifacts().stream().map(a->a.id()).toList()), "Fresh import scope never collides");
        for (String type : List.of("SUPPORTS", "USES", "PRODUCES", "FULFILLS", "ASSIGNED_TO", "REQUIRES")) {
            var transformed = unzip((byte[]) method.invoke(null, graph("Typed graph", type), NAMESPACE));
            require(new SparxXmiCodec().read(transformed.get("architecture.xmi"), null, false).relations().size() == 1, "No silent relation omission");
            require(new String(transformed.get("manifest.json"), StandardCharsets.UTF_8).contains("TRANSFORMED"), "Explicit projection loss for " + type);
        }
        for (String type : List.of("REALIZES", "CONSUMES", "DEPENDS_ON", "COMMUNICATES_WITH", "CONTAINS", "RELATED_TO"))
            require(new SparxXmiCodec().read(unzip((byte[]) method.invoke(null, graph("Mapped", type), NAMESPACE)).get("architecture.xmi"), null, false).relations().size() == 1, "Known relation " + type);
        reject(method, graph("Unknown relation", "MADE_UP"));
        reject(method, new DiagramModel("Missing endpoint", model.nodes(), List.of(new DiagramEdge("e", "n1", "missing", "CONSUMES", .5)), model.layout()));
        reject(method, new DiagramModel("Duplicates", List.of(model.nodes().getFirst(),model.nodes().getFirst()), List.of(), model.layout()));
        reject(method, new DiagramModel("Invalid score", List.of(new DiagramNode("n", "bad", "Capabilities", Double.NaN, false, 0)), List.of(), model.layout()));
        reject(method, new DiagramModel("Unknown type", List.of(new DiagramNode("n", "bad", "MADE_UP", .5, false, 0)), List.of(), model.layout()));
        System.out.println("SPARX_HANDOFF_CHECKS_OK");
    }
    private static DiagramModel graph(String title, String relation) {
        return new DiagramModel("Working view", List.of(new DiagramNode("n1", title, "Business Processes", .9, true, 1), new DiagramNode("n2", "Information", "Information Products", .8, false, 2)), List.of(new DiagramEdge("edge", "n1", "n2", relation, .7)), new DiagramLayout("LR", true));
    }
    private static void reject(java.lang.reflect.Method m, DiagramModel model) throws Exception {
        try { m.invoke(null, model, NAMESPACE); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            require(failure.getCause() instanceof IllegalArgumentException, "Explicit input validation error"); return;
        }
        throw new AssertionError("Invalid model unexpectedly exported: " + model.title());
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception { run(); }
}
