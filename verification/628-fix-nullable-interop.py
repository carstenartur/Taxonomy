"""Disposable verification helper; only the four explicit Java paths are deliverables."""
from pathlib import Path
import json, os, shutil, subprocess, sys, urllib.request

BASE = 'eba5039c2731005f4a76e2f47b120403e8684b1b'
ROOT = Path('taxonomy-interop/src/main/java/com/taxonomy/interop')
PORT = ROOT / 'IntegrationPortfolioPort.java'
DOMAIN = ROOT / 'IntegrationDomainAdapter.java'
OSLC = ROOT / 'oslc/OslcProviderService.java'
TEST = Path('taxonomy-interop/src/test/java/com/taxonomy/interop/IntegrationNullableRequirementTest.java')
FILES = (PORT, DOMAIN, OSLC, TEST)

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def replace(path, old, new, count=1):
    source = path.read_text()
    assert source.count(old) == count, (str(path), old, source.count(old))
    path.write_text(source.replace(old, new))

def tests():
    assert git('rev-parse', 'HEAD') == BASE
    assert not TEST.exists()
    source = Path(__file__).with_name('IntegrationNullableRequirementTest.java').read_text()
    assert source.endswith('}\n')
    extra = '''
    @Test
    void completeRequirementMetadataKeepsTheExistingFingerprint() {
        var requirement = requirement("APPROVED", 10L, NOW, new VersionData("Body", NOW));
        when(projects.listRequirements(eq(7L), eq("alice"), any())).thenReturn(List.of(requirement));
        var snapshot = domain.snapshot(context, connection, List.of(mapping), document);
        assertEquals(new InternalState("repo-a", "workspace-scope", "draft", "checkpoint", 1, 7L,
                json.fingerprint(List.of(List.of(9L, "Title", "APPROVED", 10L, NOW)))), snapshot.state());
        assertEquals("Body", snapshot.items().get("REQUIREMENT:external").text());
    }

    @Test
    void oslcRejectsMissingContentForQueryCurrentAndVersionRoutes() {
        for (VersionData version : Arrays.asList(null, new VersionData(null, NOW))) {
            var requirement = requirement("APPROVED", 10L, NOW, version);
            when(projects.getRequirement(eq(7L), eq(9L), eq("alice"), any())).thenReturn(requirement);
            when(projects.listApprovedRequirements(eq(7L), eq("alice"), any(), eq(0), eq(10)))
                    .thenReturn(new RequirementsPage(List.of(requirement), false));
            assertVersionUnavailable(() -> provider.requirement(context, 7, 9, null, links));
            assertVersionUnavailable(() -> provider.requirement(context, 7, 9, 10L, links));
            assertVersionUnavailable(() -> provider.query(context, 7, 0, 10, links));
        }
    }

    @Test
    void snapshotExportAndApplyRejectNullTextBeforeMutation() {
        var requirement = requirement("DRAFT", 10L, NOW, new VersionData(null, NOW));
        when(projects.listRequirements(eq(7L), eq("alice"), any())).thenReturn(List.of(requirement));
        when(projects.getRequirement(eq(7L), eq(9L), eq("alice"), any())).thenReturn(requirement);
        assertVersionUnavailable(() -> domain.snapshot(context, connection, List.of(mapping), document));
        var snapshot = new IntegrationDomainAdapter.Snapshot(null, Map.of(), List.of(requirement), List.of());
        assertVersionUnavailable(() -> domain.exportDocument(connection, snapshot, document, List.of(), null));
        assertVersionUnavailable(() -> domain.applyRequirement(context, connection, artifact, mapping, "Reviewed import"));
        verify(projects).listRequirements(eq(7L), eq("alice"), any());
        verify(projects).getRequirement(eq(7L), eq(9L), eq("alice"), any());
        verifyNoMoreInteractions(projects);
    }

    @Test
    void queryPreservesPagingWithoutInventingVersionOrModificationMetadata() {
        var requirement = requirement("APPROVED", null, null, new VersionData("Body", null));
        when(projects.listApprovedRequirements(eq(7L), eq("alice"), any(), eq(0), eq(10)))
                .thenReturn(new RequirementsPage(List.of(requirement), true));
        var graph = assertDoesNotThrow(() -> provider.query(context, 7, 0, 10, links));
        assertTrue(graph.triples().stream().anyMatch(t -> t.predicate().equals(OslcRdf.OSLC + "nextPage")));
        assertFalse(graph.triples().stream().anyMatch(t -> t.predicate().equals(OslcRdf.DCT + "hasVersion")
                || t.predicate().equals(OslcRdf.DCT + "modified")));
        assertTrue(graph.triples().stream().anyMatch(t -> t.predicate().equals(OslcRdf.DCT + "description") && t.value().equals("Body")));
    }

    private static void assertVersionUnavailable(org.junit.jupiter.api.function.Executable action) {
        var problem = assertThrows(IntegrationProblem.class, action);
        assertEquals("REQUIREMENT_VERSION_UNAVAILABLE", problem.code());
        assertEquals(409, problem.status());
        assertTrue(problem.getMessage().contains("current version"));
    }
'''
    TEST.write_text(source[:-2] + extra + '}\n')

