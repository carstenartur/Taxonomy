package com.taxonomy.interop.oslc;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.exchange.sparx.SparxOslcAmCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.sparx.SparxOslcAmReader;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SparxOslcAmReaderTest {
    private static final String TOKEN = "{22222222-2222-4222-8222-222222222222}";
    private static final String ID = "el_{11111111-1111-4111-8111-111111111111}";
    private HttpServer server;
    private OslcTransport transport;
    private SparxOslcAmReader reader;
    private URI base;
    private final AtomicInteger calls = new AtomicInteger();
    private final RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
    private Connection connection() { return new Connection(UUID.randomUUID(), "USER:alice", "PCS contract fixture",
            SparxOslcAmCodec.PROFILE, "1", AuthorityMode.IMPORT_COPY, new ExternalScope("PCS", base.toString(), null),
            null, "pcs", 0, null, null, "alice"); }
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model/oslc/am/");
        var profiles = new OslcRemoteProfiles();
        profiles.setRemotes(Map.of("pcs", new OslcRemoteProfiles.RemoteProfile("repo", "USER:alice", base, "PCS_TOKEN", true, true)));
        var repositories = mock(SystemRepositoryService.class); var repository = new SystemRepository();
        repository.setOwnerType(RepositoryOwnerType.USER); repository.setOwnerId("alice");
        when(repositories.getRepository("repo")).thenReturn(repository);
        transport = new OslcTransport(profiles, repositories, new MockEnvironment().withProperty("PCS_TOKEN", TOKEN));
        reader = new SparxOslcAmReader(transport);
        serve("sp/", new OslcRdf().link(base.resolve("query").toString(), OslcRdf.OSLC + "resourceType", SparxOslcAmCodec.AM + "Resource")
                .link(base.resolve("query").toString(), OslcRdf.OSLC + "queryBase", base.resolve("qc/").toString()).xml(), 200);
    }
    @AfterEach void stop() { transport.close(); server.stop(0); }

    @Test void realHttpUsesPcsTokenAndReturnsOnlySanitizedDurableEvidence() {
        String xml = new String(resource("Observation reader"), StandardCharsets.UTF_8)
                .replace("</rdf:Description>", "<ss:stereotype xmlns:ss=\"" + SparxOslcAmCodec.SS + "\">"
                        + "<ss:stereotypename><ss:name>CoreService</ss:name></ss:stereotypename>"
                        + "</ss:stereotype></rdf:Description>");
        serve("qc/", xml.getBytes(StandardCharsets.UTF_8), 200);
        var result = reader.read(context, connection(), base.resolve("sp/").toString(), null);
        assertEquals(2, calls.get()); assertEquals(1, result.artifacts().size());
        assertFalse(result.completeScope());
        assertEquals("CoreService", result.artifacts().getFirst().extensions().get("canonicalType"));
        assertFalse(result.toString().contains(TOKEN));
        assertFalse(result.toString().contains("useridentifier"));
    }
    @Test void noPartialPreviewAfterAuthenticationFailureAndNoRedirectFollowing() {
        serve("qc/", new byte[0], 401);
        assertEquals("REMOTE_UNAUTHORIZED", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), base.resolve("sp/").toString(), null)).code());
        server.removeContext(base.getPath() + "qc/");
        serve("qc/", new byte[0], 302);
        assertEquals("REMOTE_MOVED", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), base.resolve("sp/").toString(), null)).code());
    }
    @Test void detectsStaleEvidenceAndRejectsCredentialUrlsBeforeSending() {
        serve("qc/", resource("Changed"), 200);
        assertEquals("REMOTE_STALE", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), base.resolve("sp/").toString(), "old-content-version")).code());
        int before = calls.get();
        assertThrows(IntegrationProblem.class, () -> reader.validate(context, connection(), "qc/?useridentifier=secret"));
        assertThrows(IntegrationProblem.class, () -> reader.validate(context, connection(), "qc/?oslc.select=dcterms:title"));
        assertThrows(IntegrationProblem.class, () -> reader.validate(context, connection(), "https://other.example/model/oslc/am/sp/"));
        assertEquals(before, calls.get());
    }
    @Test void refusesTokenReflectionAndPagingLoops() {
        serve("qc/", resource(TOKEN), 200);
        var reflected = assertThrows(IntegrationProblem.class, () -> reader.read(context, connection(), base.resolve("sp/").toString(), null));
        assertFalse(reflected.toString().contains(TOKEN));
        server.removeContext(base.getPath() + "qc/");
        serve("qc/", new OslcRdf().link(base.resolve("qc/").toString(), OslcRdf.OSLC + "nextPage", base.resolve("qc/").toString()).xml(), 200);
        assertThrows(IntegrationProblem.class, () -> reader.read(context, connection(), base.resolve("sp/").toString(), null));
    }
    @Test void followsAllPagesAndRejectsAFailedLaterPageInsteadOfReturningPartialData() {
        var failLastPage = new java.util.concurrent.atomic.AtomicBoolean(false);
        server.createContext(base.getPath() + "qc/", exchange -> {
            boolean second = exchange.getRequestURI().getRawQuery().contains("page=2");
            if (second && failLastPage.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            byte[] content = second ? resource("Second").clone() : resource("First");
            String xml = new String(content, StandardCharsets.UTF_8);
            if (second) xml = xml.replace("11111111-1111-4111-8111-111111111111", "33333333-3333-4333-8333-333333333333");
            else xml = xml.replace("</rdf:RDF>", "<rdf:Description rdf:about=\"" + base + "qc/\">"
                    + "<oslc:nextPage xmlns:oslc=\"" + OslcRdf.OSLC + "\" rdf:resource=\"" + base + "qc/?page=2\"/>"
                    + "</rdf:Description></rdf:RDF>");
            content = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml");
            exchange.sendResponseHeaders(200, content.length); exchange.getResponseBody().write(content); exchange.close();
        });
        var result = reader.read(context, connection(), "qc/", null);
        assertEquals(2, result.artifacts().size()); assertFalse(result.completeScope());
        failLastPage.set(true);
        assertEquals("REMOTE_UNAVAILABLE", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), "qc/", null)).code());
    }

    private Connection versionTwo() {
        var c = connection(); return new Connection(c.id(), c.organizationId(), c.displayName(), c.connectorId(), "2", c.authority(), c.externalScope(), c.projectId(), c.remoteProfile(), c.revision(), c.checkpointId(), c.activeOperationId(), c.createdBy());
    }
    @Test void versionTwoReadsEveryFeatureAndDetectsChangesWithUnchangedRoot() {
        serve("qc/", resource("Fixed root"), 200);
        for (String kind : List.of("linkedresources", "attributes", "operations")) serve(kind + "/" + ID + "/", new OslcRdf().xml(), 200);
        serve("taggedvalues/" + ID + "/", tag("first"), 200);
        var first = reader.read(context, versionTwo(), "qc/", null);
        assertEquals("2", first.profileVersion()); assertEquals(5, calls.get());
        assertEquals("5", first.metadata().get("responseCount"));
        assertFalse(first.toString().contains(TOKEN)); assertFalse(first.toString().contains("useridentifier"));
        server.removeContext(base.getPath() + "taggedvalues/" + ID + "/");
        serve("taggedvalues/" + ID + "/", tag("changed"), 200);
        assertEquals("REMOTE_STALE", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, versionTwo(), "qc/", first.externalVersion())).code());
    }
    @Test void versionTwoUsesOneInjectedAggregateBudgetAndClock() {
        serve("qc/", resource("Fixed root"), 200);
        for (String kind : List.of("linkedresources", "taggedvalues", "attributes", "operations")) serve(kind + "/" + ID + "/", new OslcRdf().xml(), 200);
        var limits = new com.taxonomy.interop.sparx.SparxAmReadBudget.Limits(250, 20, 3, 16777216, 100000, 10000, 30000000000L);
        var bounded = new SparxOslcAmReader(transport, limits, () -> 0L);
        assertEquals("SPARX_AM_REQUEST_LIMIT", assertThrows(IntegrationProblem.class,
                () -> bounded.read(context, versionTwo(), "qc/", null)).code());
        java.util.concurrent.atomic.AtomicLong time = new java.util.concurrent.atomic.AtomicLong();
        var timeout = new SparxOslcAmReader(transport, com.taxonomy.interop.sparx.SparxAmReadBudget.Limits.defaults(), () -> time.getAndAdd(16000000000L));
        assertEquals("REMOTE_TIMEOUT", assertThrows(IntegrationProblem.class,
                () -> timeout.read(context, versionTwo(), "qc/", null)).code());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"qc", "linkedresources", "rootTags", "attributes", "operations", "attributeTags", "operationTags", "parameters"})
    void exhaustsEveryCollectionAndRejectsFailedLaterPages(String selected) {
        var fail = new java.util.concurrent.atomic.AtomicBoolean(false);
        var authorized = new java.util.concurrent.atomic.AtomicBoolean(true);
        String at="at_{44444444-4444-4444-8444-444444444444}", op="op_{55555555-5555-4555-8555-555555555555}";
        String selectedPath=switch(selected) {
            case "qc" -> "qc/"; case "rootTags" -> "taggedvalues/"+ID+"/";
            case "attributeTags" -> "taggedvalues/"+at+"/"; case "operationTags" -> "taggedvalues/"+op+"/";
            case "parameters" -> "parameters/"+op+"/"; default -> selected+"/"+ID+"/";
        };
        server.createContext(base.getPath(), exchange -> {
            calls.incrementAndGet(); String query=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            if (!("OSLC "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")) || !query.contains("useridentifier="+TOKEN)) authorized.set(false);
            String path=exchange.getRequestURI().getPath().substring(base.getPath().length());
            boolean paged=path.equals(selectedPath), second=query.contains("page=2");
            if(paged && second && fail.get()) {exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            byte[] body;
            if(paged && !second) body=new OslcRdf().link(base.toString(),OslcRdf.OSLC+"nextPage",base+selectedPath.replace("{","%7B").replace("}","%7D")+"?page=2").xml();
            else if(path.equals("qc/")) body=resource("Root");
            else if(path.startsWith("attributes/")) body=feature(at,"Attribute");
            else if(path.startsWith("operations/")) body=feature(op,"Operation");
            else if(path.startsWith("parameters/")) body=feature("pr_{66666666-6666-4666-8666-666666666666}","Parameter");
            else body=new OslcRdf().xml();
            exchange.getResponseHeaders().set("Content-Type","application/rdf+xml"); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);exchange.close();
        });
        var result=reader.read(context,versionTwo(),"qc/",null);
        assertEquals(4,result.artifacts().size()); assertEquals(9,calls.get()); assertTrue(authorized.get());
        assertEquals("9",result.metadata().get("responseCount")); assertFalse(result.completeScope());
        fail.set(true);
        assertEquals("REMOTE_UNAVAILABLE",assertThrows(IntegrationProblem.class,()->reader.read(context,versionTwo(),"qc/",null)).code());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"host", "path", "encoded", "token", "loop", "body", "etag"})
    void featurePagesCannotEscapeOrReflectCredentials(String mode) {
        serve("qc/",resource("Root"),200);
        serve("linkedresources/"+ID+"/",new OslcRdf().xml(),200);
        String endpoint="taggedvalues/"+ID.replace("{","%7B").replace("}","%7D")+"/";
        String next=switch(mode) {
            case "host" -> "https://other.example/model/oslc/am/"+endpoint;
            case "path" -> base+"attributes/"+ID.replace("{","%7B").replace("}","%7D")+"/?page=2";
            case "encoded" -> base+"taggedvalues/%2e%2e/qc/?page=2";
            case "token" -> base+endpoint+"?useridentifier=secret";
            default -> base+endpoint;
        };
        byte[] body=mode.equals("body")?tag(TOKEN):mode.equals("etag")?tag("safe"):
                new OslcRdf().link(base.toString(),OslcRdf.OSLC+"nextPage",next).xml();
        server.createContext(base.getPath()+"taggedvalues/"+ID+"/",exchange->{
            calls.incrementAndGet(); if(mode.equals("etag")) exchange.getResponseHeaders().set("ETag",TOKEN);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        var failure=assertThrows(IntegrationProblem.class,()->reader.read(context,versionTwo(),"qc/",null));
        assertFalse(failure.toString().contains(TOKEN)); assertEquals(3,calls.get());
        assertThrows(IntegrationProblem.class,()->reader.validate(context,versionTwo(),endpoint));
    }
    @Test void aggregateByteStatementObjectAndRootBoundsFailWithoutPartialResults() {
        serve("qc/",resource("Root"),200);
        for(String kind:List.of("linkedresources","attributes","operations")) serve(kind+"/"+ID+"/",new OslcRdf().xml(),200);
        serve("taggedvalues/"+ID+"/",tag("One more object"),200);
        var cases=List.of(
            new com.taxonomy.interop.sparx.SparxAmReadBudget.Limits(250,20,1024,resource("Root").length+1,100000,10000,30000000000L),
            new com.taxonomy.interop.sparx.SparxAmReadBudget.Limits(250,20,1024,16777216,5,10000,30000000000L),
            new com.taxonomy.interop.sparx.SparxAmReadBudget.Limits(250,20,1024,16777216,100000,1,30000000000L));
        for(int i=0;i<cases.size();i++) {
            var bounded=new SparxOslcAmReader(transport,cases.get(i),()->0L);
            assertEquals(i==0?"REMOTE_RESPONSE_LIMIT":"ITEM_LIMIT",assertThrows(IntegrationProblem.class,()->bounded.read(context,versionTwo(),"qc/",null)).code());
        }
        server.removeContext(base.getPath()+"qc/");
        var rdf=new OslcRdf();
        for(int i=1;i<=251;i++) {
            String id="el_{"+String.format("%08x",i)+"-0000-4000-8000-000000000000}";
            String uri=base+"resource/"+id.replace("{","%7B").replace("}","%7D")+"/";
            rdf.type(uri,SparxOslcAmCodec.AM+"Resource").literal(uri,OslcRdf.DCT+"identifier",id).literal(uri,OslcRdf.DCT+"type","Component").literal(uri,OslcRdf.DCT+"title","Root");
        }
        serve("qc/",rdf.xml(),200); int before=calls.get();
        var exception=assertThrows(com.taxonomy.exchange.ExchangeFormatException.class,()->reader.read(context,versionTwo(),"qc/",null));
        assertEquals("SPARX_AM_ENRICHMENT_LIMIT",exception.code()); assertEquals(before+1,calls.get());
    }
    @Test void aggregateObjectBoundCountsStableIdentitiesInsteadOfRepeatedConnectorReports() {
        String target="el_{33333333-3333-4333-8333-333333333333}";
        var roots=new OslcRdf();
        for(String id:List.of(ID,target)) {
            String uri=resourceUri(id);
            roots.type(uri,SparxOslcAmCodec.AM+"Resource").literal(uri,OslcRdf.DCT+"identifier",id)
                    .literal(uri,OslcRdf.DCT+"type","Component").literal(uri,OslcRdf.DCT+"title","Root");
        }
        String from=resourceUri(ID),to=resourceUri(target),detail=base+"link#ID";
        var links=new OslcRdf().type(from,SparxOslcAmCodec.AM+"Resource").literal(from,OslcRdf.DCT+"identifier",ID)
                .link(from,SparxOslcAmCodec.SS+"Dependency",to).type(detail,OslcRdf.RDF+"Statement")
                .link(detail,OslcRdf.RDF+"subject",from).link(detail,OslcRdf.RDF+"predicate",SparxOslcAmCodec.SS+"Dependency")
                .link(detail,OslcRdf.RDF+"object",to).literal(detail,OslcRdf.DCT+"identifier","lt_{44444444-4444-4444-8444-444444444444}")
                .literal(detail,SparxOslcAmCodec.SS+"direction","Source -> Destination");
        serve("qc/",roots.xml(),200);
        for(String id:List.of(ID,target)) for(String kind:List.of("linkedresources","taggedvalues","attributes","operations"))
            serve(kind+"/"+id+"/",kind.equals("linkedresources")?links.xml():new OslcRdf().xml(),200);
        var bounded=new SparxOslcAmReader(transport,new com.taxonomy.interop.sparx.SparxAmReadBudget.Limits(250,20,1024,16777216,100000,3,30000000000L),()->0L);
        var document=bounded.read(context,versionTwo(),"qc/",null);
        assertEquals(2,document.artifacts().size());assertEquals(1,document.relations().size());assertEquals(9,calls.get());
    }
    private String resourceUri(String id) {return base+"resource/"+id.replace("{","%7B").replace("}","%7D")+"/";}

    private byte[] feature(String id,String title) {
        return new OslcRdf().literal(base+"feature",OslcRdf.DCT+"identifier",id).literal(base+"feature",OslcRdf.DCT+"title",title).xml();
    }

    private byte[] tag(String value) {
        String id = "tv_{33333333-3333-4333-8333-333333333333}";
        return new OslcRdf().literal(base + "tag", OslcRdf.DCT + "identifier", id)
                .literal(base + "tag", OslcRdf.DCT + "title", "owner")
                .literal(base + "tag", SparxOslcAmCodec.SS + "value", value).xml();
    }

    private byte[] resource(String title) {
        String uri = base + "resource/" + ID.replace("{", "%7B").replace("}", "%7D") + "/";
        return new OslcRdf().type(uri, SparxOslcAmCodec.AM + "Resource").literal(uri, OslcRdf.DCT + "identifier", ID)
                .literal(uri, OslcRdf.DCT + "type", "Component").literal(uri, OslcRdf.DCT + "title", title)
                .literal(uri, OslcRdf.DCT + "description", "Published flood observations").xml();
    }
    private void serve(String path, byte[] body, int status) {
        server.createContext(base.getPath() + path, exchange -> {
            calls.incrementAndGet();
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (!("OSLC " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || !query.contains("useridentifier=" + TOKEN) || !exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml");
            exchange.getResponseHeaders().set("Location", "https://other.example/");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
}
