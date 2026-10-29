package com.taxonomy.portfolio.inventory;
import com.taxonomy.extension.api.inventory.*;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.inventory.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.*;
import org.hibernate.cfg.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;

class InventoryReviewConcurrencyTest {
  static Assertion assertion(String id,String candidate,boolean positive,String start,String end) {
    return new Assertion(id,candidate,Criterion.AVAILABILITY,null,positive,
      new Boundary(start,Precision.DATE),new Boundary(end,Precision.DATE),"north","production",AssertionKind.DECLARED,"explicit dated source",Instant.EPOCH);
  }
  static class Evidence implements InventoryEvidencePort {
    final InventoryJournal journal; final IntegrationJson json;
    Evidence(InventoryJournal j,IntegrationJson x){journal=j;json=x;}
    public List<Assertion> assertions(Scope s,String c){return journal.assertions(s,c);}
    public void appendAssertion(Scope s,Assertion a){journal.append(s,a);}
    public SourceRead readSources(Scope s,Demand d,List<String> p){return new SourceRead(List.of(),List.of());}
    public void authorize(Scope s,Demand d,List<SourceEvidence> p){}
    public String fingerprint(Object o){return json.fingerprint(o);}
    public void map(Scope s,Mapping m){journal.map(s,m);}
    public List<Mapping> mappings(Scope s,String c){return journal.mappings(s,c);}
    public void save(Scope s,Comparison c){journal.save(s,c);}
    public Comparison read(Scope s,UUID c){return journal.read(s,c);}
    public List<Comparison> list(Scope s,Long p,String c){return journal.list(s,p,c);}
    public List<Reviewed> reviews(Scope s,UUID c){return journal.reviews(s,c);}
    public Reviewed review(Scope s,UUID c,Review r,Supplier<Void> app){return journal.review(s,c,r,app);}
  }
  enum Writer { ASSERTION, PRODUCT, COVERAGE, REQUIREMENT }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(Writer.class)
  void inputWriterCannotCommitBetweenRevalidationAndApplication(Writer mutation) throws Exception {
    var evaluator=new InventoryCriterionEvaluator();
    var config=new Configuration().setProperty("hibernate.connection.driver_class","org.hsqldb.jdbc.JDBCDriver")
      .setProperty("hibernate.connection.url","jdbc:hsqldb:mem:inventoryReview-"+UUID.randomUUID()+";hsqldb.tx=mvcc")
      .setProperty("hibernate.connection.username","SA").setProperty("hibernate.connection.password","")
      .setProperty("hibernate.connection.pool_size","4").setProperty("hibernate.hbm2ddl.auto","create-drop")
      .setProperty("hibernate.search.enabled","false");
    for(var entity:List.of(InventoryComparisonEntity.class,InventoryReviewEntity.class,InventoryAssertionEntity.class,InventoryMappingEntity.class,
      ProductCatalogEntry.class,ProductTaxonomyCoverage.class,ArchitectureProject.class,SolutionDefinition.class,ProjectSolution.class,SolutionProductCandidate.class,ProjectRequirement.class,ProjectRequirementVersion.class,RequirementAnalysisJob.class,RequirementAnalysisJobItem.class,RequirementAnalysisSnapshot.class,SnapshotReference.class,RequirementElementMapping.class,RequirementRelationMapping.class)) config.addAnnotatedClass(entity);
    try(var factory=config.buildSessionFactory()) {
      var txm=new JpaTransactionManager(factory);var tx=new TransactionTemplate(txm);
      var em=SharedEntityManagerCreator.createSharedEntityManager(factory);
      var mapper=JsonMapper.builder().build();var json=new IntegrationJson(mapper);var codec=new PortfolioJsonCodec(mapper);
      var journal=new InventoryJournal(factory,txm,json);var evidence=new Evidence(journal,json);
      var context=new WorkspaceContext("alice","ws","draft","repo");
      var scope=new Scope("repo",PortfolioScope.key("alice",context),"draft","alice");
      var project=new ArchitectureProject(scope.scopeKey(),"ws","alice","P","Project",null,ProjectStatus.PLANNING,Instant.EPOCH);
      var solution=new SolutionDefinition(scope.scopeKey(),"ws","S","Solution",null,null,null,null,1,"alice","north",Instant.EPOCH);
      var product=new ProductCatalogEntry(scope.scopeKey(),"ws","PRODUCT","M",null,"Product",null,ProductStatus.ACTIVE,null,null,OperatingModel.UNSPECIFIED,null,null,null,null,null,null,"source",Instant.EPOCH,"alice",Instant.EPOCH);
      tx.executeWithoutResult(s->{em.persist(project);em.persist(solution);em.persist(product);});
      var projectSolution=new ProjectSolution(project,solution,null,null,50,"Existing","alice",Instant.EPOCH);
      tx.executeWithoutResult(s->em.persist(projectSolution));
      var repoFactory=new JpaRepositoryFactory(em);
      var fingerprints=mock(PortfolioFingerprintService.class);
      when(fingerprints.contentFingerprint(anyString())).thenAnswer(call->json.fingerprint(call.getArgument(0)));
      var projects=transactional(new ProjectPortfolioService(repoFactory.getRepository(ArchitectureProjectRepository.class),
          repoFactory.getRepository(ProjectRequirementRepository.class),repoFactory.getRepository(ProjectRequirementVersionRepository.class),
          repoFactory.getRepository(ProjectSolutionRepository.class),null,fingerprints,codec,null),txm,ProjectPortfolioService.class);
      var requirement=projects.createRequirement(project.getId(),new CreateRequirementRequest("REQ","Frozen requirement","Original demand",RequirementStatus.DRAFT,50,Criticality.MEDIUM,RequirementType.FUNCTIONAL,ReviewStatus.PROPOSED,"alice",null,null),"alice",context);
      var snapshots=mock(PortfolioAnalysisPersistenceService.class);
      var detail=new SnapshotDetail(null,null,null,null,null,List.of(),List.of());
      when(snapshots.getSnapshot(project.getId(),"snapshot","alice",context)).thenReturn(detail);
      var ready=new CountDownLatch(1);var changed=new CountDownLatch(1);
      var taxonomy=mock(com.taxonomy.catalog.api.TaxonomyNodeLookup.class);
      when(taxonomy.findByCode("BP")).thenReturn(Optional.of(mock(com.taxonomy.catalog.model.TaxonomyNode.class)));
      var productTarget=new ProductCatalogService(repoFactory.getRepository(ProductCatalogEntryRepository.class),repoFactory.getRepository(ProductTaxonomyCoverageRepository.class),
          repoFactory.getRepository(SolutionProductCandidateRepository.class),repoFactory.getRepository(ProjectSolutionRepository.class),taxonomy,projects) {
        @Override public SolutionProductCandidateView applyReviewedInventoryCandidate(Long p,Long s,UpsertProductCandidateRequest r,String c,String f,String h,String u,WorkspaceContext w) {
          ready.countDown();try{if(!changed.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Application pause was not released");}catch(InterruptedException e){throw new RuntimeException(e);}
          return super.applyReviewedInventoryCandidate(p,s,r,c,f,h,u,w);
        }
      };
      var products=transactional(productTarget,txm,ProductCatalogService.class);
      var service=new InventoryComparisonService(products,snapshots,projects,mock(SolutionPortfolioService.class),evidence,codec);
      String cid="product:"+product.getId();
      var question=new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north","production");
      var positive=assertion("initial-positive",cid,true,"2029-01-01","2032-01-01");journal.append(scope,positive);
      var productView=products.getProduct(product.getId(),"alice",context);
      var projected=new CuratedInventoryProjection().project(productView,json.fingerprint(productView),journal.assertions(scope,cid),question);
      var metadata=new TreeMap<>(projected.metadata());metadata.put("catalogueFingerprint",json.fingerprint(productView));metadata.put("curatedAssertionsFingerprint",json.fingerprint(journal.assertions(scope,cid)));
      var candidate=new Candidate(cid,product.getId(),"Product","TECHNOLOGY_PRODUCT",projected.fingerprint(),metadata,projected.assertions());
      var finding=evaluator.evaluate(cid,Criterion.AVAILABILITY,null,question,candidate.assertions(),true);
      var demand=new Demand("repo",scope.scopeKey(),"draft",project.getId(),requirement.id(),requirement.currentVersionId(),"snapshot","catalog",json.fingerprint(codec.write(detail)),List.of("BP"),question);
      var comparison=new Comparison(UUID.randomUUID(),demand,InventoryCriterionEvaluator.VERSION,Instant.now(),List.of(candidate),List.of(finding),List.of(),"comparison-fingerprint");
      journal.save(scope,comparison);
      var review=new Review(UUID.randomUUID(),comparison.fingerprint(),cid,"Explicit selection",projectSolution.getId(),true,100);
      var proxiedService=transactional(service,txm,InventoryComparisonService.class);
      var executor=Executors.newFixedThreadPool(2);
      try {
        var result=executor.submit(()->proxiedService.review(comparison.id(),review,"alice",context));
        org.junit.jupiter.api.Assertions.assertTrue(ready.await(15,TimeUnit.SECONDS),"Review reaches real application after input revalidation");
        var attempted=new CountDownLatch(1);
        Runnable write=()->{attempted.countDown();switch(mutation) {
          case ASSERTION -> proxiedService.appendAssertion(product.getId(),assertion("new-negative-before-apply",cid,false,"2029-01-01","2032-01-01"),"alice",context);
          case PRODUCT -> {var update=mock(UpdateProductRequest.class);when(update.manufacturer()).thenReturn("Changed manufacturer");products.updateProduct(product.getId(),update,"alice",context);}
          case REQUIREMENT -> projects.addRequirementVersion(project.getId(),requirement.id(),new CreateRequirementVersionRequest("Concurrent new demand","Concurrent revision",null),"alice",context);
          case COVERAGE -> products.upsertTaxonomyCoverage(product.getId(),new UpsertTaxonomyCoverageRequest("BP",50,"Revised mapping",ReviewStatus.CONFIRMED),"alice",context);
        }};
        var writer=executor.submit(write);
        org.junit.jupiter.api.Assertions.assertTrue(attempted.await(5,TimeUnit.SECONDS));
        boolean committedBeforeApply;
        try {writer.get(500,TimeUnit.MILLISECONDS);committedBeforeApply=true;}
        catch(TimeoutException blocked) {committedBeforeApply=false;}
        finally {changed.countDown();}
        result.get(15,TimeUnit.SECONDS);
        try {writer.get(15,TimeUnit.SECONDS);}catch(ExecutionException contention) {
          var conflict=org.junit.jupiter.api.Assertions.assertInstanceOf(PortfolioException.class,contention.getCause());
          org.junit.jupiter.api.Assertions.assertEquals(PortfolioException.Kind.CONFLICT,conflict.getKind());
          // HSQL MVCC can reject the waiting stale version. A fresh operation
          // after the applying review has committed must then succeed.
          write.run();
        }
        org.junit.jupiter.api.Assertions.assertFalse(committedBeforeApply,mutation+" committed after revalidation but before real selection application");
        tx.executeWithoutResult(status->{var saved=em.createQuery("select c from SolutionProductCandidate c",SolutionProductCandidate.class).getSingleResult();
          org.junit.jupiter.api.Assertions.assertEquals(ProductSelectionStatus.SELECTED,saved.getSelectionStatus());});
        if(mutation==Writer.ASSERTION)org.junit.jupiter.api.Assertions.assertEquals(Outcome.CONFLICT,evaluator.evaluate(cid,Criterion.AVAILABILITY,null,question,journal.assertions(scope,cid),true).outcome());
        org.junit.jupiter.api.Assertions.assertEquals(1,journal.reviews(scope,comparison.id()).size());
        var stale=new Review(UUID.randomUUID(),comparison.fingerprint(),cid,"Try selecting after the committed exclusion",projectSolution.getId(),true,100);
        org.junit.jupiter.api.Assertions.assertThrows(PortfolioException.class,()->proxiedService.review(comparison.id(),stale,"alice",context));
        org.junit.jupiter.api.Assertions.assertEquals(1,journal.reviews(scope,comparison.id()).size(),"Changed inputs must not produce another retained review");
      } finally {changed.countDown();executor.shutdownNow();}
    }
  }
  private static <T> T transactional(T target,org.springframework.transaction.PlatformTransactionManager manager,Class<T> type) {
    var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
    proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    return type.cast(proxy.getProxy());
  }
}
