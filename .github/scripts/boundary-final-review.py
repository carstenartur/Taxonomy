"""Isolated boundary preflight. No helper, token or workflow enters a product tree."""
from pathlib import Path
import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

mode, root = sys.argv[1], Path(sys.argv[2]).resolve()
BASE = 'aff0f1c78f81598d9bce2c575ba1bf0584de4ee5'
INTERCEPTOR = 'taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/ExplicitWorkspacePinValidationInterceptor.java'
AUTH = 'taxonomy-app/src/main/java/com/taxonomy/security/config/AuthorizationRulesConfigurer.java'
WS_TEST = 'taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceCentralReadBoundaryTest.java'
ADMIN_TEST = 'taxonomy-app/src/test/java/com/taxonomy/security/AdminAuthorizationRegressionTest.java'
GIT_TEST = 'taxonomy-app/src/test/java/com/taxonomy/workspace/service/GitNativeSyncIntegrationServiceTest.java'
PRODUCT = [INTERCEPTOR, AUTH, WS_TEST, ADMIN_TEST, GIT_TEST]


def git(*args, text=True):
    result = subprocess.check_output(['git', '-C', str(root), *args], text=text)
    return result.strip() if text else result


def replace(path, old, new):
    file = root / path
    text = file.read_text()
    assert text.count(old) == 1, (path, old[:80], text.count(old))
    file.write_text(text.replace(old, new))


def append_methods(path, methods):
    file = root / path
    text = file.read_text()
    index = text.rfind('\n}')
    assert index > 0 and not text[index + 2:].strip()
    file.write_text(text[:index] + methods + text[index:])


if mode == 'tests':
    assert git('rev-parse', 'HEAD') == BASE
    replace(WS_TEST,
            'manager, resolver, null, null, null, null, null, null)).build();',
            'manager, resolver, null, null, null, null, null, null))\n'
            '                .addInterceptors(new ExplicitWorkspacePinValidationInterceptor(resolver)).build();')
    append_methods(WS_TEST, r'''

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void emptyHeaderOverridesAStaleQueryAcrossMetadataEndpoints(String header) {
        MockMvc mvc = mvc();
        for (String endpoint : new String[] {"current", "provisioning-status", "provision"}) {
            var request = endpoint.equals("provision")
                    ? post("/api/workspace/" + endpoint) : get("/api/workspace/" + endpoint);
            int expectedStatus = endpoint.equals("current") ? 204 : endpoint.equals("provision") ? 403 : 200;
            assertDoesNotThrow(() -> mvc.perform(request.header(WorkspaceContextResolver.WORKSPACE_HEADER, header)
                    .param(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "inaccessible-stale-query"))
                    .andExpect(status().is(expectedStatus)));
        }
        verifyNoInteractions(manager);
        // Only provisioning-status legitimately resolves absent, read-only metadata.
        // The interceptor must not add a lookup based on the overridden query pin.
        verify(resolver).resolveCurrentWorkspaceMetadata();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void emptyHeaderOverridesAStaleQueryForNonMetadataHandlers(String header) {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, header);
        request.addParameter(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "foreign-query");
        var interceptor = new ExplicitWorkspacePinValidationInterceptor(resolver);
        assertDoesNotThrow(() -> assertTrue(interceptor.preHandle(request,
                new org.springframework.mock.web.MockHttpServletResponse(), new Object())));
        verifyNoInteractions(resolver);
    }
''')
    append_methods(ADMIN_TEST, r'''

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"USER,GET", "USER,HEAD", "ARCHITECT,GET", "ARCHITECT,HEAD"})
    void workspaceActivityDoesNotExposeGlobalMetadataToNonAdmins(String role, String method) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), "/api/workspace/active")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("activity-reader").roles(role)))
                .andExpect(status().isForbidden());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GET", "HEAD"})
    void workspaceActivityRemainsAvailableToAdmins(String method) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), "/api/workspace/active")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("activity-admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GET", "HEAD"})
    void workspaceActivityIsNotPublic(String method) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), "/api/workspace/active")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
''')
elif mode == 'patch':
    replace(INTERCEPTOR,
            '        if (hasText(header)) {\n            return header.strip();\n        }',
            '''        if (header != null) {
            // A present empty header explicitly selects central read. Do not validate
            // an unrelated stale query pin that the canonical resolver will ignore.
            return hasText(header) ? header.strip() : null;
        }''')
    replace(AUTH,
            '        auth.requestMatchers(HttpMethod.GET, "/api/workspace/**").authenticated();',
            '''        // Global activity includes other users' workspace metadata, unlike scoped reads.
        auth.requestMatchers(HttpMethod.GET, "/api/workspace/active").hasRole("ADMIN");
        auth.requestMatchers(HttpMethod.HEAD, "/api/workspace/active").hasRole("ADMIN");
        auth.requestMatchers(HttpMethod.GET, "/api/workspace/**").authenticated();''')
    replace(GIT_TEST,
            '''    void centralSynchronizationUsesExactRepositoryInsteadOfLegacyPrimarySentinel()
            throws Exception {
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.centralRead(''',
            '''    void writableCentralSynchronizationUsesExactRepositoryInsteadOfLegacyPrimarySentinel()
            throws Exception {
        // A synchronization mutates this central repository; CENTRAL_READ is covered
        // by the rejection cases in WorkspaceCentralReadBoundaryTest instead.
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.centralWrite(''')
    subprocess.run(['git', '-C', str(root), 'diff', '--check'], check=True)
