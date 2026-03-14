package com.taxonomy.dsl;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.repository.ArchitectureDslDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Tests for {@link DslMaterializeService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class DslMaterializeServiceTest {

    @Autowired
    private DslMaterializeService materializeService;

    @Autowired
    private ArchitectureDslDocumentRepository documentRepository;

    @Test
    void materializeValidDslCreatesDocument() {
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "test.materialize";
                }
                
                element CP-1010 type Capability {
                  title: "Test Capability";
                }
                """;

        DslMaterializeService.MaterializeResult result =
                materializeService.materialize(dsl, "test.tax", "main", "abc123");

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.documentId()).isNotNull();

        // Verify document was persisted
        var doc = documentRepository.findById(result.documentId());
        assertThat(doc).isPresent();
        assertThat(doc.get().getPath()).isEqualTo("test.tax");
        assertThat(doc.get().getBranch()).isEqualTo("main");
        assertThat(doc.get().getCommitId()).isEqualTo("abc123");
        assertThat(doc.get().getNamespace()).isEqualTo("test.materialize");
        assertThat(doc.get().getDslVersion()).isEqualTo("2.0");
    }

    @Test
    void materializeDslWithDuplicateIdsFails() {
        String dsl = """
                element CP-1010 type Capability {
                  title: "First";
                }
                
                element CP-1010 type Capability {
                  title: "Duplicate";
                }
                """;

        DslMaterializeService.MaterializeResult result =
                materializeService.materialize(dsl, null, null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.documentId()).isNull();
    }

    @Test
    void materializeProvisionalRelationsCreatesHypotheses() {
        String dsl = """
                element CP-100 type Capability {
                  title: "Cap";
                }
                
                element BP-100 type Process {
                  title: "Proc";
                }
                
                relation BP-100 REALIZES CP-100 {
                  status: provisional;
                  confidence: 0.65;
                }
                """;

        DslMaterializeService.MaterializeResult result =
                materializeService.materialize(dsl, "provisional.tax", null, null);

        assertThat(result.valid()).isTrue();
        assertThat(result.hypothesesCreated()).isEqualTo(1);
    }

    @Test
    void materializeAcceptedRelationsCreatesRelations() {
        // Use real root-level node codes with a relation type not preloaded from CSV
        String dsl = """
                element UA type UserApplication {
                  title: "Test App";
                }
                
                element CP type Capability {
                  title: "Test Capability";
                }
                
                relation UA REALIZES CP {
                  status: accepted;
                }
                """;

        DslMaterializeService.MaterializeResult result =
                materializeService.materialize(dsl, "accepted.tax", null, null);

        assertThat(result.valid()).isTrue();
        assertThat(result.relationsCreated()).isEqualTo(1);
    }

    @Test
    void materializeWithNullPathUsesInline() {
        String dsl = """
                element CP-1022 type Capability {
                  title: "Inline Test";
                }
                """;

        DslMaterializeService.MaterializeResult result =
                materializeService.materialize(dsl, null, null, null);

        assertThat(result.valid()).isTrue();
        assertThat(result.documentId()).isNotNull();

        var doc = documentRepository.findById(result.documentId());
        assertThat(doc).isPresent();
        assertThat(doc.get().getPath()).isEqualTo("inline");
    }
}
