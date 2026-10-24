package com.taxonomy.build;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production images, OCI identity and cold, offline local inference. */
@EnabledIfSystemProperty(named="taxonomy.production.image.skip",matches="false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionImageIT {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final String MODEL_DIR="/opt/taxonomy/models/multilingual-minilm";
 private String commit,image,onnxImage;

 @BeforeAll void buildImage() throws Exception {
  commit=System.getenv("GITHUB_SHA");
  if(commit==null || commit.isBlank()) commit=ExternalDriverSupport.command(List.of("git","rev-parse","HEAD"),"image-source.log",1).strip();
  assertTrue(commit.matches("[0-9a-f]{40}"),"Production image requires exact source commit");
  image=System.getenv().getOrDefault("IMAGE_TAG","taxonomy-ci:"+commit);
  onnxImage=image+"-onnx";
  build(image,List.of(),"production-image.log");
  // Same daemon/BuildKit cache and build arguments reuse the common application layers.
  build(onnxImage,List.of("--target","onnx","--cache-from",image),"production-image-onnx.log");
 }
 private void build(String tag,List<String> extra,String log) throws Exception {
  var command=new ArrayList<>(List.of("docker","build","--progress=plain","--build-arg","BUILD_DATE=1970-01-01T00:00:00Z","--build-arg","VCS_REF="+commit,"--build-arg","VERSION=ci-"+commit,"--tag",tag));
  command.addAll(extra); command.add(".");
  ExternalDriverSupport.command(command,log,25);
 }
 @Test void imageRevisionIdentifiesTheVerifiedSourceCommit() throws Exception {
  for(String tag:List.of(image,onnxImage)) assertEquals(commit,inspect(tag,"{{ index .Config.Labels \"org.opencontainers.image.revision\" }}","revision"));
 }
 @Test void imageRunsAsTheAssignedUnprivilegedIdentity() throws Exception {
  for(String tag:List.of(image,onnxImage)) assertEquals("10001:10001",inspect(tag,"{{ .Config.User }}","user"));
 }
 @Test void onnxImageCarriesOfflineRuntimeDefaults() throws Exception {
  JsonNode env=JSON.readTree(inspect(onnxImage,"{{json .Config.Env}}","environment"));
  var defaults=new HashMap<String,String>();
  for(JsonNode entry:env) { String[] pair=entry.asText().split("=",2); defaults.put(pair[0],pair[1]); }
  assertEquals("LOCAL_ONNX",defaults.get("LLM_PROVIDER"));
  assertEquals("true",defaults.get("TAXONOMY_EMBEDDING_ENABLED"));
  assertEquals("MULTILINGUAL_MINILM_L12",defaults.get("TAXONOMY_EMBEDDING_MODEL_PROFILE"));
  assertEquals(MODEL_DIR,defaults.get("TAXONOMY_EMBEDDING_MODEL_DIR"));
  assertEquals("false",defaults.get("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD"));
  assertEquals("cpu",defaults.get("RUST_FLAVOR"));
  assertEquals("/tmp/djl-cache",defaults.get("DJL_CACHE_DIR"));
  assertTrue(Arrays.asList(defaults.getOrDefault("JAVA_OPTS","").split("\\s+")).contains("-Xmx768m"));
 }
 private String inspect(String tag,String format,String name) throws Exception {
  return ExternalDriverSupport.command(List.of("docker","image","inspect","--format",format,tag),"image-"+name+(tag.equals(onnxImage)?"-onnx":"")+".log",1).strip();
 }

 @Test void onnxImageBuildsAUsableSemanticIndexOnAnOfflineColdStart() throws Exception {
  String container="taxonomy-onnx-acceptance-"+UUID.randomUUID();
  Throwable failure=null;
  try {
   // Only credentials are injected: provider, model and JVM settings must work as shipped.
   // tmpfs mounts are fresh; there are no model/cache binds, published ports or API keys.
   ExternalDriverSupport.command(List.of("docker","run","--detach","--name",container,
     "--network","none","--read-only","--memory","2g","--cpus","2",
     "--tmpfs","/tmp:rw,exec,nosuid,size=768m,mode=1777",
     "--tmpfs","/app/data:rw,nosuid,size=512m,uid=10001,gid=10001,mode=0770",
     "--env","TAXONOMY_ADMIN_PASSWORD="+UUID.randomUUID(),
     "--env","TAXONOMY_REQUIRE_PASSWORD_CHANGE=false",onnxImage),"image-onnx-start.log",1);
   JsonNode status=awaitSemanticReadiness(container);
   assertEquals("MULTILINGUAL_MINILM_L12",status.path("modelProfile").asText());
   assertEquals(MODEL_DIR,status.path("modelUrl").asText());
   JsonNode ai=get(container,"/api/ai-status","ai-status");
   assertEquals("LIMITED",ai.path("level").asText());
   assertTrue(ai.path("available").asBoolean());
   assertTrue(ai.path("limited").asBoolean());
   assertEquals("Local (ONNX embeddings)",ai.path("provider").asText());
   assertTrue(ai.path("availableProviders").toString().contains("\"LOCAL_ONNX\""));
   JsonNode catalogue=get(container,"/api/taxonomy","catalogue");
   assertTrue(catalogue.isArray() && !catalogue.isEmpty(),"Packaged catalogue must be loaded");
   var codes=new HashSet<String>();
   collectCodes(catalogue,codes);
   JsonNode results=get(container,"/api/search/semantic?q=communication%20and%20collaboration&maxResults=5","semantic-search");
   assertTrue(results.isArray() && !results.isEmpty(),"Real local inference must return semantic hits");
   assertTrue(results.size()<=5);
   for(JsonNode result:results) {
    assertFalse(result.path("code").asText().isBlank());
    assertTrue(codes.contains(result.path("code").asText()),"Semantic hit must exist in the actual catalogue: "+result);
   }
  } catch(Exception | AssertionError error) { failure=error; throw error; }
  finally { finish(container,failure); }
 }
 private JsonNode awaitSemanticReadiness(String container) throws Exception {
  long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();
  String last="No response";
  while(System.nanoTime()<deadline) {
   last=curl(container,"/api/embedding/status","embedding-status");
   if(last.endsWith("\n200")) {
    JsonNode status=JSON.readTree(last.substring(0,last.lastIndexOf('\n')));
    String state=status.path("indexState").asText();
    assertNotEquals("FAILED",state,"Local model/index initialization failed: "+status);
    if(status.path("enabled").asBoolean() && status.path("available").asBoolean()
      && status.path("modelAvailable").asBoolean() && status.path("semanticReady").asBoolean()
      && Set.of("READY","PARTIAL","INDEXING_RELATIONS").contains(state)
      && status.path("indexedNodesAtReadiness").asLong()>0) return status;
   }
   Thread.sleep(1000);
  }
  return fail("Offline ONNX semantic node index did not become ready within 10 minutes; last response: "+last);
 }
 private JsonNode get(String container,String path,String log) throws Exception {
  String response=curl(container,path,log);
  int split=response.lastIndexOf('\n');
  assertTrue(split>=0 && response.substring(split+1).equals("200"),"Expected HTTP 200: "+response);
  return JSON.readTree(response.substring(0,split));
 }
 private String curl(String container,String path,String log) throws Exception {
  // Resolve the ephemeral credential inside the container so it never enters curl logs.
  return ExternalDriverSupport.command(List.of("docker","exec",container,"sh","-c",
    "curl --silent --show-error --max-time 10 --user \"admin:$TAXONOMY_ADMIN_PASSWORD\" --header 'Accept: application/json' --write-out '\\n%{http_code}' \"$1\" || [ \"$?\" = 7 ]",
    "sh","http://127.0.0.1:8080"+path),"image-onnx-"+log+".log",1).stripTrailing();
 }
 private void collectCodes(JsonNode nodes,Set<String> codes) {
  for(JsonNode node:nodes) {
   if(!node.path("code").asText().isBlank()) codes.add(node.path("code").asText());
   collectCodes(node.path("children"),codes);
  }
 }
 private void finish(String container,Throwable failure) throws Exception {
  // Preserve the original assertion when diagnostics or cleanup also fail.
  for(String operation:List.of("logs","rm")) {
   try {
    var command=new ArrayList<>(List.of("docker",operation));
    if(operation.equals("rm")) command.add("--force");
    command.add(container);
    ExternalDriverSupport.command(command,"image-onnx-"+operation+".log",1);
   } catch(Exception | AssertionError cleanup) {
    if(failure!=null) failure.addSuppressed(cleanup);
    else failure=cleanup;
   }
  }
  if(failure instanceof Exception exception) throw exception;
  if(failure instanceof AssertionError assertion) throw assertion;
 }
}