elif mode == 'prepare':
    out = Path(sys.argv[3]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    assert git('rev-parse', 'HEAD') == BASE
    changed = set(git('diff', '--name-only').splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
    assert changed == set(PRODUCT), changed
    bases = [
        ('workspace', 1062, '985492a6b70f998485a6d84dc3c5ddc8f1d05307', 'ad1f01d9637f6a96d45bcad53ca9c63e31d16495'),
        ('templates', 1063, '4ad9423870a8d08ad86bddf38020c11573802a4c', '60a9d22b10f86269670adfe9e0f2b1f14ad60249'),
        ('interop', 1064, BASE, '70d13cb1b5a549ef6f4847a2752359a7225db938'),
    ]
    for stage, pr, sha, prior_pr in bases:
        for path in PRODUCT:
            assert git('show', sha + ':' + path, text=False) == git('show', BASE + ':' + path, text=False), (stage, path)
    api_root = 'https://api.github.com/repos/carstenartur/Taxonomy/'
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'}
    def api(path, data=None):
        request = urllib.request.Request(api_root + path, headers=headers,
            data=None if data is None else json.dumps(data).encode(), method='GET' if data is None else 'POST')
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    files = {}
    for path in PRODUCT:
        data = (root / path).read_bytes()
        sha = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        assert api('git/blobs', {'content': base64.b64encode(data).decode(), 'encoding': 'base64'})['sha'] == sha
        files[path] = sha
    previous = 'deaf46d6d8623a4404b88cbe1067acdcef10fcfc'
    candidates = []
    for stage, pr, base, prior_pr in bases:
        original = api('git/commits/' + base)
        entries = [{'path': p, 'mode': '100644', 'type': 'blob', 'sha': files[p]} for p in PRODUCT]
        tree = api('git/trees', {'base_tree': original['tree']['sha'], 'tree': entries})['sha']
        parents = [base] if stage == 'workspace' else [base, previous]
        commit = api('git/commits', {
            'message': 'fix: complete read-only and metadata boundaries (' + stage + ')\n\n'
                       'Preserve header precedence, restrict the global activity listing, and\n'
                       'exercise writable central synchronization with its explicit write scope.\n'
                       'Keep all repository-identity assertions and central-read denial cases.\n'
                       'No helper, safeguard waiver or baseline change is shipped.',
            'tree': tree, 'parents': parents})['sha']
        api('git/refs', {'ref': 'refs/heads/verify/628-final-' + os.environ['GITHUB_RUN_ID'] + '-' + stage, 'sha': commit})
        candidates.append({'stage': stage, 'pr': pr, 'sha': commit, 'tree': tree, 'base': base,
                           'priorPrHead': prior_pr, 'parents': parents, 'files': files})
        previous = commit
    matrix = {'include': [{k: entry[k] for k in ('stage', 'pr', 'sha', 'tree', 'base')} for entry in candidates]}
    manifest = {'initialRedRun': 35057338219, 'finalRedRun': 35058777486, 'gateVerificationRun': 35058014829,
                'gate': {'sha': 'deaf46d6d8623a4404b88cbe1067acdcef10fcfc', 'tree': '1583bacdddc84b6f9a474cae950a5533d82ec2d9'},
                'finalPreflightRun': os.environ['GITHUB_RUN_ID'], 'candidates': candidates}
    (out / 'candidates.json').write_text(json.dumps(manifest, indent=2) + '\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('matrix=' + json.dumps(matrix, separators=(',', ':')) + '\n')
    print(json.dumps(manifest, indent=2))
else:
    raise SystemExit('Unknown mode: ' + mode)
