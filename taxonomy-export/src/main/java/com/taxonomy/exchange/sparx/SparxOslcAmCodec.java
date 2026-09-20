package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.taxonomy.exchange.OslcRdf.DCT;
import static com.taxonomy.exchange.OslcRdf.OSLC;

/** Documented PCS AM resource subset. No transport, credentials or proprietary model classes. */
public final class SparxOslcAmCodec {
    public static final String PROFILE = "sparx-oslc-am-2.0";
    public static final String VERSION = "1";
    public static final String AM = "http://open-services.net/ns/am#";
    public static final String SS = "http://www.sparxsystems.com.au/oslc_am#";
    public static final int MAX_PAGES = 20;
    public record Page(URI resource, String etag, byte[] content) {
        public Page { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

    public URI queryBase(byte[] content, URI base) {
        Model model = parse(content, base);
        Set<URI> queries = new HashSet<>();
        for (Resource query : model.listResourcesWithProperty(model.createProperty(OSLC + "resourceType"),
                model.createResource(AM + "Resource")).toList()) {
            for (Statement value : query.listProperties(model.createProperty(OSLC + "queryBase")).toList()) {
                if (!value.getObject().isURIResource()) throw invalid("SPARX_AM_DISCOVERY");
                queries.add(URI.create(value.getResource().getURI()));
            }
        }
        if (queries.size() != 1) throw invalid("SPARX_AM_DISCOVERY");
        return queries.iterator().next();
    }

    public URI nextPage(byte[] content, URI resource) { return nextPage(parse(content, resource)); }
    private URI nextPage(Model model) {
        var values = model.listStatements(null, model.createProperty(OSLC + "nextPage"), (RDFNode) null).toList();
        if (values.size() > 1 || values.size() == 1 && !values.getFirst().getObject().isURIResource())
            throw invalid("SPARX_AM_PAGING");
        return values.isEmpty() ? null : URI.create(values.getFirst().getResource().getURI());
    }

    public ExchangeDocument read(List<Page> pages, URI base) {
        if (pages.isEmpty() || pages.size() > MAX_PAGES) throw invalid("SPARX_AM_PAGE_LIMIT");
        Map<String, Artifact> artifacts = new TreeMap<>();
        Map<String, String> parents = new TreeMap<>();
        List<MappingLoss> losses = new ArrayList<>();
        Set<URI> visited = new HashSet<>();
        StringBuilder versions = new StringBuilder();
        long size = 0;
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            if (!visited.add(page.resource()) || (size += page.content().length) > ExchangeXml.MAX_BYTES)
                throw invalid("SPARX_AM_PAGE_LIMIT");
            Model model = parse(page.content(), page.resource());
            URI next = nextPage(model);
            if (!Objects.equals(next, index + 1 < pages.size() ? pages.get(index + 1).resource() : null))
                throw invalid("SPARX_AM_INCOMPLETE");
            versions.append(page.resource()).append('\n').append(page.etag()).append('\n')
                    .append(ReqifExchangeCodec.digest(page.content())).append('\n');
            List<Resource> resources = model.listResourcesWithProperty(RDF.type, model.createResource(AM + "Resource")).toList();
            if (resources.isEmpty() && !model.isEmpty()) throw invalid("SPARX_AM_RESOURCE_REQUIRED");
            for (Resource resource : resources) {
                String prefixed = literal(resource, DCT + "identifier", true);
                if (!prefixed.matches("(pk|el|dg)_\\{[0-9a-fA-F-]{36}}")) throw invalid("SPARX_GUID_REQUIRED");
                String guid = SparxMappingProfile.guid(prefixed.substring(3));
                if (!resource.isURIResource()) throw invalid("SPARX_AM_IDENTITY");
                URI uri = URI.create(resource.getURI());
                URI expected = base.resolve("resource/" + prefixed.replace("{", "%7B").replace("}", "%7D") + "/");
                if (!expected.equals(uri)) throw invalid("SPARX_AM_IDENTITY");
                if (artifacts.containsKey(guid) || parents.containsKey(guid)) throw invalid("DUPLICATE_IDENTITY");
                String parent = literal(resource, SS + "parentresourceidentifier", false);
                if (!parent.isEmpty() && !parent.matches("pk_\\{[0-9a-fA-F-]{36}}")) throw invalid("SPARX_AM_PARENT");
                parents.put(guid, parent.isEmpty() ? "" : SparxMappingProfile.guid(parent.substring(3)));
                if (prefixed.startsWith("dg_")) {
                    losses.add(loss(guid, "diagram", "SPARX_LAYOUT_EXCLUDED", "Diagram resources are outside the semantic read profile"));
                    continue;
                }
                String type = literal(resource, DCT + "type", true);
                String stereotype = literal(resource, SS + "stereotype", false);
                boolean pkg = prefixed.startsWith("pk_");
                if (pkg && !type.equals("Package")) throw invalid("SPARX_AM_TYPE");
                boolean requirement = type.equals("Requirement");
                ArtifactKind kind = pkg ? ArtifactKind.SPECIFICATION : requirement ? ArtifactKind.REQUIREMENT : ArtifactKind.ELEMENT;
                Map<String, String> attributes = new TreeMap<>(), extensions = new TreeMap<>();
                extensions.put("uri", uri.toString());
                if (!stereotype.isBlank()) extensions.put("stereotype", stereotype);
                String canonical = SparxMappingProfile.elementType(type, stereotype, null);
                if (kind == ArtifactKind.ELEMENT) {
                    if (canonical == null) losses.add(loss(guid, "type", "SPARX_ELEMENT_UNMAPPED", "Reject or explicitly map this EA element type"));
                    else extensions.put("canonicalType", canonical);
                }
                for (Statement statement : resource.listProperties().toList()) {
                    String property = statement.getPredicate().getURI();
                    if (Set.of(RDF.type.getURI(), DCT + "title", DCT + "description", DCT + "type", DCT + "identifier", SS + "parentresourceidentifier").contains(property)) continue;
                    if (statement.getObject().isLiteral()) {
                        if (attributes.putIfAbsent(property, statement.getString()) != null) throw invalid("SPARX_AM_PROPERTY");
                    } else losses.add(loss(guid, property, "SPARX_AM_FEATURE_NOT_MAPPED", "Linked or structured feature is not imported by this profile"));
                }
                String description = literal(resource, DCT + "description", false);
                Statement body = resource.getProperty(model.createProperty(DCT + "description"));
                if (body != null && RDF.dtXMLLiteral.getURI().equals(body.getLiteral().getDatatypeURI())) {
                    description = ExchangeXml.parse(("<div>" + description + "</div>").getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent();
                    losses.add(new MappingLoss(guid, "description", "SPARX_AM_TEXT_FLATTENED", LossDisposition.TRANSFORMED, "XML description was converted to plain text"));
                }
                artifacts.put(guid, new Artifact(guid, kind, requirement ? "Class" : type,
                        literal(resource, DCT + "title", true), description, attributes, extensions));
                if (artifacts.size() > ExchangeXml.MAX_ARTIFACTS) throw invalid("ITEM_LIMIT");
            }
        }
        String modelId = SparxMappingProfile.guid(UUID.nameUUIDFromBytes(base.toString().getBytes(StandardCharsets.UTF_8)).toString());
        List<Placement> placements = new ArrayList<>();
        int position = 0;
        for (Artifact artifact : artifacts.values()) {
            String parent = parents.get(artifact.id());
            if (!parent.isEmpty() && (!artifacts.containsKey(parent) || artifacts.get(parent).kind() != ArtifactKind.SPECIFICATION))
                throw invalid("SPARX_AM_PARENT_MISSING");
            Set<String> seen = new HashSet<>(); String ancestor = artifact.id();
            while (ancestor != null && !ancestor.isEmpty()) {
                if (!seen.add(ancestor) || seen.size() > 80) throw invalid("HIERARCHY_CYCLE");
                ancestor = parents.get(ancestor);
            }
            placements.add(new Placement("placement:" + artifact.id(), modelId,
                    parent.isEmpty() ? null : "placement:" + parent, artifact.id(), position++, Map.of()));
        }
        losses.add(loss(null, "features", "SPARX_AM_FEATURES_NOT_FETCHED",
                "This read profile imports package/element properties only; connectors, tags, attributes, operations and diagrams require a separate supported profile"));
        return new ExchangeDocument(PROFILE, VERSION, ReqifExchangeCodec.digest(versions.toString().getBytes(StandardCharsets.UTF_8)),
                false, "", List.copyOf(artifacts.values()), List.of(), placements,
                Map.of("identifier", modelId, "resource", base.toString(), "collectionEvidence", "EXHAUSTED_PAGES_NOT_AUTHORITATIVE"), losses);
    }

    private static Model parse(byte[] content, URI base) {
        var xml = ExchangeXml.parse(content);
        for (var element : ExchangeXml.all(xml, "*", "*")) {
            if (Set.of("useridentifier", "accesstoken", "refreshtoken").contains(element.getLocalName().toLowerCase(Locale.ROOT)))
                throw invalid("SPARX_AM_CREDENTIAL_REFLECTION");
            // PCS documentation spells GUID braces literally; RDF IRIs need escaping.
            for (String name : List.of("about", "resource")) if (element.hasAttributeNS(OslcRdf.RDF, name)) {
                String uri = element.getAttributeNS(OslcRdf.RDF, name);
                if (uri.toLowerCase(Locale.ROOT).matches(".*[?&](useridentifier|token|access_token|password)=.*"))
                    throw invalid("SPARX_AM_CREDENTIAL_REFLECTION");
                element.setAttributeNS(OslcRdf.RDF, "rdf:" + name, uri.replace("{", "%7B").replace("}", "%7D"));
            }
        }
        return OslcRequirementsCodec.parse(ExchangeXml.write(xml), base);
    }
    private static String literal(Resource resource, String property, boolean required) {
        var values = resource.listProperties(resource.getModel().createProperty(property)).toList();
        if (values.size() > 1 || values.size() == 1 && !values.getFirst().getObject().isLiteral()) throw invalid("SPARX_AM_PROPERTY");
        String value = values.isEmpty() ? "" : values.getFirst().getString();
        if (required && value.isBlank()) throw invalid("SPARX_AM_PROPERTY");
        return value;
    }
    private static MappingLoss loss(String id, String field, String code, String detail) {
        return new MappingLoss(id, field, code, LossDisposition.UNSUPPORTED, detail);
    }
    private static ExchangeFormatException invalid(String code) { return ExchangeXml.invalid(code, "Response does not satisfy the bounded Sparx AM resource profile"); }
}
