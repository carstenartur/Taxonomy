#!/usr/bin/env python3
"""Apply the final PR #406 contract/test alignment exactly once.

Every replacement is exact and fails if the expected source text is absent, so
this script cannot silently rewrite an unexpected code version.
"""
from pathlib import Path


def replace_exact(path: str, old: str, new: str, count: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    occurrences = text.count(old)
    if occurrences != count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {occurrences}: {old[:100]!r}")
    file.write_text(text.replace(old, new, count), encoding="utf-8")


# Real schema bug: identical session/relation tuples must be unique per workspace,
# not globally across all tenants.
replace_exact(
    "taxonomy-app/src/main/java/com/taxonomy/relations/model/RelationHypothesis.java",
    '@Table(name = "relation_hypothesis",\n'
    '       uniqueConstraints = @UniqueConstraint(columnNames = {"source_node_id", "target_node_id", "relation_type", "analysis_session_id"}))',
    '@Table(name = "relation_hypothesis",\n'
    '       uniqueConstraints = @UniqueConstraint(\n'
    '               name = "uq_hypothesis_workspace_session_relation",\n'
    '               columnNames = {"workspace_id", "source_node_id", "target_node_id",\n'
    '                       "relation_type", "analysis_session_id"}))'
)

# /api/admin/status is intentionally readable by authenticated users so the UI
# can render the lock state. Test actual protected admin data instead.
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/SecurityTests.java",
    '    void userCannotAccessAdminEndpoints() throws Exception {\n'
    '        mockMvc.perform(get("/api/admin/status"))\n'
    '                .andExpect(status().isForbidden());\n'
    '    }',
    '    void userCannotAccessAdminEndpoints() throws Exception {\n'
    '        mockMvc.perform(get("/api/prompts"))\n'
    '                .andExpect(status().isForbidden());\n'
    '    }'
)
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/SecurityTests.java",
    '    void architectCannotAccessAdminEndpoints() throws Exception {\n'
    '        mockMvc.perform(get("/api/admin/status"))\n'
    '                .andExpect(status().isForbidden());\n'
    '    }',
    '    void architectCannotAccessAdminEndpoints() throws Exception {\n'
    '        mockMvc.perform(get("/api/prompts"))\n'
    '                .andExpect(status().isForbidden());\n'
    '    }'
)

# ROLE_ADMIN replaces the removed second application password.
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/TaxonomyApplicationTests.java",
    '    void adminVerifyReturnsFalseWhenNoPasswordConfigured() throws Exception {\n'
    '        // When no password is configured, no password is valid\n'
    '        mockMvc.perform(post("/api/admin/verify")\n'
    '                        .contentType(MediaType.APPLICATION_JSON)\n'
    '                        .content("{\\"password\\":\\"anything\\"}")\n'
    '                        .accept(MediaType.APPLICATION_JSON))\n'
    '                .andExpect(status().isOk())\n'
    '                .andExpect(jsonPath("$.valid").value(false));\n'
    '    }',
    '    void adminVerifyUsesAuthenticatedAdminRole() throws Exception {\n'
    '        mockMvc.perform(post("/api/admin/verify")\n'
    '                        .contentType(MediaType.APPLICATION_JSON)\n'
    '                        .content("{}")\n'
    '                        .accept(MediaType.APPLICATION_JSON))\n'
    '                .andExpect(status().isOk())\n'
    '                .andExpect(jsonPath("$.valid").value(true))\n'
    '                .andExpect(jsonPath("$.token").value("role-admin"));\n'
    '    }'
)

# Document mutations require ARCHITECT or ADMIN; these are controller behavior
# tests, not authorization-denial tests.
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/provenance/DocumentImportControllerTest.java",
    '@WithMockUser\nclass DocumentImportControllerTest {',
    '@WithMockUser(roles = "ARCHITECT")\nclass DocumentImportControllerTest {'
)

