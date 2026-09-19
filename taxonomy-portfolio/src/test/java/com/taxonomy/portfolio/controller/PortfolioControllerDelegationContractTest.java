package com.taxonomy.portfolio.controller;

import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.Invocation;
import org.springframework.http.ResponseEntity;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Explicit routing table: every listed endpoint preserves request identity, actor and workspace. */
class PortfolioControllerDelegationContractTest {
    record Route(Class<?> controller, String endpoint, String service, int status, String location) {}
    static Stream<Route> routes() {
        return Stream.of(
            new Route(ProductCatalogController.class,"create","createProduct",201,"/api/products/73"),
            new Route(ProductCatalogController.class,"list","listProducts",200,null),
            new Route(ProductCatalogController.class,"get","getProduct",200,null),
            new Route(ProductCatalogController.class,"update","updateProduct",200,null),
            new Route(ProductCatalogController.class,"upsertCoverage","upsertTaxonomyCoverage",200,null),
            new Route(ProductCatalogController.class,"upsertCandidate","upsertCandidate",200,null),
            new Route(ProductCatalogController.class,"listCandidates","listCandidates",200,null),
            new Route(SolutionCatalogController.class,"create","createSolution",201,"/api/solutions/73"),
            new Route(SolutionCatalogController.class,"list","listSolutions",200,null),
            new Route(SolutionCatalogController.class,"get","getSolution",200,null),
            new Route(SolutionCatalogController.class,"update","updateSolution",200,null),
            new Route(SolutionCatalogController.class,"upsertCoverage","upsertTaxonomyCoverage",200,null),
            new Route(ProjectSolutionController.class,"add","addProjectSolution",200,null),
            new Route(ProjectSolutionController.class,"list","listProjectSolutions",200,null),
            new Route(ProjectSolutionController.class,"propose","proposeFromCurrentMappings",200,null),
            new Route(ProjectSolutionController.class,"update","updateProjectSolution",200,null),
            new Route(ProjectSolutionController.class,"linkRequirement","linkRequirement",200,null),
            new Route(PortfolioQueryController.class,"portfolio","build",200,null),
            new Route(PortfolioQueryController.class,"detectConflicts","detect",200,null),
            new Route(PortfolioQueryController.class,"listConflicts","list",200,null),
            new Route(PortfolioQueryController.class,"reviewConflict","review",200,null),
            new Route(ProjectPortfolioController.class,"createProject","createProject",201,"/api/projects/73"),
            new Route(ProjectPortfolioController.class,"listProjects","listProjects",200,null),
            new Route(ProjectPortfolioController.class,"getProject","getProject",200,null),
            new Route(ProjectPortfolioController.class,"updateProject","updateProject",200,null),
            new Route(ProjectPortfolioController.class,"createRequirement","createRequirement",201,"/api/projects/101/requirements/73"),
            new Route(ProjectPortfolioController.class,"listRequirements","listRequirements",200,null),
            new Route(ProjectPortfolioController.class,"getRequirement","getRequirement",200,null),
            new Route(ProjectPortfolioController.class,"updateRequirement","updateRequirement",200,null),
            new Route(ProjectPortfolioController.class,"addRequirementVersion","addRequirementVersion",201,"/api/projects/101/requirements/102/versions/73"),
            new Route(ProjectPortfolioController.class,"listRequirementVersions","listRequirementVersions",200,null),
            new Route(ProjectAnalysisController.class,"analyzeProject","enqueueProject",202,"/api/projects/101/analysis-jobs/job-73"),
            new Route(ProjectAnalysisController.class,"listJobs","listJobs",200,null),
            new Route(ProjectAnalysisController.class,"listSnapshots","listSnapshots",200,null),
            new Route(ProjectAnalysisController.class,"getSnapshot","getSnapshot",200,null),
            new Route(ProjectAnalysisController.class,"diffSnapshots","diffSnapshots",200,null),
            new Route(ProjectAnalysisController.class,"reviewElementMapping","reviewElementMapping",200,null),
            new Route(ProjectAnalysisController.class,"reviewRelationMapping","reviewRelationMapping",200,null)
        );
    }
    @ParameterizedTest(name="{0}") @MethodSource("routes")
    void forwardsOnlyToTheDeclaredServiceWithExactScopedArguments(Route route) throws Exception {
        var context=new WorkspaceContext("alice","workspace-a","review","repository-a");
        var resolver=mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(context);
        var services=new ArrayList<Object>();
        var returned=new AtomicReference<Object>();
        Constructor<?> constructor=route.controller().getConstructors()[0];
        Object[] dependencies=new Object[constructor.getParameterCount()];
        for(int i=0;i<dependencies.length;i++) {
            Class<?> type=constructor.getParameterTypes()[i];
            if(type==WorkspaceResolver.class) dependencies[i]=resolver;
            else if(type==int.class) dependencies[i]=100;
            else if(type==long.class) dependencies[i]=500000L;
            else {
                dependencies[i]=mock(type, call->{
                    if(call.getMethod().getDeclaringClass()==Object.class) return RETURNS_DEFAULTS.answer(call);
                    assertThat(call.getMethod().getName()).as("selected backend method").isEqualTo(route.service());
                    Object value=responseValue(call.getMethod()); returned.set(value); return value;
                });
                services.add(dependencies[i]);
            }
        }
        Object controller=constructor.newInstance(dependencies);
        Method method=Arrays.stream(route.controller().getMethods()).filter(m->m.getName().equals(route.endpoint()))
                .findFirst().orElseThrow();
        Object[] args=new Object[method.getParameterCount()];
        for(int i=0;i<args.length;i++) {
            Class<?> type=method.getParameterTypes()[i];
            args[i]=type==Long.class ? 101L+i : type==String.class ? "argument-"+i : mock(type);
        }
        Object response=method.invoke(controller,args);
        var calls=services.stream().flatMap(s->mockingDetails(s).getInvocations().stream()).toList();
        assertThat(calls).as("one backend operation, no secondary mutation").hasSize(1);
        Invocation call=calls.getFirst();
        assertThat(call.getMethod().getName()).isEqualTo(route.service());
        var expected=new ArrayList<>(Arrays.asList(args)); expected.add("alice");expected.add(context);
        assertThat(call.getArguments()).containsExactly(expected.toArray());
        if(response instanceof ResponseEntity<?> entity) {
            assertThat(entity.getStatusCode().value()).isEqualTo(route.status());
            assertThat(entity.getBody()).isSameAs(returned.get());
            if(route.location()!=null) assertThat(entity.getHeaders().getLocation()).hasToString(route.location());
        } else assertThat(response).isSameAs(returned.get());
        verify(resolver,times(1)).resolveCurrentUsername();
        verify(resolver,times(1)).resolveCurrentContext();
    }
    private static Object responseValue(Method method) {
        Class<?> type=method.getReturnType();
        if(type==List.class) {
            Type item=((ParameterizedType)method.getGenericReturnType()).getActualTypeArguments()[0];
            return List.of(mock((Class<?>)item));
        }
        return mock(type, call->{
            if(call.getMethod().getName().equals("id"))
                return call.getMethod().getReturnType()==String.class ? "job-73" : 73L;
            return RETURNS_DEFAULTS.answer(call);
        });
    }
}