def patch():
    assert git('hash-object', str(PORT)) == '6422905c74ae3169f02eb809402e740658f28d61'
    assert git('hash-object', str(DOMAIN)) == 'a16743dda1ae4880b6e74431760952c444c30648'
    assert git('hash-object', str(OSLC)) == '42feaca89d700b324bb0da791975a3bb5c230f7b'
    replace(PORT,
        '        public boolean approved() { return "APPROVED".equals(status); }\n',
        '''        public boolean approved() { return "APPROVED".equals(status); }
        /**
         * Content operations require a readable version. Optional status, version
         * identity and timestamps remain unchanged; absent content must never be
         * exported as empty text or mistaken for a deleted requirement.
         */
        public VersionData requireCurrentVersion() {
            if (currentVersion == null || currentVersion.text() == null) {
                throw new IntegrationProblem("REQUIREMENT_VERSION_UNAVAILABLE", 409,
                        "Requirement has no readable current version; repair it before exchanging content");
            }
            return currentVersion;
        }
''')
    replace(DOMAIN, 'requirement.currentVersion().text()', 'requirement.requireCurrentVersion().text()', 2)
    replace(DOMAIN, 'before.currentVersion().text()', 'before.requireCurrentVersion().text()')
    replace(DOMAIN, 'r -> List.of(r.id(), r.title(), r.status(), r.currentVersionId(), r.updatedAt())',
                    'r -> java.util.Arrays.asList(r.id(), r.title(), r.status(), r.currentVersionId(), r.updatedAt())')
    replace(OSLC,
        '''        String immutable = links.uri("/projects/" + projectId + "/requirements/" + requirement.id() + "/versions/" + requirement.currentVersionId());
        String uri = version ? immutable : current;''',
        '''        var currentVersion = requirement.requireCurrentVersion();
        String immutable = requirement.currentVersionId() == null ? null
                : links.uri("/projects/" + projectId + "/requirements/" + requirement.id() + "/versions/" + requirement.currentVersionId());
        String uri = version ? immutable : current;
        var modified = version ? currentVersion.createdAt() : requirement.updatedAt();''')
    replace(OSLC, 'requirement.currentVersion().text()', 'currentVersion.text()')
    replace(OSLC, 'version ? requirement.currentVersion().createdAt().toString() : requirement.updatedAt().toString()',
                  'modified == null ? null : modified.toString()')
    replace(OSLC, 'if (version) graph.link(uri, DCT + "isVersionOf", current); else graph.link(uri, DCT + "hasVersion", immutable);',
                  'if (version) graph.link(uri, DCT + "isVersionOf", current);\n        else if (immutable != null) graph.link(uri, DCT + "hasVersion", immutable);')
    git('add', '--', *(str(p) for p in FILES))
    git('diff', '--cached', '--check')
    changed = git('diff', '--cached', '--name-only').splitlines()
    assert set(changed) == {str(p) for p in FILES}, changed
    print(git('diff', '--cached', '--stat'))
    print('CANDIDATE_TREE', git('write-tree'), flush=True)

def publish():
    git('diff', '--exit-code')
    entries = [{'path': str(p), 'mode': '100644', 'type': 'blob', 'sha': git('hash-object', str(p))} for p in FILES]
    manifest = {'base': BASE, 'tree': git('write-tree'), 'entries': entries}
    path = Path(os.environ['RUNNER_TEMP'], 'interop-null-source.json')
    path.write_text(json.dumps(manifest, indent=2))
    print('VERIFIED_SOURCE', json.dumps(manifest), flush=True)
    for entry, p in zip(entries, FILES):
        assert p.stat().st_size < 1000000
        request = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/git/blobs',
                data=json.dumps({'content': p.read_text(), 'encoding': 'utf-8'}).encode(),
                headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(request, timeout=30) as response:
            assert json.load(response)['sha'] == entry['sha']
    print('All four verified source blobs retained; no branch, commit or workflow was changed by this step.', flush=True)

if __name__ == '__main__':
    {'tests': tests, 'patch': patch, 'publish': publish}[sys.argv[1]]()
