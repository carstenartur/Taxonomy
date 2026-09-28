package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Deterministic portable-profile contract; not a vendor compatibility certification. */
public final class ReqifPlanningContract {
    private static int assertions;
    public static void main(String[] args) { run(); System.out.println("ReqifPlanningContract: " + assertions + " assertions passed"); }
    public static void run() {
        assertions = 0;
        var goal = new PlanningEnvelope.Entry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2030"));
        var reference = new PlanningEnvelope.Entry("norm", "standard-reference", "1", "MANUAL", Map.of("identifier", "TEST-NORM", "edition", "2026", "section", "4.2"));
        var unknown = new PlanningEnvelope.Entry("unknown", "vendor-aspect", "17", "external", Map.of("opaque", "<not interpreted>"));
        String payload = PlanningEnvelope.write(List.of(goal, reference, unknown));
        equal(List.of(goal, reference, unknown), PlanningEnvelope.read(payload));
        var codec = new ReqifExchangeCodec();
        var original = document(List.of(requirement("R", payload)));
        byte[] bytes = codec.write(original);
        var read = codec.read(bytes, "1", true);
        var req = requirement(read, "R");
        equal(List.of(goal, reference, unknown), PlanningEnvelope.read(req.extensions().get(PlanningEnvelope.EXTENSION)));
        check(read.losses().stream().anyMatch(l -> l.code().equals("PLANNING_PROFILE_ENVELOPE") && l.disposition() == LossDisposition.PRESERVED_EXTENSION), "Missing honest loss report");
        String xml = new String(bytes, StandardCharsets.UTF_8);
        check(xml.contains(PlanningEnvelope.ATTRIBUTE), "Missing named attribute");
        check(!xml.contains("2030-01-01"), "Year was coerced by ReqIF");
        equal("Requirement text", req.text());
        String changed = PlanningEnvelope.write(List.of(new PlanningEnvelope.Entry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2032")), reference));
        var extensions = new java.util.TreeMap<>(req.extensions()); extensions.put(PlanningEnvelope.EXTENSION, changed);
        var replacement = new Artifact(req.id(), req.kind(), req.type(), req.title(), req.text(), req.attributes(), extensions);
        var updated = new ExchangeDocument(read.profile(), read.profileVersion(), read.externalVersion(), true, read.source(),
                read.artifacts().stream().map(a -> a.id().equals("R") ? replacement : a).toList(), read.relations(), read.placements(), read.metadata(), read.losses());
        var current = requirement(codec.read(codec.write(updated), "2", true), "R");
        equal("2032", PlanningEnvelope.read(current.extensions().get(PlanningEnvelope.EXTENSION)).getFirst().values().get("value"));
        // A local addition shares the existing native type without destroying that type's planning definition.
        var added = new java.util.ArrayList<>(updated.artifacts()); added.add(requirement("NEW", payload));
        var expanded = new ExchangeDocument(updated.profile(), updated.profileVersion(), "2", true, updated.source(), added,
                updated.relations(), updated.placements(), updated.metadata(), updated.losses());
        equal(3, PlanningEnvelope.read(requirement(codec.read(codec.write(expanded), "3", true), "NEW").extensions().get(PlanningEnvelope.EXTENSION)).size());
        String empty = PlanningEnvelope.write(List.of());
        extensions.put(PlanningEnvelope.EXTENSION, empty);
        var cleared = new Artifact(req.id(), req.kind(), req.type(), req.title(), req.text(), req.attributes(), extensions);
        var clearing = new ExchangeDocument(read.profile(), read.profileVersion(), "2", true, read.source(), read.artifacts().stream().map(a -> a.id().equals("R") ? cleared : a).toList(), read.relations(), read.placements(), read.metadata(), read.losses());
        equal(List.of(), PlanningEnvelope.read(requirement(codec.read(codec.write(clearing), "4", true), "R").extensions().get(PlanningEnvelope.EXTENSION)));
        for (String malformed : List.of("{}", payload + " {}", payload.replace("\"schema\":1", "\"schema\":2"),
                payload.replace("\"schema\":1", "\"schema\":4294967297"),
                payload.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                payload.replace("\"value\":\"2030\"", "\"value\":2030"))) rejects(() -> PlanningEnvelope.read(malformed));
        rejects(() -> PlanningEnvelope.write(List.of(goal, goal)));
        rejects(() -> PlanningEnvelope.read(" ".repeat(PlanningEnvelope.MAX_LENGTH + 1)));
        rejects(() -> new PlanningEnvelope.Entry("x", "p", "1", "origin\nforged", Map.of()));
        // Ordinary schema-valid ReqIF files without the reserved attribute retain the existing behavior.
        var plain = codec.read(codec.write(document(List.of(requirement("PLAIN", null)))), "plain", true);
        check(!requirement(plain, "PLAIN").extensions().containsKey(PlanningEnvelope.EXTENSION), "Invented planning metadata");
        equal("Requirement text", requirement(plain, "PLAIN").text());
        check(PlanningEnvelope.report(original).stream().anyMatch(l -> l.disposition() == LossDisposition.PRESERVED_EXTENSION), "Missing outbound report");
    }
    private static Artifact requirement(String id, String payload) { return new Artifact(id, ArtifactKind.REQUIREMENT, "taxonomy-object", "Title", "Requirement text", Map.of(), payload == null ? Map.of() : Map.of(PlanningEnvelope.EXTENSION, payload)); }
    private static ExchangeDocument document(List<Artifact> artifacts) { return new ExchangeDocument(ReqifExchangeCodec.PROFILE, "1", "1", true, "", artifacts, List.of(), List.of(), Map.of(), List.of()); }
    private static Artifact requirement(ExchangeDocument doc, String id) { return doc.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT && a.id().equals(id)).findFirst().orElseThrow(); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + " but got " + actual); }
    private static void rejects(Runnable run) { assertions++; try { run.run(); } catch (ExchangeFormatException expected) { return; } throw new AssertionError("Expected invalid envelope"); }
}
