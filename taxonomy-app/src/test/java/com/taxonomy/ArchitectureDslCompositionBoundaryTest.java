package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureDslCompositionBoundaryTest {
    private static final String WORKSPACE = "com.taxonomy.versioning.controller.DslApiController";
    private static final String DOCUMENT = "com.taxonomy.composition.dsl.controller.DslDocumentApiController";

    @Test
    void workspaceHttpAndFacadesDoNotDependOnKnowledgeOrDocumentAdapters() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.taxonomy");
        assertThat(classes.contain(WORKSPACE)).isTrue();
        assertThat(classes.contain("com.taxonomy.versioning.service.DslOperationsFacade")).isTrue();
        assertThat(classes.contain("com.taxonomy.versioning.service.SemanticDslOperationsFacade")).isTrue();
        noClasses().that().resideInAPackage("com.taxonomy.versioning.controller..")
                .or().haveFullyQualifiedName("com.taxonomy.versioning.service.DslOperationsFacade")
                .or().haveFullyQualifiedName("com.taxonomy.versioning.service.SemanticDslOperationsFacade")
                .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.architecture..",
                        "com.taxonomy.catalog..", "com.taxonomy.relations..", "com.taxonomy.dsl.export..")
                .check(classes);
    }

    @Test
    void documentRoutesHaveExactlyOneCompositionOwnerAndWorkspaceRoutesRemain() throws Exception {
        Class<?> document = Class.forName(DOCUMENT);
        assertThat(Class.forName("com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade").getPackageName())
                .isEqualTo("com.taxonomy.composition.dsl.service");
        assertThat(Class.forName("com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver").getPackageName())
                .isEqualTo("com.taxonomy.versioning.controller");
        Map<String, String> owners = new HashMap<>();
        for (Class<?> owner : new Class<?>[]{Class.forName(WORKSPACE), document}) {
            assertThat(owner.getAnnotation(RequestMapping.class).value()).containsExactly("/api/dsl");
            for (Method method : owner.getDeclaredMethods()) {
                GetMapping get = method.getAnnotation(GetMapping.class);
                PostMapping post = method.getAnnotation(PostMapping.class);
                if (get != null) for (String path : get.value()) {
                    assertThat(owners.put("GET " + path, owner.getName())).isNull();
                }
                if (post != null) for (String path : post.value()) {
                    assertThat(owners.put("POST " + path, owner.getName())).isNull();
                }
            }
        }
        Set<String> documentRoutes = Set.of("GET /export", "GET /current", "POST /materialize",
                "POST /materialize-incremental", "GET /history", "GET /diff/{beforeId}/{afterId}",
                "GET /diff/semantic/{beforeId}/{afterId}", "GET /documents");
        assertThat(owners.entrySet().stream().filter(e -> e.getValue().equals(DOCUMENT)).map(Map.Entry::getKey))
                .containsExactlyInAnyOrderElementsOf(documentRoutes);
        for (String route : Set.of("POST /parse", "POST /validate", "POST /format",
                "GET /diff/text/{beforeId}/{afterId}", "POST /history/index")) {
            assertThat(owners).containsEntry(route, WORKSPACE);
        }
    }
}
