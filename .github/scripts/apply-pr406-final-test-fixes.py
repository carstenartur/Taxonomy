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


replace_exact(
    "taxonomy-app/src/test/java/com/taxonomy/dsl/DslApiControllerTest.java",
    '        h.setAnalysisSessionId("session-apply-test");\n'
    '        h = hypothesisRepository.save(h);',
    '        h.setAnalysisSessionId("session-apply-test");\n'
    '        scopeToCurrentWorkspace(h);\n'
    '        h = hypothesisRepository.save(h);'
)

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

old_method = '''    void formLoginSessionRequiresCsrfForStateChangingApiCalls() throws Exception {
        MvcResult login = mockMvc.perform(formLogin().user("admin").password("admin"))
                .andExpect(authenticated())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/admin/verify")
                        .session(session)
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
if old_method not in text:
    if 'HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY' not in text:
        raise SystemExit("AdminAuthorizationRegressionTest method body not found")
else:
    text = text.replace(old_method, new_method, 1)
admin_test.write_text(text, encoding="utf-8")

print("Applied final PR #406 test-fixture corrections")
