package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeStandardsTest {
    private final ReqifExchangeCodec reqif = new ReqifExchangeCodec();
    private final ArchiMateExchangeCodec archimate = new ArchiMateExchangeCodec();
    private static byte[] fixture(String name) throws Exception {
        try (var stream = ExchangeStandardsTest.class.getResourceAsStream("/interoperability/" + name)) { assertNotNull(stream); return stream.readAllBytes(); }
    }
    @Test void rmfRoundTripRetainsIdsTypesAttributesAndRepeatedHierarchyOccurrences() throws Exception {
        var before = reqif.read(fixture("eclipse-rmf.reqif"), "tool-version-1", true);
        var after = reqif.read(reqif.write(before), "tool-version-1", true);
        assertEquals(before.artifacts().size(), after.artifacts().size()); assertEquals(before.relations(), after.relations());
        assertEquals(before.placements(), after.placements());
        Map<String, Artifact> original = items(before), returned = items(after); assertEquals(original.keySet(), returned.keySet());
        original.forEach((id, value) -> {
            Artifact actual = returned.get(id); assertEquals(value.type(), actual.type()); assertEquals(value.title(), actual.title()); assertEquals(value.text(), actual.text());
            assertEquals(semanticValues(value.attributes()), semanticValues(actual.attributes()));
        });
        assertEquals(ExchangeXml.semantic(before.metadata().get("DATATYPES")), ExchangeXml.semantic(after.metadata().get("DATATYPES")));
    }
    @Test void exportOverlaysCurrentValuesInsteadOfReplayingTheOriginalSource() throws Exception {
        var source = reqif.read(fixture("eclipse-rmf.reqif"), null, true);
        Artifact original = source.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT && a.extensions().containsKey("textAttribute")).findFirst().orElseThrow();
        var artifacts = new ArrayList<>(source.artifacts()); artifacts.replaceAll(a -> a.id().equals(original.id()) ? new Artifact(a.id(), a.kind(), a.type(), "Current canonical title", "Current canonical body", a.attributes(), a.extensions()) : a);
        var after = reqif.read(reqif.write(copy(source, source.source(), artifacts, source.placements())), null, true);
        assertEquals("Current canonical title", items(after).get(original.id()).title()); assertEquals("Current canonical body", items(after).get(original.id()).text());
    }
    @Test void acceptedEvidenceRebuildsWithoutOriginalUploadedPackage() throws Exception {
        var source = reqif.read(fixture("eclipse-rmf.reqif"), null, true);
        var rebuilt = reqif.read(reqif.write(copy(source, "", source.artifacts(), source.placements())), null, true);
        assertEquals(items(source).keySet(), items(rebuilt).keySet()); assertEquals(source.placements(), rebuilt.placements());
        items(source).forEach((id, artifact) -> assertEquals(semanticValues(artifact.attributes()), semanticValues(items(rebuilt).get(id).attributes())));
    }
    @Test void newNativeRequirementsCanBeAddedAfterAnExternalRoundTripWithStableTypes() throws Exception {
        var original = reqif.read(fixture("eclipse-rmf.reqif"), null, true);
        var artifacts = new ArrayList<>(original.artifacts()); artifacts.add(requirement("native-added", "New requirement", "New body"));
        var first = reqif.read(reqif.write(copy(original, original.source(), artifacts, original.placements())), null, true);
        assertEquals("New body", items(first).get("native-added").text());
        artifacts = new ArrayList<>(first.artifacts()); artifacts.add(requirement("native-next", "Another", "Next body"));
        var second = reqif.read(reqif.write(copy(first, first.source(), artifacts, first.placements())), null, true);
        assertEquals("New body", items(second).get("native-added").text()); assertEquals("Next body", items(second).get("native-next").text());
    }
    @Test void generatedProjectIsDeterministicAndAllRequirementsOccurInSpecification() {
        var source = new ExchangeDocument(ReqifExchangeCodec.PROFILE, "1", null, true, "", List.of(requirement("req-alpha", "Alpha", "Text < & >"), requirement("req-beta", "Beta", "Other")), List.of(), List.of(), Map.of(), List.of());
        byte[] bytes = reqif.write(source); assertArrayEquals(bytes, reqif.write(source));
        var back = reqif.read(bytes, null, true); assertEquals(2, back.placements().size()); assertEquals("Text < & >", items(back).get("req-alpha").text());
    }
    @Test void archiRoundTripRetainsModelIdentityTypesAndBendpoints() throws Exception {
        var original = archimate.read(fixture("archi-bendpoints.xml"), null, true);
        byte[] output = archimate.write(original); var back = archimate.read(output, null, true);
        assertEquals(items(original).keySet(), items(back).keySet());
        original.relations().forEach(r -> assertTrue(back.relations().stream().anyMatch(b -> b.id().equals(r.id()) && b.type().equals(r.type()) && b.source().equals(r.source()) && b.target().equals(r.target()))));
        assertEquals(original.placements().stream().map(Placement::id).toList(), back.placements().stream().map(Placement::id).toList());
        Artifact view = original.artifacts().stream().filter(a -> a.kind() == ArtifactKind.VIEW).findFirst().orElseThrow();
        assertEquals(ExchangeXml.semantic(view.extensions().get("connectionsXml")), ExchangeXml.semantic(items(back).get(view.id()).extensions().get("connectionsXml")));
        assertArrayEquals(output, archimate.write(original));
    }
    @Test void acceptedLayoutMovesAreAppliedAndCanonicalTypeIsNotLost() throws Exception {
        var source = archimate.read(fixture("archi-bendpoints.xml"), null, true);
        Placement original = source.placements().getFirst(); var placements = new ArrayList<>(source.placements());
        Map<String, String> attributes = new HashMap<>(original.attributes()); attributes.put("x", "777");
        placements.set(0, new Placement(original.id(), original.containerId(), original.parentId(), original.artifactId(), original.position(), attributes));
        var moved = archimate.read(archimate.write(copy(source, source.source(), source.artifacts(), placements)), null, true);
        assertEquals("777", moved.placements().stream().filter(p -> p.id().equals(original.id())).findFirst().orElseThrow().attributes().get("x"));
        Artifact canonical = new Artifact("native-system", ArtifactKind.ELEMENT, "ApplicationComponent", "System", "", Map.of(), Map.of("canonicalType", "System"));
        var nativeModel = new ExchangeDocument(ArchiMateExchangeCodec.PROFILE, "1", null, true, "", List.of(canonical), List.of(), List.of(), Map.of(), List.of());
        assertEquals("System", items(archimate.read(archimate.write(nativeModel), null, true)).get("native-system").extensions().get("canonicalType"));
    }
    @Test void invalidProducerFilesAreRejectedWithoutWeakeningTheNormativeSchema() throws Exception {
        for (String name : List.of("strictdoc.reqif", "polarion.reqif", "valid-tool.reqif")) {
            byte[] source = fixture(name); assertThrows(ExchangeFormatException.class, () -> reqif.read(source, null, true), name);
        }
    }
    @Test void partialReviewCannotSilentlyDropAnOrphanedOrCyclicOccurrence() throws Exception {
        var source = reqif.read(fixture("eclipse-rmf.reqif"), null, true);
        Placement original = source.placements().getFirst();
        for (String parent : List.of("missing-parent", original.id())) {
            var placements = new ArrayList<>(source.placements());
            placements.set(0, new Placement(original.id(), original.containerId(), parent, original.artifactId(), original.position(), original.attributes()));
            assertThrows(ExchangeFormatException.class, () -> reqif.write(copy(source, source.source(), source.artifacts(), placements)));
        }
        var model = archimate.read(fixture("archi-bendpoints.xml"), null, true);
        var orphan = new Placement("orphan", "unselected-view", null, model.placements().getFirst().artifactId(), 0, Map.of());
        assertThrows(ExchangeFormatException.class, () -> archimate.write(copy(model, model.source(), model.artifacts(), List.of(orphan))));
    }
    @Test void xxeUtf16DepthAndSizeAreRejectedBeforeAnyExternalAccess() {
        String entity = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]><x>&leak;</x>";
        assertThrows(ExchangeFormatException.class, () -> ExchangeXml.parse(entity.getBytes(StandardCharsets.UTF_8)));
        assertThrows(ExchangeFormatException.class, () -> ExchangeXml.parse(entity.replace("1.0\"", "1.0\" encoding=\"UTF-16\"").getBytes(StandardCharsets.UTF_16)));
        assertThrows(ExchangeFormatException.class, () -> ExchangeXml.parse(("<x>".repeat(98) + "</x>".repeat(98)).getBytes(StandardCharsets.UTF_8)));
        assertThrows(ExchangeFormatException.class, () -> ExchangeXml.parse(new byte[ExchangeXml.MAX_BYTES + 1]));
    }
    @Test void namespacesAndFormattingDoNotPretendToBeSemanticChanges() {
        assertEquals(ExchangeXml.semantic("<a:r xmlns:a=\"urn:test\"><a:p a:k=\"v\">text</a:p></a:r>"),
                ExchangeXml.semantic("<b:r xmlns:b=\"urn:test\">\n <b:p b:k=\"v\">text</b:p>\n</b:r>"));
    }
    @Test void richTextCannotSmuggleActiveContentOrAutomaticExternalFetches() {
        for (String content : List.of("<script>ignored()</script>", "<a href=\"javascript:ignored()\">text</a>", "<img src=\"https://untrusted.invalid/image\"/>", "<p style=\"background:url(https://untrusted.invalid)\">text</p>", "<p onclick=\"ignored()\">text</p>"))
            assertThrows(ExchangeFormatException.class, () -> ExchangeXml.parse(("<div xmlns=\"http://www.w3.org/1999/xhtml\">" + content + "</div>").getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> ExchangeXml.parse("<div xmlns=\"http://www.w3.org/1999/xhtml\"><a href=\"https://example.org/reference\">Reference</a></div>".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void acceptedContainerEvidenceDoesNotDuplicateIndividuallyReviewedChildren() throws Exception {
        var requirements = reqif.read(fixture("eclipse-rmf.reqif"), null, true);
        for (Artifact specification : requirements.artifacts()) if (specification.kind() == ArtifactKind.SPECIFICATION)
            assertFalse(specification.extensions().get("xml").contains("SPEC-HIERARCHY"));
        var model = archimate.read(fixture("archi-bendpoints.xml"), null, true);
        for (Artifact view : model.artifacts()) if (view.kind() == ArtifactKind.VIEW) {
            var evidence = ExchangeXml.parse(view.extensions().get("xml").getBytes(StandardCharsets.UTF_8));
            assertEquals(0, evidence.getElementsByTagNameNS(ArchiMateExchangeCodec.NS, "node").getLength());
            assertEquals(0, evidence.getElementsByTagNameNS(ArchiMateExchangeCodec.NS, "connection").getLength());
        }
    }
    @Test void multipleArchiOrganizationGroupsSurviveAcceptedEvidenceRebuild() throws Exception {
        String xml = new String(fixture("archi-sample.xml"), StandardCharsets.UTF_8).replace("</model>",
                "<organizations><item><label>First</label><item identifierRef=\"id-37d5bc4b\"/></item></organizations>"
                        + "<organizations><item><label>Second</label><item identifierRef=\"id-89c22226\"/></item></organizations></model>");
        var original = archimate.read(xml.getBytes(StandardCharsets.UTF_8), null, true);
        var rebuilt = archimate.read(archimate.write(copy(original, "", original.artifacts(), original.placements())), null, true);
        assertEquals(4, rebuilt.placements().size());
        assertEquals(original.placements(), rebuilt.placements());
        assertEquals(2, ExchangeXml.parse(archimate.write(rebuilt)).getElementsByTagNameNS(ArchiMateExchangeCodec.NS, "organizations").getLength());
    }
    private static Artifact requirement(String id, String title, String text) { return new Artifact(id, ArtifactKind.REQUIREMENT, "taxonomy-object", title, text, Map.of(), Map.of()); }
    private static ExchangeDocument copy(ExchangeDocument source, String xml, List<Artifact> artifacts, List<Placement> placements) {
        return new ExchangeDocument(source.profile(), source.profileVersion(), source.externalVersion(), source.completeScope(), xml, artifacts, source.relations(), placements, source.metadata(), source.losses());
    }
    private static Map<String, Artifact> items(ExchangeDocument source) { Map<String, Artifact> values = new TreeMap<>(); source.artifacts().forEach(a -> values.put(a.id(), a)); return values; }
    private static Map<String, String> semanticValues(Map<String, String> values) {
        Map<String, String> result = new TreeMap<>(); values.forEach((key, value) -> result.put(key, value.startsWith("<") ? ExchangeXml.semantic(value) : value)); return result;
    }
}
