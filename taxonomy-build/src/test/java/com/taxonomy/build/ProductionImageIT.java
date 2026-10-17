package com.taxonomy.build;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production image build plus independently reported OCI assertions. */
@EnabledIfSystemProperty(named="taxonomy.production.image.skip",matches="false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionImageIT {
 private String commit,image;
 @BeforeAll void buildImage() throws Exception {
  commit=System.getenv("GITHUB_SHA");
  if(commit==null || commit.isBlank()) commit=ExternalDriverSupport.command(List.of("git","rev-parse","HEAD"),"image-source.log",1).strip();
  assertTrue(commit.matches("[0-9a-f]{40}"),"Production image requires exact source commit");
  image=System.getenv().getOrDefault("IMAGE_TAG","taxonomy-ci:"+commit);
  ExternalDriverSupport.command(List.of("docker","build","--progress=plain","--build-arg","BUILD_DATE=1970-01-01T00:00:00Z","--build-arg","VCS_REF="+commit,"--build-arg","VERSION=ci-"+commit,"--tag",image,"."),"production-image.log",25);
 }
 @Test void imageRevisionIdentifiesTheVerifiedSourceCommit() throws Exception {
  assertEquals(commit,ExternalDriverSupport.command(List.of("docker","image","inspect","--format","{{ index .Config.Labels \"org.opencontainers.image.revision\" }}",image),"image-revision.log",1).strip());
 }
 @Test void imageRunsAsTheAssignedUnprivilegedIdentity() throws Exception {
  assertEquals("10001:10001",ExternalDriverSupport.command(List.of("docker","image","inspect","--format","{{ .Config.User }}",image),"image-user.log",1).strip());
 }
}
