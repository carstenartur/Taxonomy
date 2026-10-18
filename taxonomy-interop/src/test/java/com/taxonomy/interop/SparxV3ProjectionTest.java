package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual native evidence for new and previously bound Sparx export connections. */
class SparxV3ProjectionTest {
    private static final String REQUIREMENT = "F1: Display the river level — including the latest reading.\n"
            + "F2: Authorized operators review alerts & retain the dispatch history.\n"
            + "F3: Send email, phone and text notifications when the configured threshold is exceeded.";
    private static final String PRESENCE = "F2 directly requires access to the dispatch history; the reader displays that record.";
    private static final Map<String, String> TRANSPORTS = Map.ofEntries(
            Map.entry("DEPENDS_ON", "Dependency"), Map.entry("REALIZES", "Association"),
            Map.entry("COMMUNICATES_WITH", "InformationFlow"), Map.entry("RELATED_TO", "Association"),
            Map.entry("CONSUMES", "Usage"), Map.entry("CONTAINS", "Composition"),
            Map.entry("SUPPORTS", "Association"), Map.entry("USES", "Association"),
            Map.entry("FULFILLS", "Association"), Map.entry("ASSIGNED_TO", "Association"),
            Map.entry("PRODUCES", "Association"), Map.entry("REQUIRES", "Association"));
    private static final Map<String, List<String>> ENDPOINTS = Map.ofEntries(
            Map.entry("REALIZES", List.of("capability", "service")), Map.entry("SUPPORTS", List.of("service", "reader")),
            Map.entry("CONSUMES", List.of("reader", "record")), Map.entry("USES", List.of("application", "service")),
            Map.entry("FULFILLS", List.of("coi", "capability")), Map.entry("ASSIGNED_TO", List.of("role", "reader")),
            Map.entry("DEPENDS_ON", List.of("coi", "service")), Map.entry("PRODUCES", List.of("reader", "record")),
            Map.entry("COMMUNICATES_WITH", List.of("channel", "service")), Map.entry("CONTAINS", List.of("system", "component")),
            Map.entry("REQUIRES", List.of("capability", "record")), Map.entry("RELATED_TO", List.of("reader", "record")));
    private static final List<ElementSpec> ELEMENTS = List.of(
            new ElementSpec("reader", "BusinessProcess", "Process", "History reader"),
            new ElementSpec("record", "DataObject", "InformationProduct", "Dispatch record"),
            new ElementSpec("capability", "Capability", "Capability", "Warning capability"),
            new ElementSpec("service", "ApplicationService", "CoreService", "History service"),
            new ElementSpec("application", "ApplicationComponent", "UserApplication", "Operator application"),
            new ElementSpec("coi", "BusinessService", "COIService", "Warning service"),
            new ElementSpec("role", "BusinessRole", "BusinessRole", "Operator role"),
            new ElementSpec("channel", "CommunicationNetwork", "CommunicationsService", "Notification channel"),
            new ElementSpec("system", "ApplicationComponent", "System", "Warning system"),
            new ElementSpec("component", "ApplicationComponent", "Component", "Display component"));
    private final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
    private final IntegrationDomainAdapter domain = new IntegrationDomainAdapter(null, json);
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private final RepositoryContext context = RepositoryContext.workspace("repository", "workspace", "draft", "alice");

