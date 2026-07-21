#!/usr/bin/env python3
"""Apply the final two test-fixture corrections for PR #406 exactly once."""
from pathlib import Path


def replace_exact(path: str, old: str, new: str, count: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    occurrences = text.count(old)
    if occurrences != count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {occurrences}")
    file.write_text(text.replace(old, new, count), encoding="utf-8")


dsl_test = Path("taxonomy-app/src/test/java/com/taxonomy/dsl/DslApiControllerTest.java")
dsl_text = dsl_test.read_text(encoding="utf-8")
annotation = '    @WithMockUser(username = "hypothesis-apply-user", roles = "ADMIN")\n'
method_anchor = '    @Test\n    void applyHypothesisForSession() throws Exception {'
if annotation not in dsl_text:
    if method_anchor not in dsl_text:
        raise SystemExit("DslApiControllerTest apply-session method anchor not found")
    dsl_text = dsl_text.replace(
        method_anchor,
        '    @Test\n' + annotation + '    void applyHypothesisForSession() throws Exception {',
        1,
    )

scope_anchor = (
    '        h.setAnalysisSessionId("session-apply-test");\n'
    '        h = hypothesisRepository.save(h);'
)
if scope_anchor in dsl_text:
    dsl_text = dsl_text.replace(
        scope_anchor,
        '        h.setAnalysisSessionId("session-apply-test");\n'
        '        scopeToCurrentWorkspace(h);\n'
        '        h = hypothesisRepository.save(h);',
        1,
    )
elif '        scopeToCurrentWorkspace(h);\n        h = hypothesisRepository.save(h);' not in dsl_text:
    raise SystemExit("DslApiControllerTest workspace scope anchor not found")
dsl_test.write_text(dsl_text, encoding="utf-8")

admin_test = Path("taxonomy-app/src/test/java/com/taxonomy/security/AdminAuthorizationRegressionTest.java")
text = admin_test.read_text(encoding="utf-8")
text = text.replace('import org.springframework.test.web.servlet.MvcResult;\n', '')
text = text.replace('import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;\n', '')
text = text.replace('import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;\n', '')

import_anchor = 'import org.springframework.security.test.context.support.WithMockUser;\n'
additional_imports = (
    'import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;\n'
    'import org.springframework.security.core.authority.SimpleGrantedAuthority;\n'
    'import org.springframework.security.core.context.SecurityContext;\n'
    'import org.springframework.security.core.context.SecurityContextHolder;\n'
    'import org.springframework.security.web.context.HttpSessionSecurityContextRepository;\n'
)
if additional_imports not in text:
    if import_anchor not in text:
        raise SystemExit("AdminAuthorizationRegressionTest import anchor not found")
    text = text.replace(import_anchor, import_anchor + additional_imports, 1)

method_start = '    void formLoginSessionRequiresCsrfForStateChangingApiCalls() throws Exception {'
start = text.find(method_start)
if start < 0:
    raise SystemExit("AdminAuthorizationRegressionTest method not found")
end_marker = '\n    }'
end = text.find(end_marker, start)
if end < 0:
    raise SystemExit("AdminAuthorizationRegressionTest method end not found")
end += len(end_marker)
new_method = '''    void formLoginSessionRequiresCsrfForStateChangingApiCalls() throws Exception {
        MockHttpSession session = new MockHttpSession();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);

        mockMvc.perform(post("/api/admin/verify")
                        .session(session)
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/verify")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }'''
text = text[:start] + new_method + text[end:]
admin_test.write_text(text, encoding="utf-8")

print("Applied final PR #406 test-fixture corrections")
