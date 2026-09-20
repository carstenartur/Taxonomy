package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.taxonomy.exchange.OslcRdf.DCT;
import static com.taxonomy.exchange.sparx.SparxOslcAmCodec.*;
import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;
import static com.taxonomy.exchange.sparx.SparxSemanticExchangeAssembler.*;

/** Bounded PCS contract parser. Unknown links are evidence, never requests. */
final class SparxAmV2Parser {
    private static final Set<String> ROOT_PREFIXES = Set.of("pk_", "el_", "dg_");
    private SparxAmV2Parser() {}

    static List<Model> chain(List<Page> pages) {
        if (pages.isEmpty() || pages.size() > MAX_PAGES) throw invalid("SPARX_AM_PAGE_LIMIT");
        Set<URI> seen = new HashSet<>(); List<Model> models = new ArrayList<>();
        URI endpoint = pages.getFirst().resource(); long bytes = 0, statements = 0;
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (!seen.add(page.resource()) || !Objects.equals(endpoint.getScheme(), page.resource().getScheme())
                    || !Objects.equals(endpoint.getAuthority(), page.resource().getAuthority())
                    || !endpoint.getPath().equals(page.resource().getPath())) throw invalid("SPARX_AM_PAGING");
            if ((bytes += page.content().length) > ExchangeXml.MAX_BYTES) throw invalid("REMOTE_RESPONSE_LIMIT");
            Model model = SparxOslcAmCodec.parse(page.content(), page.resource());
            if ((statements += model.size()) > 100000) throw invalid("ITEM_LIMIT");
            for (org.apache.jena.rdf.model.Resource subject : model.listSubjects().toList()) {
                if (subject.listProperties().toList().size() > 128) throw invalid("SPARX_AM_PROPERTY_LIMIT");
            }
            URI next = new SparxOslcAmCodec().nextPage(page.content(), page.resource());
            if (!Objects.equals(next, i + 1 < pages.size() ? pages.get(i + 1).resource() : null)) throw invalid("SPARX_AM_INCOMPLETE");
            models.add(model);
        }
        return models;
    }
    static List<String> roots(List<Page> pages, URI base) {
        SortedMap<String,String> ids = new TreeMap<>();
        for (Model model : chain(pages)) for (org.apache.jena.rdf.model.Resource r : model.listResourcesWithProperty(RDF.type, model.createResource(AM + "Resource")).toList()) {
            String id = literal(r, DCT + "identifier", true), guid = prefixedGuid(id, ROOT_PREFIXES);
            requireRootUri(r, base, id);
            if (ids.putIfAbsent(guid, id) != null) throw invalid("DUPLICATE_IDENTITY");
        }
        if (ids.size() > ExchangeXml.MAX_ARTIFACTS) throw invalid("ITEM_LIMIT");
        List<String> result = ids.values().stream().filter(id -> !id.startsWith("dg_")).toList();
        if (result.size() > 250) throw invalid("SPARX_AM_ENRICHMENT_LIMIT");
        return result;
    }
    static List<String> featureIds(SparxOslcAmCodec.Collection c, URI base) {
        return parseFeatures(c, base, new ArrayList<>(), new FragmentBudget()).stream().map(f -> f.extensions().get("externalIdentifier"))
                .sorted(Comparator.comparing(id -> prefixedGuid(id, prefixes(c)))).toList();
    }
    static ExchangeDocument read(List<Page> pages, List<SparxOslcAmCodec.Collection> collections, URI base) {
        roots(pages, base);
        List<SparxSemanticExchangeAssembler.Resource> resources = new ArrayList<>();
        FragmentBudget preservation = new FragmentBudget();
        List<Feature> features = new ArrayList<>(); List<Connector> connectors = new ArrayList<>(); List<MappingLoss> losses = new ArrayList<>();
        for (Model model : chain(pages)) {
            var values = model.listResourcesWithProperty(RDF.type, model.createResource(AM + "Resource")).toList();
            if (values.isEmpty() && model.listStatements(null, model.createProperty(DCT + "identifier"), (RDFNode)null).hasNext()) throw invalid("SPARX_AM_RESOURCE_REQUIRED");
            for (org.apache.jena.rdf.model.Resource r : values) {
                String external = literal(r, DCT + "identifier", true), id = prefixedGuid(external, ROOT_PREFIXES);
                if (external.startsWith("dg_")) { losses.add(loss(id, "diagram", "SPARX_LAYOUT_EXCLUDED", LossDisposition.UNSUPPORTED)); continue; }
                String type = literal(r, DCT + "type", true), parent = literal(r, SS + "parentresourceidentifier", false);
                boolean pkg = external.startsWith("pk_"); if (pkg && !type.equals("Package")) throw invalid("SPARX_AM_TYPE");
                Map<String,String> attributes = new TreeMap<>(), extensions = new TreeMap<>();
                extensions.put("externalIdentifier", external); extensions.put("uri", r.getURI());
                List<String> stereotypes = new ArrayList<>();
                for (Statement s : r.listProperties(model.createProperty(SS + "stereotype")).toList()) {
                    if (s.getObject().isLiteral()) stereotypes.add(s.getString());
                    else for (Statement name : s.getResource().listProperties(model.createProperty(SS + "name")).toList()) {
                        if (!name.getObject().isLiteral()) throw invalid("SPARX_AM_PROPERTY");
                        stereotypes.add(name.getString());
                    }
                }
                if (stereotypes.size() > 32) throw invalid("SPARX_AM_PROPERTY_LIMIT");
                if (stereotypes.size() == 1) extensions.put("stereotype", stereotypes.getFirst());
                if (r.hasProperty(model.createProperty(SS + "stereotype")) && stereotypes.size() != 1) {
                    extensions.put("stereotypeMapping", "UNMAPPED");
                    losses.add(loss(id, "stereotype", "SPARX_AM_STEREOTYPE_UNMAPPED", LossDisposition.PRESERVED_EXTENSION));
                }
                properties(r, id, Set.of(DCT + "identifier", DCT + "title", DCT + "description", DCT + "type", SS + "parentresourceidentifier"), attributes, extensions, losses, preservation);
                resources.add(new SparxSemanticExchangeAssembler.Resource(id, pkg ? ArtifactKind.SPECIFICATION : type.equals("Requirement") ? ArtifactKind.REQUIREMENT : ArtifactKind.ELEMENT,
                        type.equals("Requirement") ? "Class" : type, literal(r, DCT + "title", true), description(r, id, losses),
                        parent.isEmpty() ? null : prefixedGuid(parent, Set.of("pk_")), null, attributes, extensions));
            }
        }
        Set<String> collectionKeys = new HashSet<>();
        long bytes = pages.stream().mapToLong(p -> p.content().length).sum(), statements = 0;
        int responses = pages.size();
        for (Page p : pages) statements += SparxOslcAmCodec.parse(p.content(), p.resource()).size();
        for (SparxOslcAmCodec.Collection c : collections) {
            if (!collectionKeys.add(c.kind() + ":" + c.owner())) throw invalid("DUPLICATE_IDENTITY");
            for (Page p : c.pages()) { bytes += p.content().length; statements += SparxOslcAmCodec.parse(p.content(), p.resource()).size(); responses++; }
            if (bytes > ExchangeXml.MAX_BYTES) throw invalid("REMOTE_RESPONSE_LIMIT");
            if (statements > 100000 || features.size() + connectors.size() + resources.size() > 10000) throw invalid("ITEM_LIMIT");
            if (responses > 1024) throw invalid("SPARX_AM_REQUEST_LIMIT");
            if (c.kind().equals("linkedresources")) connectors.addAll(parseConnectors(c, base, losses, preservation));
            else features.addAll(parseFeatures(c, base, losses, preservation));
        }
        Set<String> required = new HashSet<>();
        for (String root : roots(pages, base)) {
            required.add("linkedresources:" + root); required.add("taggedvalues:" + root);
            if (root.startsWith("el_")) { required.add("attributes:" + root); required.add("operations:" + root); }
        }
        for (Feature feature : features) {
            String external = feature.extensions().get("externalIdentifier");
            if (feature.type().equals("attribute") || feature.type().equals("operation")) required.add("taggedvalues:" + external);
            if (feature.type().equals("operation")) required.add("parameters:" + external);
        }
        if (!required.equals(collectionKeys)) throw invalid("SPARX_AM_INCOMPLETE");
        String modelId = guid(UUID.nameUUIDFromBytes(base.toString().getBytes(StandardCharsets.UTF_8)).toString());
        Map<String,String> metadata = Map.of("identifier", modelId, "resource", base.toString(),
                "collectionEvidence", "EXHAUSTED_COLLECTIONS_NOT_ATOMIC_OR_AUTHORITATIVE", "resourceCount", "" + resources.size(),
                "featureCount", "" + features.size(), "connectorCount", "" + connectors.stream().map(Connector::id).distinct().count(),
                "responseCount", "" + responses, "byteCount", "" + bytes);
        var evidence = new SparxAmReadEvidence(); pages.forEach(p -> evidence.add("qc", "ROOT", p));
        collections.forEach(c -> c.pages().forEach(p -> evidence.add(c.kind(), prefixedGuid(c.owner(), Set.of("pk_", "el_", "at_", "op_")), p)));
        return assemble(SparxOslcAmCodec.PROFILE, evidence.version(), false, "", metadata, resources, features, connectors, losses);
    }
    private static Set<String> prefixes(SparxOslcAmCodec.Collection c) {
        return switch (c.kind()) {
            case "attributes" -> Set.of("at_"); case "operations" -> Set.of("op_"); case "parameters" -> Set.of("pr_");
            case "taggedvalues" -> c.owner().startsWith("at_") ? Set.of("attv_") : c.owner().startsWith("op_") ? Set.of("optv_") : Set.of("tv_");
            default -> throw invalid("SPARX_AM_RESOURCE");
        };
    }
    private static List<Feature> parseFeatures(SparxOslcAmCodec.Collection c, URI base, List<MappingLoss> losses, FragmentBudget preservation) {
        String owner = prefixedGuid(c.owner(), switch (c.kind()) {
            case "attributes", "operations" -> Set.of("el_"); case "parameters" -> Set.of("op_");
            case "taggedvalues" -> Set.of("pk_", "el_", "at_", "op_"); default -> throw invalid("SPARX_AM_RESOURCE");
        });
        List<Feature> features = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Model model : chain(c.pages())) for (org.apache.jena.rdf.model.Resource r : model.listResourcesWithProperty(model.createProperty(DCT + "identifier")).toList()) {
            String external = literal(r, DCT + "identifier", true), id = prefixedGuid(external, prefixes(c));
            if (!seen.add(id)) throw invalid("DUPLICATE_IDENTITY");
            Map<String,String> attributes = new TreeMap<>(), extensions = new TreeMap<>(); extensions.put("externalIdentifier", external);
            String type = switch(c.kind()) { case "attributes" -> "attribute"; case "operations" -> "operation"; case "parameters" -> "parameter"; default -> "tagged-value"; };
            String position = literal(r, SS + "position", false);
            Integer order = null;
            if (!position.isEmpty()) { if (!position.matches("0|[1-9][0-9]{0,3}")) throw invalid("SPARX_FEATURE_POSITION"); order = Integer.valueOf(position); }
            String classifier = literal(r, SS + "classifierresourceidentifier", false);
            if (!classifier.isEmpty()) extensions.put("classifier", prefixedGuid(classifier, Set.of("el_")));
            Set<String> known = new HashSet<>(Set.of(DCT + "identifier", DCT + "title", SS + "value", SS + "position", SS + "classifierresourceidentifier"));
            if (!type.equals("tagged-value")) known.add(DCT + "description");
            properties(r, id, known, attributes, extensions, losses, preservation);
            features.add(new Feature(id, owner, type, literal(r, DCT + "title", true), type.equals("tagged-value") ? literal(r, SS + "value", false) : description(r, id, losses), order, attributes, extensions));
        }
        return features;
    }
    private static List<Connector> parseConnectors(SparxOslcAmCodec.Collection c, URI base,
            List<MappingLoss> losses, FragmentBudget preservation) {
        String collectionOwner = prefixedGuid(c.owner(), Set.of("pk_", "el_"));
        List<Connector> result = new ArrayList<>();
        for (Model model : chain(c.pages())) {
            Set<org.apache.jena.rdf.model.Resource> consumedDescriptions = new HashSet<>();
            for (org.apache.jena.rdf.model.Resource source : model.listResourcesWithProperty(
                    RDF.type, model.createResource(AM + "Resource")).toList()) {
                String sourceId = literal(source, DCT + "identifier", true);
                String sourceGuid = prefixedGuid(sourceId, Set.of("pk_", "el_"));
                if (source.isURIResource()) requireRootUri(source, base, sourceId);
                for (Statement link : source.listProperties().toList()) {
                    String predicate = link.getPredicate().getURI();
                    if (!predicate.startsWith(SS) || !isRelation(predicate.substring(SS.length()))) continue;
                    if (!link.getObject().isURIResource()) throw invalid("SPARX_AM_ENDPOINT");
                    String target = endpoint(link.getResource().getURI(), base);
                    // rdf:ID on the dynamic property reifies the exact subject/predicate/object triple.
                    var descriptions = model.listResourcesWithProperty(RDF.subject, source).toList().stream()
                            .filter(d -> d.hasProperty(RDF.predicate, link.getPredicate())
                                    && d.hasProperty(RDF.object, link.getObject())).toList();
                    if (descriptions.isEmpty()) throw invalid("SPARX_AM_CONNECTOR");
                    for (org.apache.jena.rdf.model.Resource detail : descriptions) {
                        for (Property property : List.of(RDF.subject, RDF.predicate, RDF.object)) {
                            if (detail.listProperties(property).toList().size() != 1)
                                throw invalid("SPARX_AM_CONNECTOR");
                        }
                        if (!collectionOwner.equals(sourceGuid) && !collectionOwner.equals(target))
                            throw invalid("SPARX_AM_CONNECTOR");
                        String external = literal(detail, DCT + "identifier", true);
                        String id = prefixedGuid(external, Set.of("lt_"));
                        Map<String,String> attributes = new TreeMap<>(), extensions = new TreeMap<>();
                        extensions.put("externalIdentifier", external);
                        String title = literal(detail, DCT + "title", false);
                        String text = description(detail, id, losses);
                        if (!title.isEmpty()) attributes.put("name", title);
                        if (!text.isEmpty()) attributes.put("description", text);
                        String direction = literal(detail, SS + "direction", true);
                        properties(detail, id, Set.of(DCT + "identifier", DCT + "title", DCT + "description",
                                SS + "direction", RDF.subject.getURI(), RDF.predicate.getURI(), RDF.object.getURI()),
                                attributes, extensions, losses, preservation);
                        result.add(new Connector(id, sourceGuid, target, predicate.substring(SS.length()),
                                direction, attributes, extensions));
                        consumedDescriptions.add(detail);
                    }
                }
            }
            // Every declaration on this page must describe an associated connector on this page.
            // Matching the RDF subject also catches an orphan reusing an already consumed GUID.
            for (Statement declaration : model.listStatements(null,
                    model.createProperty(DCT + "identifier"), (RDFNode) null).toList()) {
                if (declaration.getObject().isLiteral() && declaration.getString().startsWith("lt_")
                        && !consumedDescriptions.contains(declaration.getSubject()))
                    throw invalid("SPARX_AM_CONNECTOR");
            }
        }
        return result;
    }
    private static String endpoint(String uri, URI base) {
        URI value; try { value = URI.create(uri); } catch (IllegalArgumentException e) { throw invalid("SPARX_AM_ENDPOINT"); }
        String prefix = base.getPath() + "resource/";
        if (!Objects.equals(base.getScheme(), value.getScheme()) || !Objects.equals(base.getAuthority(), value.getAuthority()) || value.getQuery() != null || value.getFragment() != null
                || !value.getPath().startsWith(prefix) || !value.getPath().endsWith("/")) throw invalid("SPARX_AM_ENDPOINT");
        return prefixedGuid(value.getPath().substring(prefix.length(), value.getPath().length() - 1), Set.of("pk_", "el_"));
    }
    private static void requireRootUri(org.apache.jena.rdf.model.Resource r, URI base, String id) {
        if (!r.isURIResource() || !base.resolve("resource/" + id.replace("{", "%7B").replace("}", "%7D") + "/").equals(URI.create(r.getURI()))) throw invalid("SPARX_AM_IDENTITY");
    }
    private static String description(org.apache.jena.rdf.model.Resource r, String id, List<MappingLoss> losses) {
        String value = literal(r, DCT + "description", false); Statement s = r.getProperty(r.getModel().createProperty(DCT + "description"));
        if (s != null && RDF.dtXMLLiteral.getURI().equals(s.getLiteral().getDatatypeURI())) {
            value = ExchangeXml.parse(("<div>" + value + "</div>").getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent();
            losses.add(loss(id, "description", "SPARX_AM_TEXT_FLATTENED", LossDisposition.TRANSFORMED));
        }
        return value;
    }
    private static void properties(org.apache.jena.rdf.model.Resource r, String id, Set<String> known,
            Map<String,String> attributes, Map<String,String> extensions, List<MappingLoss> losses, FragmentBudget preservation) {
        Map<String,List<RDFNode>> unknown = new TreeMap<>();
        for (Statement s : r.listProperties().toList()) {
            String predicate = s.getPredicate().getURI(); if (known.contains(predicate) || predicate.equals(RDF.type.getURI())) continue;
            String scalar = predicate.startsWith(SS) ? FEATURE_SCALARS.stream().filter(k -> k.equalsIgnoreCase(predicate.substring(SS.length()))).findFirst().orElse(null) : null;
            if (scalar != null && s.getObject().isLiteral()) {
                if (attributes.putIfAbsent("ea:" + scalar, s.getString()) != null) throw invalid("SPARX_AM_PROPERTY");
            } else unknown.computeIfAbsent(predicate, unused -> new ArrayList<>()).add(s.getObject());
        }
        unknown.forEach((predicate, values) -> {
            if (values.size() == 1 && values.getFirst().isLiteral()) attributes.put("attribute:" + predicate, values.getFirst().asLiteral().getLexicalForm());
            else extensions.put("rdf:" + predicate, values.stream().map(v -> fragment(v, new HashSet<>(), 0, preservation)).sorted().collect(java.util.stream.Collectors.joining(",", "[", "]")));
            losses.add(loss(id, predicate, "SPARX_PROPERTY_PRESERVED", LossDisposition.PRESERVED_EXTENSION));
        });
    }
    /** Canonical bounded RDF value tree: full URI, lexical form, language and datatype; never Jena blank-node labels. */
    private static String fragment(RDFNode node, Set<org.apache.jena.rdf.model.Resource> visited, int depth, FragmentBudget budget) {
        if (depth > 16 || ++budget.nodes > 100000) throw invalid("SPARX_AM_PROPERTY_LIMIT");
        if (node.isLiteral()) {
            Literal l = node.asLiteral();
            return budget.bound("L" + field(l.getLexicalForm()) + field(l.getLanguage()) + field(Objects.toString(l.getDatatypeURI(), "")));
        }
        var r = node.asResource();
        String identity = budget.bound(node.isURIResource() ? "U" + field(r.getURI()) : "B");
        if (!r.listProperties().hasNext()) return identity;
        if (!visited.add(r)) throw invalid("SPARX_AM_STRUCTURED_CYCLE");
        List<String> parts = new ArrayList<>();
        for (Statement s : r.listProperties().toList()) parts.add(budget.bound(field(s.getPredicate().getURI())) + fragment(s.getObject(), visited, depth + 1, budget));
        visited.remove(r); Collections.sort(parts); return identity + "[" + String.join(",", parts) + "]";
    }
    private static final class FragmentBudget {
        int nodes, characters;
        String bound(String value) {
            characters += value.length();
            if (characters > ExchangeXml.MAX_BYTES) throw invalid("SPARX_AM_PROPERTY_LIMIT");
            return value;
        }
    }
    private static String field(String value) { return value.length() + ":" + value; }
    private static ExchangeFormatException invalid(String code) { return ExchangeXml.invalid(code, "Response does not satisfy the bounded Sparx AM v2 profile"); }
}
