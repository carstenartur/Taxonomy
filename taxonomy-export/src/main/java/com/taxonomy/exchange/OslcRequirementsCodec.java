package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.vocabulary.RDF;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.taxonomy.exchange.OslcRdf.DCT;
import static com.taxonomy.exchange.OslcRdf.OSLC;
import static com.taxonomy.exchange.OslcRdf.RM;

/** OSLC RM RDF/XML profile, parsed as RDF instead of depending on one producer's XML spelling. */
public final class OslcRequirementsCodec {
    public static final String PROFILE = OslcRdf.RM_PROFILE;
    public ExchangeDocument read(byte[] content, URI base, String version, String configuration) {
        Model model = parse(content, base); List<Artifact> artifacts = new ArrayList<>(); List<MappingLoss> losses = new ArrayList<>();
        List<Resource> subjects = model.listResourcesWithProperty(RDF.type, model.createResource(RM + "Requirement")).toList();
        subjects.addAll(model.listResourcesWithProperty(RDF.type, model.createResource(RM + "RequirementCollection")).toList());
        for (Resource subject : subjects.stream().distinct().sorted(Comparator.comparing(Resource::toString)).toList()) {
            if (!subject.isURIResource() || subject.getURI().length() > 2048) throw ExchangeXml.invalid("INVALID_IDENTITY", "An OSLC requirement needs a bounded stable resource URI");
            Map<String, String> attributes = new TreeMap<>(), extensions = new TreeMap<>();
            Model evidence = ModelFactory.createDefaultModel();
            for (Statement statement : subject.listProperties().toList()) {
                if (statement.getObject().isAnon()) throw ExchangeXml.invalid("OSLC_BLANK_NODE_MAPPING", "This profile requires explicit mapping of structured blank-node attributes");
                evidence.add(statement);
                String value = NodeFmtLib.strNT(statement.getObject().asNode());
                attributes.put(statement.getPredicate().getURI() + ":" + ReqifExchangeCodec.digest(value.getBytes(StandardCharsets.UTF_8)), value);
            }
            String title = literal(subject, DCT + "title"), body = literal(subject, DCT + "description");
            if (title.isBlank()) losses.add(new MappingLoss(subject.getURI(), "title", "REQUIREMENT_MAPPING_REQUIRED", LossDisposition.UNSUPPORTED, "Resource has no mapped title"));
            Statement description = subject.getProperty(model.createProperty(DCT + "description"));
            if (description != null && description.getObject().isLiteral() && RDF.dtXMLLiteral.getURI().equals(description.getLiteral().getDatatypeURI()))
                body = ExchangeXml.parse(body.getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent();
            extensions.put("rdfXml", new String(serialize(evidence), StandardCharsets.UTF_8)); extensions.put("uri", subject.getURI());
            if (configuration != null) extensions.put("configuration", configuration);
            boolean requirement = subject.hasProperty(RDF.type, model.createResource(RM + "Requirement"));
            artifacts.add(new Artifact(subject.getURI(), requirement ? ArtifactKind.REQUIREMENT : ArtifactKind.SPECIFICATION,
                    RM + (requirement ? "Requirement" : "RequirementCollection"), title, body, attributes, extensions));
        }
        if (artifacts.size() > ExchangeXml.MAX_ARTIFACTS) throw ExchangeXml.invalid("ITEM_LIMIT", "OSLC response exceeds the object limit");
        return new ExchangeDocument(PROFILE, "1", version == null ? ReqifExchangeCodec.digest(content) : version, false,
                new String(ExchangeXml.write(ExchangeXml.parse(content)), StandardCharsets.UTF_8), artifacts, List.of(), List.of(), Map.of("resource", base.toString()), losses);
    }
    public DiscoveryResult discover(byte[] content, URI base, String etag, String configuration) {
        Model model = parse(content, base); List<DiscoveryResource> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Statement statement : model.listStatements().toList()) {
            String predicate = statement.getPredicate().getURI();
            if (Set.of(OSLC + "serviceProvider", OSLC + "queryBase", OSLC + "nextPage", "http://www.w3.org/2000/01/rdf-schema#member").contains(predicate) && statement.getObject().isURIResource()) {
                String uri = statement.getResource().getURI(); if (seen.add(uri)) result.add(new DiscoveryResource(uri, predicate, "", etag, configuration));
            }
        }
        return new DiscoveryResult(result, false, etag);
    }
    public byte[] write(ExchangeDocument document) {
        Model result = ModelFactory.createDefaultModel();
        for (Artifact artifact : document.artifacts()) {
            if (artifact.extensions().containsKey("rdfXml")) result.add(parse(artifact.extensions().get("rdfXml").getBytes(StandardCharsets.UTF_8), URI.create(artifact.id())));
            Resource subject = result.createResource(artifact.id()); subject.addProperty(RDF.type, result.createResource(artifact.type()));
            replaceLiteral(subject, DCT + "title", artifact.title());
            String existing = literal(subject, DCT + "description");
            Statement original = subject.getProperty(result.createProperty(DCT + "description"));
            if (original != null && original.getObject().isLiteral() && RDF.dtXMLLiteral.getURI().equals(original.getLiteral().getDatatypeURI()))
                existing = ExchangeXml.parse(existing.getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent();
            if (!existing.equals(artifact.text())) replaceLiteral(subject, DCT + "description", artifact.text());
        }
        return serialize(result);
    }
    private static void replaceLiteral(Resource subject, String predicate, String value) {
        if (literal(subject, predicate).equals(value)) return;
        Property property = subject.getModel().createProperty(predicate); subject.removeAll(property).addLiteral(property, value);
    }
    private static String literal(Resource subject, String predicate) {
        List<Statement> values = subject.listProperties(subject.getModel().createProperty(predicate)).toList();
        if (values.isEmpty()) return "";
        if (values.stream().anyMatch(s -> !s.getObject().isLiteral())) throw ExchangeXml.invalid("OSLC_LITERAL_REQUIRED", "Mapped OSLC text must be a literal");
        return values.stream().sorted(Comparator.comparing(s -> s.getLiteral().getLanguage())).findFirst().orElseThrow().getString();
    }
    public static Model parse(byte[] content, URI base) {
        // Eliminate DTD/entities before RDF parsing. No remote context or SPARQL is evaluated.
        String safe = new String(ExchangeXml.write(ExchangeXml.parse(content)), StandardCharsets.UTF_8);
        try {
            Model model = ModelFactory.createDefaultModel();
            RDFParser.fromString(safe, Lang.RDFXML).base(base.toString()).errorHandler(ErrorHandlerFactory.errorHandlerStrictSilent()).parse(model);
            if (model.size() > 100000) throw ExchangeXml.invalid("ITEM_LIMIT", "RDF response exceeds the triple limit");
            return model;
        } catch (Exception rejected) { throw ExchangeXml.invalid("INVALID_RDF", "Response does not satisfy the declared RDF/XML profile"); }
    }
    private static byte[] serialize(Model model) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); RDFDataMgr.write(out, model, RDFFormat.RDFXML_PLAIN);
        if (out.size() > ExchangeXml.MAX_BYTES) throw ExchangeXml.invalid("PACKAGE_SIZE", "OSLC output exceeds 16 MiB"); return out.toByteArray();
    }
}