# Use a real form-login session for the CSRF regression test.
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/security/AdminAuthorizationRegressionTest.java",
    'import org.springframework.mock.web.MockHttpSession;\n',
    'import org.springframework.mock.web.MockHttpSession;\nimport org.springframework.test.web.servlet.MvcResult;\n'
)
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/security/AdminAuthorizationRegressionTest.java",
    'import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;\n'
    'import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;\n',
    'import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;\n'
    'import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;\n'
    'import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;\n'
)
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/security/AdminAuthorizationRegressionTest.java",
    '    void formLoginSessionRequiresCsrfForStateChangingApiCalls() throws Exception {\n'
    '        MockHttpSession session = new MockHttpSession();\n\n'
    '        mockMvc.perform(post("/api/admin/verify")\n'
    '                        .session(session)\n'
    '                        .with(user("admin").roles("ADMIN"))\n'
    '                        .contentType(MediaType.APPLICATION_JSON)\n'
    '                        .content("{}"))\n'
    '                .andExpect(status().isForbidden());\n\n'
    '        mockMvc.perform(post("/api/admin/verify")\n'
    '                        .session(session)\n'
    '                        .with(user("admin").roles("ADMIN"))\n'
    '                        .with(csrf())\n'
    '                        .contentType(MediaType.APPLICATION_JSON)\n'
    '                        .content("{}"))\n'
    '                .andExpect(status().isOk())\n'
    '                .andExpect(jsonPath("$.valid").value(true));\n'
    '    }',
    '    void formLoginSessionRequiresCsrfForStateChangingApiCalls() throws Exception {\n'
    '        MvcResult login = mockMvc.perform(formLogin().user("admin").password("admin"))\n'
    '                .andExpect(authenticated())\n'
    '                .andReturn();\n'
    '        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);\n\n'
    '        mockMvc.perform(post("/api/admin/verify")\n'
    '                        .session(session)\n'
    '                        .contentType(MediaType.APPLICATION_JSON)\n'
    '                        .content("{}"))\n'
    '                .andExpect(status().isForbidden());\n\n'
    '        mockMvc.perform(post("/api/admin/verify")\n'
    '                        .session(session)\n'
    '                        .with(csrf())\n'
    '                        .contentType(MediaType.APPLICATION_JSON)\n'
    '                        .content("{}"))\n'
    '                .andExpect(status().isOk())\n'
    '                .andExpect(jsonPath("$.valid").value(true));\n'
    '    }'
)

# Assert the current canonical DSL version rather than the superseded 2.0 value.
replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/versioning/service/HypothesisDslIntegrityTest.java",
    '.contains("version: \\"2.0\\";")',
    '.contains("version: \\"2.1\\";")'
)

# Integration fixtures that exercise mutation must belong to the same workspace
# as the authenticated controller request.
dsl_test = "taxonomy-app/src/test/java/com/taxonomy/dsl/DslApiControllerTest.java"
replace_exact(
    dsl_test,
    'import com.taxonomy.relations.repository.RelationHypothesisRepository;\n',
    'import com.taxonomy.relations.repository.RelationHypothesisRepository;\n'
    'import com.taxonomy.versioning.service.RepositoryStateService;\n'
    'import com.taxonomy.workspace.service.WorkspaceContext;\n'
    'import com.taxonomy.workspace.service.WorkspaceResolver;\n'
    'import org.springframework.security.core.context.SecurityContextHolder;\n'
)
replace_exact(
    dsl_test,
    '    @Autowired\n'
    '    private RelationHypothesisRepository hypothesisRepository;\n',
    '    @Autowired\n'
    '    private RelationHypothesisRepository hypothesisRepository;\n\n'
    '    @Autowired\n'
    '    private RepositoryStateService repositoryStateService;\n\n'
    '    @Autowired\n'
    '    private WorkspaceResolver workspaceResolver;\n'
)
replace_exact(
    dsl_test,
    '        RelationHypothesis saved = hypothesisRepository.save(h);',
    '        scopeToCurrentWorkspace(h);\n'
    '        RelationHypothesis saved = hypothesisRepository.save(h);',
    count=4
)
replace_exact(
    dsl_test,
    '        hypothesisRepository.save(h1);',
    '        scopeToCurrentWorkspace(h1);\n'
    '        hypothesisRepository.save(h1);'
)
replace_exact(
    dsl_test,
    '\n}\n',
    '\n    private void scopeToCurrentWorkspace(RelationHypothesis hypothesis) {\n'
    '        String username = SecurityContextHolder.getContext().getAuthentication().getName();\n'
    '        repositoryStateService.ensureWorkspaceState(username);\n'
    '        WorkspaceContext context = workspaceResolver.resolveCurrentContext();\n'
    '        hypothesis.setWorkspaceId(context.workspaceId());\n'
    '        hypothesis.setOwnerUsername(context.username());\n'
    '    }\n\n'
    '}\n'
)

print("PR #406 final test-contract fixes applied")