    @Test void newV3ConnectionProjectsActualPersistedArchiMateEvidenceAndAllTwelveTypes() throws Exception {
        Imported imported = importArchiMate();
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        assertNotEquals(imported.connection().id(), outgoing.id());
        assertEquals(TRANSPORTS.keySet(), Arrays.stream(RelationType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        assertTrue(ArchitectureSemanticPatch.index(imported.dsl()).values().stream()
                .filter(b -> Set.of("element", "relation").contains(b.getKind()))
                .allMatch(b -> b.property("x-exchange-artifact") != null));
        ExchangeDocument projected = export(outgoing, imported.dsl());
        assertAll("The native projection must already contain the evidence before serialization",
                () -> assertEquals(REQUIREMENT, projected.metadata().get("taxonomy.requirementText")),
                () -> assertEquals(12, projected.relations().size()),
                () -> assertTrue(projected.losses().isEmpty(), () -> projected.losses().toString()));
        assertProjection(imported, projected);
        var codec = new SparxXmiCodec("3");
        byte[] xmi = codec.write(projected);
        assertProjection(imported, codec.read(xmi, "returned-v3", true));
    }

    @Test void currentNativeFieldsOverrideTheImportedValuesWithoutDiscardingTheirEvidence() throws Exception {
        Imported imported = importArchiMate();
        Artifact reader = imported.items().get("ELEMENT:reader");
        String id = domain.businessId(imported.connection(), null, reader);
        String dsl = commands.apply(imported.dsl(), new UpdateArchitectureElement(id, "Process",
                Map.of("title", "Reviewed history service", "description", "The locally reviewed service description."))).dsl();
        String component = domain.businessId(imported.connection(), null, imported.items().get("ELEMENT:component"));
        dsl = commands.apply(dsl, new UpdateArchitectureElement(component, "UserApplication", Map.of("title", "Reviewed display application"))).dsl();
        Artifact relation = imported.items().get("RELATION:r-USES");
        String[] key = domain.relationBusinessId(relation, imported.connection(), imported.items(), List.of()).split(" ");
        dsl = commands.apply(dsl, new UpdateArchitectureRelation(new RelationKey(key[0], key[1], key[2]), "accepted")).dsl();
        ExchangeDocument projected = export(connection(SparxMappingProfile.PROFILE, "3"), dsl);
        Artifact updated = projected.artifacts().stream().filter(a -> a.title().equals("Reviewed history service")).findFirst().orElseThrow();
        assertEquals("Process", updated.extensions().get("canonicalType"));
        Artifact retyped = projected.artifacts().stream().filter(a -> a.title().equals("Reviewed display application")).findFirst().orElseThrow();
        assertEquals("UserApplication", retyped.extensions().get("canonicalType"));
        assertEquals("UserApplication", retyped.attributes().get("tag:taxonomy.elementType"));
        assertEquals("UserApplication", retyped.attributes().get("tag:taxonomy.ElementType"));
        assertTrue(updated.text().startsWith("The locally reviewed service description."));
        assertTrue(updated.text().contains(PRESENCE));
        reader.attributes().forEach((name, value) -> assertEquals(value, updated.attributes().get(name), name));
        Relation reviewed = relation(projected, "USES");
        assertEquals("accepted", reviewed.extensions().get("taxonomy:status"));
        assertEquals("accepted", reviewed.attributes().get("tag:taxonomy.status"));
        assertEquals("ACCEPTED_FROM_SOURCE", reviewed.attributes().get("tag:taxonomy.review.decision"));
        var codec = new SparxXmiCodec("3");
        assertEquals("accepted", relation(codec.read(codec.write(projected), "local-edit", true), "USES").attributes().get("tag:taxonomy.status"));
    }

    @ParameterizedTest
    @CsvSource({"archimate,false", "archimate,true", "sparx,false", "sparx,true"})
    void clearedNativeOwnerDoesNotReturnFromImportedTagsOrOpaquePropertyEvidence(String format, boolean bound) throws Exception {
        Imported imported = importArchiMate("", archiMateXml(Map.of("Taxonomy.x-owner", "Alice")));
        var codec = new SparxXmiCodec("3");
        if (format.equals("sparx")) {
            var delivered = export(connection(SparxMappingProfile.PROFILE, "3"), imported.dsl());
            imported = importExchange("", connection(SparxMappingProfile.PROFILE, "3"),
                    codec.read(codec.write(delivered), "sparx-owner-import", true));
        }
        Artifact original = imported.source().artifacts().stream().filter(a -> a.title().equals("History reader")).findFirst().orElseThrow();
        String elementId = domain.businessId(imported.connection(), null, original);
        var originalBlock = ArchitectureSemanticPatch.index(imported.dsl()).get("element:" + elementId);
        assertEquals("Alice", originalBlock.property("x-owner"), "Exercise an actually imported editable owner");
        String originalEvidence = originalBlock.property("x-exchange-artifact");
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        BoundDelivery prior = bound ? deliverAndBind(outgoing, imported.dsl(), export(outgoing, imported.dsl())) : null;

        String cleared = commands.apply(imported.dsl(), new ClearArchitectureElementProperties(elementId, Set.of("x-owner"))).dsl();
        assertNull(ArchitectureSemanticPatch.index(cleared).get("element:" + elementId).property("x-owner"));
        for (String current : List.of("", "Bob")) {
            String edited = current.isEmpty() ? cleared : commands.apply(cleared,
                    new UpdateArchitectureElement(elementId, "Process", Map.of("x-owner", current))).dsl();
            ExchangeDocument projected = prior == null ? export(outgoing, edited)
                    : exportWithBindings(outgoing, edited, prior.identities(), prior.document());
            for (ExchangeDocument result : List.of(projected, codec.read(codec.write(projected), "owner-roundtrip", true))) {
                Artifact owner = result.artifacts().stream().filter(a -> a.title().equals("History reader")).findFirst().orElseThrow();
                assertEquals(current.isEmpty() ? null : current, owner.extensions().get("taxonomy:x-owner"));
                assertEquals(current.isEmpty() ? null : current, owner.attributes().get("tag:taxonomy.x-owner"),
                        "The current native field, including its absence, controls the effective owner tag");
                original.attributes().forEach((key, value) -> {
                    if (!key.equals("tag:taxonomy.x-owner")) assertEquals(value, owner.attributes().get(key),
                            "Opaque imported evidence remains unchanged: " + key);
                });
                original.extensions().forEach((key, value) -> {
                    if (key.equals("xml") || key.startsWith("definition:")) assertEquals(value, owner.extensions().get(key),
                            "Original XML/property definitions are evidence, not the current editable owner");
                });
            }
            assertEquals(originalEvidence, ArchitectureSemanticPatch.index(edited).get("element:" + elementId).property("x-exchange-artifact"),
                    "The accepted original artifact is not rewritten by clear, update or export");
        }
    }

    @Test void aReverseImportedConnectorKeepsItsDirectionAndItsNativeSourceTargetMeaning() {
        Connection incoming = connection(SparxMappingProfile.PROFILE, "2");
        Artifact a = new Artifact("a", ArtifactKind.ELEMENT, "Component", "A", "", Map.of(), Map.of("canonicalType", "Component"));
        Artifact b = new Artifact("b", ArtifactKind.ELEMENT, "Component", "B", "", Map.of(), Map.of("canonicalType", "Component"));
        Artifact edge = new Artifact("r", ArtifactKind.RELATION, "Dependency", "", "", Map.of("description", "B depends on A."),
                Map.of("source", "a", "target", "b", "canonicalType", "DEPENDS_ON", "direction", "Destination -> Source"));
        Map<String, Artifact> selected = Map.of("ELEMENT:a", a, "ELEMENT:b", b, "RELATION:r", edge);
        String dsl = "";
        for (var command : domain.architectureCommands(incoming, dsl, Map.of(), selected, List.of())) dsl = commands.apply(dsl, command).dsl();
        String nativeSource = domain.businessId(incoming, null, b), nativeTarget = domain.businessId(incoming, null, a);
        assertTrue(ArchitectureSemanticPatch.index(dsl).containsKey("relation:" + nativeSource + " DEPENDS_ON " + nativeTarget));
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        ExchangeDocument projected = export(outgoing, dsl);
        Relation exported = projected.relations().getFirst();
        Map<String, String> names = new HashMap<>(); projected.artifacts().forEach(item -> names.put(item.id(), item.title()));
        assertEquals("Destination -> Source", exported.extensions().get("direction"));
        assertEquals("Destination -> Source", exported.attributes().get("tag:taxonomy.relationDirection"));
        assertEquals("A", names.get(exported.source()));
        assertEquals("B", names.get(exported.target()));
        assertEquals("B depends on A.", exported.attributes().get("description"));
        var codec = new SparxXmiCodec("3");
        assertEquals(exported.source(), codec.read(codec.write(projected), "reverse", true).relations().getFirst().source());
    }

    @Test void reviewedV3TypeAndEndpointMappingUpdateEveryEffectiveTypeDeclaration() throws Exception {
        Artifact prior = new Artifact("r", ArtifactKind.RELATION, "Association", "", "", Map.of(
                "tag:taxonomy.mappingProfile", "sparx-xmi-2.1@3", "tag:taxonomy.relationType", "RELATED_TO",
                "tag:taxonomy.relationDirection", "Source -> Destination", "tag:taxonomy.RelationType", "RELATED_TO",
                "tag:taxonomy.provenance.evidence.0.decision.quote", "the configured threshold is exceeded"),
                Map.of("source", "a", "target", "b", "canonicalType", "RELATED_TO", "taxonomy:RelationType", "RELATED_TO",
                        "direction", "Source -> Destination", "stereotype", "Taxonomy::RELATED_TO"));
        Artifact mapped = select(prior, "3", new EndpointOverride("native-a", "native-b", RelationProjection.ARCHITECTURE_RELATION, "SUPPORTS"), "SUPPORTS");
        assertAll(() -> assertEquals("Association", mapped.type()),
                () -> assertEquals("SUPPORTS", mapped.extensions().get("canonicalType")),
                () -> assertEquals("SUPPORTS", mapped.attributes().get("tag:taxonomy.relationType")),
                () -> assertEquals("SUPPORTS", mapped.attributes().get("tag:taxonomy.RelationType")),
                () -> assertEquals("Taxonomy::SUPPORTS", mapped.extensions().get("stereotype")),
                () -> assertEquals("SUPPORTS", mapped.attributes().get("name")),
                () -> assertEquals("SUPPORTS", mapped.extensions().get("nativeType")),
                () -> assertEquals("the configured threshold is exceeded", mapped.attributes().get("tag:taxonomy.provenance.evidence.0.decision.quote")));
    }

    @ParameterizedTest @ValueSource(strings = {"1", "2"})
    void legacyVersionsKeepTheirBoundedRelationMappings(String version) throws Exception {
        Imported imported = importArchiMate();
        ExchangeDocument projected = export(connection(SparxMappingProfile.PROFILE, version), imported.dsl());
        assertEquals(6, projected.losses().stream().filter(loss -> loss.code().equals("UNSUPPORTED_RELATION_TYPE")).count());
        assertFalse(projected.metadata().containsKey("taxonomy.requirementText"));
        assertEquals(version.equals("2"), IntegrationDomainAdapter.nativePackages(connection(SparxMappingProfile.PROFILE, version)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void currentSourcesExposeConflictingRequirementsWithOrWithoutAPreviousExchange(boolean previousPresent) throws Exception {
        Imported first = importArchiMate();
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        ExchangeDocument previous = export(outgoing, first.dsl());
        String otherRequirement = "F1: Export the separately reviewed catchment forecast.";
        byte[] otherXml = new String(archiMateXml(), StandardCharsets.UTF_8)
                .replace(escape(REQUIREMENT), escape(otherRequirement))
                .replace(sha256(REQUIREMENT), sha256(otherRequirement))
                .replace("snapshot-before-import", "second-snapshot").getBytes(StandardCharsets.UTF_8);
        Imported mixed = importArchiMate(first.dsl(), otherXml);

        ExchangeDocument projected = export(outgoing, mixed.dsl(), previousPresent ? previous : null);
        Set<String> conflicts = projected.losses().stream().filter(loss -> loss.code().equals("EXCHANGE_MODEL_EVIDENCE_CONFLICT"))
                .map(MappingLoss::field).collect(java.util.stream.Collectors.toSet());
        assertAll("No single source may supply model provenance for conflicting current sources",
                () -> assertEquals(20, projected.artifacts().size()),
                () -> assertEquals(24, projected.relations().size()),
                () -> assertFalse(projected.metadata().containsKey("taxonomy.requirementText")),
                () -> assertFalse(projected.metadata().containsKey("taxonomy.requirementSha256")),
                () -> assertFalse(projected.metadata().containsKey("taxonomy.snapshotId")),
                () -> assertTrue(conflicts.containsAll(Set.of("metadata.taxonomy.requirementText", "metadata.taxonomy.requirementSha256", "metadata.taxonomy.snapshotId")), conflicts::toString));
        // Both original documents remain in native evidence; the ambiguity is visible instead of losing either origin.
        var blocks = ArchitectureSemanticPatch.index(mixed.dsl());
        assertEquals(first.source().metadata(), json.read(blocks.get("exchange:integration-" + first.connection().id()).property("source"), ExchangeDocument.class).metadata());
        assertEquals(mixed.source().metadata(), json.read(blocks.get("exchange:integration-" + mixed.connection().id()).property("source"), ExchangeDocument.class).metadata());
    }

    @Test void previousModelIdentityAndNonconflictingEvidenceRemainStable() throws Exception {
        Imported imported = importArchiMate();
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        ExchangeDocument original = export(outgoing, imported.dsl());
        Map<String, String> metadata = new TreeMap<>(original.metadata());
        metadata.put("title", "Accepted external model title");
        metadata.put("external.modelReview", "Model review retained by this connection");
        var previous = new ExchangeDocument(original.profile(), original.profileVersion(), "accepted-version", true, original.source(),
                original.artifacts(), original.relations(), original.placements(), metadata, original.losses());
        ExchangeDocument projected = export(outgoing, imported.dsl(), previous);
        assertEquals(metadata, projected.metadata());
        assertEquals("accepted-version", projected.externalVersion());
        assertTrue(projected.losses().isEmpty(), projected.losses()::toString);
        assertProjection(imported, new SparxXmiCodec("3").read(new SparxXmiCodec("3").write(projected), "returned", true));
    }

    @Test void unrelatedStoredImportCannotContaminateCurrentModelEvidenceAfterAPreviousExchange() throws Exception {
        Imported imported = importArchiMate();
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        ExchangeDocument previous = export(outgoing, imported.dsl());
        var unrelatedMetadata = new TreeMap<>(imported.source().metadata());
        unrelatedMetadata.put("taxonomy.requirementText", "Unrelated requirement");
        unrelatedMetadata.put("taxonomy.requirementSha256", sha256("Unrelated requirement"));
        var unrelated = new ExchangeDocument(SparxMappingProfile.PROFILE, "3", "other", true, "", List.of(), List.of(), List.of(), unrelatedMetadata, List.of());
        String dsl = commands.apply(imported.dsl(), new StoreExchangeEvidence("integration-" + UUID.randomUUID(), SparxMappingProfile.PROFILE, "3",
                json.fingerprint(unrelated), json.write(unrelated))).dsl();
        ExchangeDocument projected = export(outgoing, dsl, previous);
        assertEquals(previous.metadata(), projected.metadata());
        assertTrue(projected.losses().isEmpty(), projected.losses()::toString);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void priorDeliveredMetadataIdentityCannotHideNewNativeSourceEvidence(boolean acknowledged) throws Exception {
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        ExchangeDocument first = export(outgoing, "");
        Map<String, String> initialMetadata = new TreeMap<>(first.metadata());
        initialMetadata.put("external.modelReview", "Opaque reviewed metadata retained by this connection");
        first = new ExchangeDocument(first.profile(), first.profileVersion(), first.externalVersion(), first.completeScope(), first.source(),
                first.artifacts(), first.relations(), first.placements(), initialMetadata, first.losses());
        var prior = deliverAndBind(outgoing, "", first);
        assertEquals(1, prior.identities().size(), "The first real file binds its model metadata even without architecture elements");
        Imported added = importArchiMate();
        ExchangeDocument projected = exportWithBindings(outgoing, added.dsl(), prior.identities(), acknowledged ? prior.document() : null);

        assertAll("The retained METADATA identity must not replace newly resolved model provenance",
                () -> assertEquals(REQUIREMENT, projected.metadata().get("taxonomy.requirementText")),
                () -> assertEquals(sha256(REQUIREMENT), projected.metadata().get("taxonomy.requirementSha256")),
                () -> assertEquals("snapshot-before-import", projected.metadata().get("taxonomy.snapshotId")),
                () -> assertEquals(initialMetadata.get("identifier"), projected.metadata().get("identifier")),
                () -> assertEquals(initialMetadata.get("title"), projected.metadata().get("title")),
                () -> assertEquals(initialMetadata.get("external.modelReview"), projected.metadata().get("external.modelReview")));
        assertTrue(projected.losses().isEmpty(), projected.losses()::toString);
        assertProjection(added, projected);
        var codec = new SparxXmiCodec("3");
        assertProjection(added, codec.read(codec.write(projected), "after-native-import", true));
        assertEquals(initialMetadata, prior.identities().getFirst().internal().attributes(), "Reading the new projection cannot mutate the accepted mapping");
    }

    @Test void boundMetadataCannotReintroduceARequirementRejectedByCurrentOriginReconciliation() throws Exception {
        Imported first = importArchiMate();
        Connection outgoing = connection(SparxMappingProfile.PROFILE, "3");
        var prior = deliverAndBind(outgoing, first.dsl(), export(outgoing, first.dsl()));
        String otherRequirement = "F1: Export the separately reviewed catchment forecast.";
        byte[] otherXml = new String(archiMateXml(), StandardCharsets.UTF_8)
                .replace(escape(REQUIREMENT), escape(otherRequirement))
                .replace(sha256(REQUIREMENT), sha256(otherRequirement))
                .replace("snapshot-before-import", "second-snapshot").getBytes(StandardCharsets.UTF_8);
        Imported mixed = importArchiMate(first.dsl(), otherXml);
        ExchangeDocument projected = exportWithBindings(outgoing, mixed.dsl(), prior.identities(), prior.document());

        assertAll("Conflicting current sources cannot regain old provenance through METADATA:package",
                () -> assertEquals(20, projected.artifacts().size()),
                () -> assertEquals(24, projected.relations().size()),
                () -> assertFalse(projected.metadata().containsKey("taxonomy.requirementText")),
                () -> assertFalse(projected.metadata().containsKey("taxonomy.requirementSha256")),
                () -> assertFalse(projected.metadata().containsKey("taxonomy.snapshotId")));
        Set<String> conflicts = projected.losses().stream().filter(loss -> loss.code().equals("EXCHANGE_MODEL_EVIDENCE_CONFLICT"))
                .map(MappingLoss::field).collect(java.util.stream.Collectors.toSet());
        assertTrue(conflicts.containsAll(Set.of("metadata.taxonomy.requirementText", "metadata.taxonomy.requirementSha256", "metadata.taxonomy.snapshotId")));
        for (Artifact original : prior.document().artifacts()) assertTrue(projected.artifacts().stream().anyMatch(item -> item.id().equals(original.id())), original.id());
        for (Relation original : prior.document().relations()) assertTrue(projected.relations().stream().anyMatch(item -> item.id().equals(original.id())), original.id());
    }

    private void assertProjection(Imported imported, ExchangeDocument result) throws Exception {
        for (var field : modelFields().entrySet()) assertEquals(field.getValue(), result.metadata().get(field.getKey()), field.getKey());
        Artifact reader = result.artifacts().stream().filter(a -> a.title().equals("History reader")).findFirst().orElseThrow();
        assertEquals("CR-HISTORY", reader.attributes().get("tag:taxonomy.sourceId"));
        assertDoesNotThrow(() -> UUID.fromString(reader.attributes().get("tag:taxonomy.id")), "Sparx identity is a GUID, separate from the original catalogue identifier");
        assertEquals(PRESENCE, reader.attributes().get("tag:taxonomy.presenceReason"));
        assertTrue(reader.text().contains("Read the retained dispatch records."));
        assertTrue(reader.text().contains(PRESENCE));
        assertEquals("REVIEWED_ELEMENT", reader.attributes().get("tag:taxonomy.review.decision"));
        assertEquals("CR-HISTORY", reader.attributes().get("tag:taxonomy.catalogue.code"));
        Artifact original = imported.items().get("ELEMENT:reader");
        original.attributes().forEach((key, value) -> assertEquals(value, reader.attributes().get(key), key));
        for (String type : TRANSPORTS.keySet()) {
            Relation relation = relation(result, type);
            assertEquals(TRANSPORTS.get(type), relation.type(), type);
            assertEquals(type, relation.attributes().get("tag:taxonomy.relationType"));
            assertEquals(type, relation.attributes().get("name"));
            assertEquals("Taxonomy::" + type, relation.extensions().get("stereotype"));
            assertEquals("sparx-xmi-2.1@3", relation.attributes().get("tag:taxonomy.mappingProfile"));
            assertEquals("original-edge-" + type, relation.attributes().get("tag:taxonomy.sourceId"));
            assertDoesNotThrow(() -> UUID.fromString(relation.attributes().get("tag:taxonomy.id")));
            assertEquals(type.equals("RELATED_TO") ? "Unspecified" : "Source -> Destination", relation.attributes().get("tag:taxonomy.relationDirection"));
            assertEquals(elementTitle(ENDPOINTS.get(type).getFirst()), result.artifacts().stream().filter(a -> a.id().equals(relation.source())).findFirst().orElseThrow().title());
            assertEquals(elementTitle(ENDPOINTS.get(type).getLast()), result.artifacts().stream().filter(a -> a.id().equals(relation.target())).findFirst().orElseThrow().title());
            assertEquals(notes(type), relation.attributes().get("description"));
            for (var field : evidenceFields(type).entrySet()) assertEquals(field.getValue(), relation.attributes().get("tag:" + field.getKey()), type + ": " + field.getKey());
            Artifact originalRelation = imported.items().get("RELATION:r-" + type);
            originalRelation.attributes().forEach((key, value) -> assertEquals(value, relation.attributes().get(key), type + ": " + key));
        }
    }

    @Test void aTagOnlyProposedConnectorRemainsProposedInTheNativeArchitecture() throws Exception {
        var incoming = connection(SparxMappingProfile.PROFILE, "3");
        var codec = new SparxXmiCodec("3");
        var imported = importArchiMate();
        var xml = com.taxonomy.exchange.ExchangeXml.parse(codec.write(export(incoming, imported.dsl())));
        for (var connectors : com.taxonomy.exchange.ExchangeXml.all(xml, null, "connectors"))
            for (var connector : com.taxonomy.exchange.ExchangeXml.children(connectors))
                for (var evidence : com.taxonomy.exchange.ExchangeXml.children(connector))
                    if (evidence.getLocalName().equals("evidence")) connector.removeChild(evidence);
        var source = codec.read(com.taxonomy.exchange.ExchangeXml.write(xml), "tag-only-status", true);
        var proposed = source.relations().stream().filter(r -> r.attributes().get("tag:taxonomy.relationType").equals("USES")).findFirst().orElseThrow();
        assertEquals("proposed", proposed.attributes().get("tag:taxonomy.status"));
        var nativeImport = importExchange("", incoming, source);
        var nativeRelation = ArchitectureSemanticPatch.index(nativeImport.dsl()).values().stream()
                .filter(b -> b.getKind().equals("relation") && b.getHeaderTokens().get(1).equals("USES")).findFirst().orElseThrow();
        assertEquals("proposed", nativeRelation.property("status"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reimportedSingleOriginReplacesPreviouslyDeliveredRequirementEvidence(boolean acknowledged) throws Exception {
        Imported initial = importArchiMate();
        var outgoing = connection(SparxMappingProfile.PROFILE, "3");
        var first = export(outgoing, initial.dsl());
        var metadata = new TreeMap<>(first.metadata()); metadata.put("external.modelReview", "Reviewed model note");
        first = new ExchangeDocument(first.profile(), first.profileVersion(), first.externalVersion(), first.completeScope(), first.source(),
                first.artifacts(), first.relations(), first.placements(), metadata, first.losses());
        var prior = deliverAndBind(outgoing, initial.dsl(), first);
        String nextRequirement = REQUIREMENT + "\nF4: Retain the reviewed river forecast.";
        byte[] updatedXml = new String(archiMateXml(), StandardCharsets.UTF_8)
                .replace(escape(REQUIREMENT), escape(nextRequirement))
                .replace(sha256(REQUIREMENT), sha256(nextRequirement))
                .replace("snapshot-before-import", "updated-snapshot").getBytes(StandardCharsets.UTF_8);
        Set<String> removedFields = Set.of("taxonomy.requirementId", "taxonomy.requirementVersionId", "taxonomy.canonicalGraphSha256");
        assertTrue(metadata.keySet().containsAll(removedFields));
        var xml = com.taxonomy.exchange.ExchangeXml.parse(updatedXml);
        var model = xml.getDocumentElement();
        var definitions = com.taxonomy.exchange.ExchangeXml.child(model, "propertyDefinitions");
        Map<String, String> names = new HashMap<>();
        for (var definition : com.taxonomy.exchange.ExchangeXml.children(definitions))
            names.put(definition.getAttribute("identifier"), com.taxonomy.exchange.ExchangeXml.text(definition, "name"));
        var properties = com.taxonomy.exchange.ExchangeXml.child(model, "properties");
        for (var property : com.taxonomy.exchange.ExchangeXml.children(properties))
            if (removedFields.contains(names.get(property.getAttribute("propertyDefinitionRef")))) properties.removeChild(property);
        var reviewDefinition = com.taxonomy.exchange.ExchangeXml.append(definitions, model.getNamespaceURI(), "propertyDefinition");
        reviewDefinition.setAttribute("identifier", "origin-review"); reviewDefinition.setAttribute("type", "string");
        com.taxonomy.exchange.ExchangeXml.text(reviewDefinition, model.getNamespaceURI(), "name", "external.modelReview");
        var reviewProperty = com.taxonomy.exchange.ExchangeXml.append(properties, model.getNamespaceURI(), "property");
        reviewProperty.setAttribute("propertyDefinitionRef", "origin-review");
        com.taxonomy.exchange.ExchangeXml.text(reviewProperty, model.getNamespaceURI(), "value", "Imported origin note");
        var updatedSource = new ArchiMateExchangeCodec().read(com.taxonomy.exchange.ExchangeXml.write(xml), "reimported-origin", true);
        var updated = importExchange(initial.dsl(), initial.connection(), updatedSource);
        var result = exportWithBindings(outgoing, updated.dsl(), prior.identities(), acknowledged ? prior.document() : null);
        var codec = new SparxXmiCodec("3");
        for (var returned : List.of(result, codec.read(codec.write(result), "updated-source", true))) {
            assertEquals(nextRequirement, returned.metadata().get("taxonomy.requirementText"));
            assertEquals(sha256(nextRequirement), returned.metadata().get("taxonomy.requirementSha256"));
            assertEquals("updated-snapshot", returned.metadata().get("taxonomy.snapshotId"));
            assertEquals(metadata.get("identifier"), returned.metadata().get("identifier"));
            assertEquals("Reviewed model note", returned.metadata().get("external.modelReview"));
            for (String removed : removedFields) assertFalse(returned.metadata().containsKey(removed), removed);
            assertTrue(returned.losses().isEmpty(), returned.losses()::toString);
        }
    }

    @Test void boundIdentityBearingOwnerTagFollowsTheCurrentNativeOwner() throws Exception {
        var outgoing = connection(SparxMappingProfile.PROFILE, "3");
        var original = importArchiMate("", archiMateXml(Map.of("Taxonomy.x-owner", "Alice")));
        var delivered = export(outgoing, original.dsl());
        var owner = delivered.artifacts().stream().filter(a -> a.title().equals("History reader")).findFirst().orElseThrow();
        String featureId = "{AAAABBBB-CCCC-4DDD-8EEE-FFFF00001111}";
        var tag = new Artifact(featureId, ArtifactKind.FEATURE, "tagged-value", "taxonomy.x-owner", "Alice",
                Map.of("reviewed", "keep"), Map.of("owner", owner.id(), "position", "0"));
        var artifacts = new ArrayList<>(delivered.artifacts()); artifacts.add(tag);
        var source = new ExchangeDocument(delivered.profile(), delivered.profileVersion(), delivered.externalVersion(), delivered.completeScope(),
                delivered.source(), artifacts, delivered.relations(), delivered.placements(), delivered.metadata(), delivered.losses());
        var codec = new SparxXmiCodec("3");
        var imported = importExchange("", outgoing, codec.read(codec.write(source), "owner-feature", true));
        String nativeId = domain.businessId(outgoing, null, owner);
        var identities = new ArrayList<Identity>();
        UUID operation = UUID.randomUUID();
        for (var entry : imported.items().entrySet()) {
            Artifact artifact = entry.getValue();
            String business = artifact.kind() == ArtifactKind.RELATION
                    ? domain.relationBusinessId(artifact, outgoing, imported.items(), List.of())
                    : domain.businessId(outgoing, null, artifact);
            identities.add(new Identity(entry.getKey(), business, null, null, json.fingerprint(null), null, artifact, operation, false));
        }
        var prior = new BoundDelivery(imported.source(), List.copyOf(identities));
        assertTrue(prior.document().artifacts().stream().anyMatch(a -> a.id().equals(featureId)));
        assertTrue(prior.identities().stream().anyMatch(i -> i.externalId().equals("FEATURE:" + featureId)));
        String originalEvidence = ArchitectureSemanticPatch.index(imported.dsl()).get("element:" + nativeId).property("x-exchange-artifact");
        String cleared = commands.apply(imported.dsl(), new ClearArchitectureElementProperties(nativeId, Set.of("x-owner"))).dsl();
        for (String current : List.of("", "Bob")) {
            String edited = current.isEmpty() ? cleared : commands.apply(cleared,
                    new UpdateArchitectureElement(nativeId, "Process", Map.of("x-owner", current))).dsl();
            var projected = exportWithBindings(outgoing, edited, prior.identities(), prior.document());
            for (var returned : List.of(projected, codec.read(codec.write(projected), "edited-owner-feature", true))) {
                var element = returned.artifacts().stream().filter(a -> a.id().equals(owner.id())).findFirst().orElseThrow();
                assertEquals(current.isEmpty() ? null : current, element.attributes().get("tag:taxonomy.x-owner"));
                var tags = returned.artifacts().stream().filter(a -> a.id().equals(featureId)).toList();
                if (current.isEmpty()) assertTrue(tags.isEmpty(), "A cleared owner cannot be restored by its identity-bearing tag");
                else { assertEquals(1, tags.size()); assertEquals("Bob", tags.getFirst().text());
                    assertTrue(tags.getFirst().attributes().entrySet().containsAll(tag.attributes().entrySet())); }
            }
            assertEquals(originalEvidence, ArchitectureSemanticPatch.index(edited).get("element:" + nativeId).property("x-exchange-artifact"));
        }
    }

    private Imported importArchiMate() throws Exception {
        return importArchiMate("", archiMateXml());
    }

    private Imported importArchiMate(String dsl, byte[] xml) {
        Connection incoming = connection(ArchiMateExchangeCodec.PROFILE, "1");
        ExchangeDocument source = new ArchiMateExchangeCodec().read(xml, "actual-import", true);
        return importExchange(dsl, incoming, source);
    }

    private Imported importExchange(String dsl, Connection incoming, ExchangeDocument source) {
        assertTrue(source.losses().isEmpty());
        Map<String, Artifact> selected = ExchangeItems.flatten(source);
        for (var command : domain.architectureCommands(incoming, dsl, Map.of(), selected, List.of())) dsl = commands.apply(dsl, command).dsl();
        // This is the exact accepted-evidence command used by IntegrationService; raw upload XML is not required.
        var evidence = new ExchangeDocument(source.profile(), source.profileVersion(), source.externalVersion(), source.completeScope(), "",
                source.artifacts(), source.relations(), source.placements(), source.metadata(), source.losses());
        dsl = commands.apply(dsl, new StoreExchangeEvidence("integration-" + incoming.id(), incoming.connectorId(), incoming.profileVersion(),
                json.fingerprint(evidence), json.write(evidence))).dsl();
        return new Imported(dsl, source, selected, incoming);
    }

    private ExchangeDocument export(Connection connection, String persistedDsl) {
        return export(connection, persistedDsl, null);
    }

    private ExchangeDocument export(Connection connection, String persistedDsl, ExchangeDocument previous) {
        var freshAdapter = new IntegrationDomainAdapter(null, new IntegrationJson(JsonMapper.builder().build()));
        var document = new WorkspaceDocument(new State("scope", "head", 1), persistedDsl);
        var current = freshAdapter.snapshot(context, connection, List.of(), document);
        assertTrue(current.items().isEmpty(), "No prior mapping may supply the exported evidence");
        return freshAdapter.exportDocument(connection, current, document, List.of(), previous);
    }

    /** The real codec and exportBindings supply the same identities that prepareFile persists. */
    private BoundDelivery deliverAndBind(Connection connection, String dsl, ExchangeDocument document) {
        var codec = new SparxXmiCodec("3");
        ExchangeDocument delivered = codec.read(codec.write(document), "prior-delivery", true);
        Map<String, Artifact> selected = ExchangeItems.flatten(delivered);
        var workspace = new WorkspaceDocument(new State("scope", "head", 1), dsl);
        var current = domain.snapshot(context, connection, List.of(), workspace);
        var bindings = domain.exportBindings(connection, current, workspace, selected);
        var identities = new ArrayList<Identity>();
        UUID operation = UUID.randomUUID();
        for (var entry : bindings.entrySet()) if (selected.containsKey(entry.getKey())) {
            var target = entry.getValue();
            var identity = new Identity(entry.getKey(), target.businessIdentity(), target.requirementId(), null,
                    json.fingerprint(null), null, selected.get(entry.getKey()), operation, false);
            identities.add(json.read(json.write(identity), Identity.class));
        }
        assertTrue(identities.stream().anyMatch(identity -> identity.externalId().equals("METADATA:package")),
                "A completed file delivery retains its metadata identity before any external acknowledgement");
        return new BoundDelivery(delivered, List.copyOf(identities));
    }

    private ExchangeDocument exportWithBindings(Connection connection, String dsl, List<Identity> identities, ExchangeDocument previous) {
        var freshAdapter = new IntegrationDomainAdapter(null, new IntegrationJson(JsonMapper.builder().build()));
        var workspace = new WorkspaceDocument(new State("scope", "changed-head", 2), dsl);
        var current = freshAdapter.snapshot(context, connection, identities, workspace);
        assertTrue(current.items().containsKey("METADATA:package"), "Exercise the actual retained mapping path");
        String retainedIdentities = json.write(identities);
        ExchangeDocument projected = freshAdapter.exportDocument(connection, current, workspace, identities, previous);
        assertEquals(retainedIdentities, json.write(identities), "Export cannot rewrite retained identities or their accepted evidence");
        return projected;
    }

    private Artifact select(Artifact incoming, String version, EndpointOverride endpoint, String canonical) throws Exception {
        Connection connection = connection(SparxMappingProfile.PROFILE, version);
        var state = new InternalState("repository", "scope", "draft", "head", 1, null, null);
        var authority = new IntegrationContext(connection.id(), connection.authority(), connection.externalScope(), state, "alice", connection.connectorId(), version);
        UUID operationId = UUID.randomUUID();
        var change = new IntegrationChange("change", "RELATION:r", ChangeKind.ADD, Set.of(), null, null, null, incoming, List.of());
        var operation = new Operation(operationId, connection.id(), authority, "INBOUND", OperationStatus.PREVIEWED, "fp", 0,
                null, List.of(change), null, null, null, null, null, null, null, null, Instant.EPOCH);
        var review = new ReviewedChangeSet(operationId, "fp", Map.of("change", Decision.ACCEPT), "Reviewed type and endpoints",
                Map.of("change", new MappingOverride(canonical, null, null, null)), endpoint == null ? Map.of() : Map.of("change", endpoint));
        Artifact metadata = new Artifact("package", ArtifactKind.METADATA, SparxMappingProfile.PROFILE, "", "", Map.of("identifier", "model"), Map.of());
        var service = new IntegrationService(null, null, domain, null, null, json, null, null, null, null);
        var method = IntegrationService.class.getDeclaredMethod("select", Operation.class, ReviewedChangeSet.class, Map.class, Map.class);
        method.setAccessible(true);
        try {
            @SuppressWarnings("unchecked") Map<String, Artifact> selected = (Map<String, Artifact>) method.invoke(service, operation, review, Map.of("METADATA:package", metadata), Map.of());
            return selected.get("RELATION:r");
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private byte[] archiMateXml() throws Exception {
        return archiMateXml(Map.of());
    }

    private byte[] archiMateXml(Map<String, String> extraElementProperties) throws Exception {
        Map<String, String> element = new TreeMap<>(Map.of("Taxonomy.ElementType", "Process", "taxonomy.id", "CR-HISTORY", "taxonomy.presenceReason", PRESENCE,
                "taxonomy.review.decision", "REVIEWED_ELEMENT", "taxonomy.catalogue.code", "CR-HISTORY"));
        element.putAll(extraElementProperties);
        Set<String> names = new TreeSet<>(modelFields().keySet()); names.addAll(element.keySet()); names.addAll(evidenceFields("USES").keySet());
        names.add("Taxonomy.RelationType"); names.add("Taxonomy.status");
        Map<String, String> definitions = new TreeMap<>(); for (String name : names) definitions.put(name, "p" + definitions.size());
        var xml = new StringBuilder("<model xmlns=\"http://www.opengroup.org/xsd/archimate/3.0/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" identifier=\"imported-model\"><name>Reviewed flood architecture</name>");
        xml.append(properties(modelFields(), definitions));
        xml.append("<elements>");
        for (ElementSpec item : ELEMENTS) {
            xml.append("<element identifier=\"").append(item.id()).append("\" xsi:type=\"").append(item.archimateType()).append("\"><name>")
                    .append(item.title()).append("</name><documentation>").append(item.id().equals("reader") ? "Read the retained dispatch records." : item.title())
                    .append("</documentation>").append(properties(item.id().equals("reader") ? element : Map.of("Taxonomy.ElementType", item.canonical(), "taxonomy.id", item.id()), definitions)).append("</element>");
        }
        xml.append("</elements><relationships>");
        for (String type : new TreeSet<>(TRANSPORTS.keySet())) {
            Map<String, String> fields = new TreeMap<>(evidenceFields(type)); fields.put("Taxonomy.RelationType", type); fields.put("Taxonomy.status", type.equals("USES") ? "proposed" : "accepted");
            fields.put("taxonomy.id", "original-edge-" + type);
            xml.append("<relationship identifier=\"r-").append(type).append("\" xsi:type=\"Association\" source=\"").append(ENDPOINTS.get(type).getFirst())
                    .append("\" target=\"").append(ENDPOINTS.get(type).getLast()).append("\"><documentation>")
                    .append(escape(notes(type))).append("</documentation>").append(properties(fields, definitions)).append("</relationship>");
        }
        xml.append("</relationships><propertyDefinitions>");
        definitions.forEach((name, id) -> xml.append("<propertyDefinition identifier=\"").append(id).append("\" type=\"string\"><name>").append(escape(name)).append("</name></propertyDefinition>"));
        return xml.append("</propertyDefinitions></model>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String properties(Map<String, String> fields, Map<String, String> definitions) {
        var xml = new StringBuilder("<properties>");
        new TreeMap<>(fields).forEach((name, value) -> xml.append("<property propertyDefinitionRef=\"").append(definitions.get(name)).append("\"><value>").append(escape(value)).append("</value></property>"));
        return xml.append("</properties>").toString();
    }
    private static Map<String, String> modelFields() throws Exception {
        return Map.of("taxonomy.requirementText", REQUIREMENT, "taxonomy.requirementSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(REQUIREMENT.getBytes(StandardCharsets.UTF_8))),
                "taxonomy.snapshotId", "snapshot-before-import", "taxonomy.requirementId", "41", "taxonomy.requirementVersionId", "revision-3",
                "taxonomy.canonicalGraphSha256", "e".repeat(64), "taxonomy.reviewState", "Reviewed", "external.reviewedBy", "Architecture review group");
    }
    private static Map<String, String> evidenceFields(String type) {
        return Map.ofEntries(Map.entry("taxonomy.provenance.version", "1"), Map.entry("taxonomy.provenance.origin", "REQUIREMENT_DERIVED"),
                Map.entry("taxonomy.provenance.evidenceCount", "1"), Map.entry("taxonomy.provenance.derivationReason", notes(type)),
                Map.entry("taxonomy.provenance.evidence.0.contribution.source.id", "CR-HISTORY"),
                Map.entry("taxonomy.provenance.evidence.0.contribution.text", "Read the retained dispatch history."),
                Map.entry("taxonomy.provenance.evidence.0.contribution.quote", "retain the dispatch history"),
                Map.entry("taxonomy.provenance.evidence.0.contribution.condition", "An authorized operator requests the history."),
                Map.entry("taxonomy.provenance.evidence.0.direction", "INCOMING"), Map.entry("taxonomy.provenance.evidence.0.type", type),
                Map.entry("taxonomy.provenance.evidence.0.decision.contribution", "Store the retained notification record."),
                Map.entry("taxonomy.provenance.evidence.0.decision.quote", "the configured threshold is exceeded"),
                Map.entry("taxonomy.provenance.evidence.0.decision.condition", "A notification was dispatched."),
                Map.entry("taxonomy.provenance.evidence.0.decision.necessity", "MANDATORY"),
                Map.entry("taxonomy.provenance.evidence.0.decision.outcome", "MATCH"), Map.entry("taxonomy.review.decision", "ACCEPTED_FROM_SOURCE"));
    }
    private static String notes(String type) { return type + ": The history reader accesses the dispatch record. Source quote: retain the dispatch history. Target quote: the configured threshold is exceeded. Condition: A notification was dispatched."; }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String sha256(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static String elementTitle(String id) { return ELEMENTS.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow().title(); }
    private static Relation relation(ExchangeDocument document, String type) { return document.relations().stream().filter(r -> type.equals(r.extensions().get("canonicalType"))).findFirst().orElseThrow(); }
    private static Connection connection(String profile, String version) { return new Connection(UUID.randomUUID(), "USER:alice", "Export copy", profile, version, AuthorityMode.BIDIRECTIONAL, new ExternalScope("FILE", "model", null), null, null, 0, null, null, "alice"); }
    private record Imported(String dsl, ExchangeDocument source, Map<String, Artifact> items, Connection connection) { }
    private record BoundDelivery(ExchangeDocument document, List<Identity> identities) { }
    private record ElementSpec(String id, String archimateType, String canonical, String title) { }
}
