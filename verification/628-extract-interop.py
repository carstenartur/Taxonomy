#!/usr/bin/env python3
"""Disposable extraction driver. Never included in the feature PR."""
from pathlib import Path
import base64, json, os, re, subprocess, sys, urllib.request

BASE = '78fce8222df2890b2782df1764fc27e91acc95ff'
APP = Path('taxonomy-app/src/main/java/com/taxonomy')
MODULE = Path('taxonomy-interop')
RATCHET = Path('taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java')

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)

def replace(path, old, new, count=1):
    path = Path(path); text = path.read_text()
    assert text.count(old) == count, (str(path), old, text.count(old), count)
    path.write_text(text.replace(old, new))

PHYSICAL_TEST = '''package com.taxonomy;

import com.taxonomy.interop.IntegrationDomainAdapter;
import com.taxonomy.interop.IntegrationService;
import com.taxonomy.interop.oslc.OslcProviderService;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Physical ownership and the deliberately narrow dependencies of interoperability. */
class ArchitectureInteropModuleTest {
    @Test
    void interoperabilityIsOwnedByItsLibraryWithoutApplicationOrPortfolioImplementations() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Path module = root.resolve("taxonomy-interop");
        assertThat(module.resolve("pom.xml")).as("physical interop Maven module").isRegularFile();
        assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/interop")).doesNotExist();
        for (Class<?> implementation : List.of(IntegrationService.class, IntegrationDomainAdapter.class,
                IntegrationStore.class, OslcProviderService.class)) {
            String relative = implementation.getName().replace('.', '/');
            assertThat(module.resolve("src/main/java/" + relative + ".java")).isRegularFile();
            assertThat(module.resolve("target/classes/" + relative + ".class")).isRegularFile();
            assertThat(implementation.getProtectionDomain().getCodeSource().getLocation().toString())
                    .as("runtime owner of %s", implementation.getName()).contains("taxonomy-interop");
        }
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.interop");
        noClasses().that().resideInAPackage("com.taxonomy.interop..")
                .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.portfolio..",
                        "com.taxonomy.composition..", "com.taxonomy.editor..")
                .check(classes);
    }
}
'''

PORT = '''package com.taxonomy.interop;

import com.taxonomy.workspace.service.WorkspaceContext;
import java.time.Instant;
import java.util.List;

/**
 * Portfolio operations required by reviewed external-tool interoperability.
 * The application binds these operations to the existing scoped portfolio services;
 * no portfolio entities or implementation DTOs cross this boundary.
 */
public interface IntegrationPortfolioPort {
    record ProjectData(Long id, String title) {}
    record VersionData(String text, Instant createdAt) {}
    /** Status retains the portfolio's serialized enum name, including future values. */
    record RequirementData(Long id, String requirementKey, String title, String status,
                           Long currentVersionId, Instant updatedAt, VersionData currentVersion) {
        public boolean archived() { return "ARCHIVED".equals(status); }
        public boolean approved() { return "APPROVED".equals(status); }
    }
    record RequirementsPage(List<RequirementData> requirements, boolean hasNext) {}
    record ImportProvenance(String sectionReference, String originalText) {}
    record ImportedRequirement(String key, String title, String text, String rationale,
                               ImportProvenance source) {}

    List<ProjectData> listProjects(String username, WorkspaceContext context);
    ProjectData getProject(Long projectId, String username, WorkspaceContext context);
    void requireProject(Long projectId, String username, WorkspaceContext context);
    void requireProjectForUpdate(Long projectId, String username, WorkspaceContext context);
    List<RequirementData> listRequirements(Long projectId, String username, WorkspaceContext context);
    RequirementsPage listApprovedRequirements(Long projectId, String username, WorkspaceContext context,
                                              int page, int pageSize);
    RequirementData getRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context);
    RequirementData createRequirement(Long projectId, ImportedRequirement request,
                                      String username, WorkspaceContext context);
    void archiveRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context);
    void updateRequirement(Long projectId, Long requirementId, String title, boolean requiresReview,
                           String username, WorkspaceContext context);
    void addRequirementVersion(Long projectId, Long requirementId, String text, String rationale,
                               ImportProvenance source, String username, WorkspaceContext context);
    String contributeTo(String source, String username, WorkspaceContext context);
}
'''

BRIDGE = '''package com.taxonomy.composition.interop;

import com.taxonomy.interop.IntegrationPortfolioPort;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import java.util.List;

/** Maps the interoperability port to the unchanged portfolio authorities. */
@Service
public class PortfolioInteropAdapter implements IntegrationPortfolioPort {
    private final ProjectPortfolioService projects;
    private final PortfolioGitService portfolio;

    public PortfolioInteropAdapter(ProjectPortfolioService projects, PortfolioGitService portfolio) {
        this.projects = projects;
        this.portfolio = portfolio;
    }

    @Override
    public List<ProjectData> listProjects(String username, WorkspaceContext context) {
        return projects.listProjects(username, context).stream()
                .map(p -> new ProjectData(p.id(), p.title())).toList();
    }

    @Override
    public ProjectData getProject(Long projectId, String username, WorkspaceContext context) {
        var project = projects.getProject(projectId, username, context);
        return new ProjectData(project.id(), project.title());
    }

    @Override
    public void requireProject(Long projectId, String username, WorkspaceContext context) {
        projects.requireProject(projectId, username, context);
    }

    @Override
    public void requireProjectForUpdate(Long projectId, String username, WorkspaceContext context) {
        projects.requireProjectForUpdate(projectId, username, context);
    }

    @Override
    public List<RequirementData> listRequirements(Long projectId, String username, WorkspaceContext context) {
        return projects.listRequirements(projectId, username, context).stream()
                .map(PortfolioInteropAdapter::requirement).toList();
    }

    @Override
    public RequirementsPage listApprovedRequirements(Long projectId, String username, WorkspaceContext context,
                                                     int page, int pageSize) {
        var result = projects.listApprovedRequirements(projectId, username, context, page, pageSize);
        return new RequirementsPage(result.requirements().stream()
                .map(PortfolioInteropAdapter::requirement).toList(), result.hasNext());
    }

    @Override
    public RequirementData getRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context) {
        return requirement(projects.getRequirement(projectId, requirementId, username, context));
    }

    @Override
    public RequirementData createRequirement(Long projectId, ImportedRequirement request,
                                             String username, WorkspaceContext context) {
        return requirement(projects.createRequirement(projectId,
                new CreateRequirementRequest(request.key(), request.title(), request.text(),
                        RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                        ReviewStatus.PROPOSED, username, request.rationale(), provenance(request.source())),
                username, context));
    }

    @Override
    public void archiveRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context) {
        projects.updateRequirement(projectId, requirementId,
                new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null),
                username, context);
    }

    @Override
    public void updateRequirement(Long projectId, Long requirementId, String title, boolean requiresReview,
                                  String username, WorkspaceContext context) {
        projects.updateRequirement(projectId, requirementId,
                new UpdateRequirementRequest(title, requiresReview ? RequirementStatus.DRAFT : null,
                        null, null, null, requiresReview ? ReviewStatus.PROPOSED : null, null), username, context);
    }

    @Override
    public void addRequirementVersion(Long projectId, Long requirementId, String text, String rationale,
                                      ImportProvenance source, String username, WorkspaceContext context) {
        projects.addRequirementVersion(projectId, requirementId,
                new CreateRequirementVersionRequest(text, rationale, provenance(source)), username, context);
    }

    @Override
    public String contributeTo(String source, String username, WorkspaceContext context) {
        return portfolio.contributeTo(source, username, context);
    }

    private static SourceReference provenance(ImportProvenance source) {
        return new SourceReference(null, null, List.of(), source.sectionReference(), null, source.originalText());
    }

    private static RequirementData requirement(RequirementView value) {
        var version = value.currentVersion();
        return new RequirementData(value.id(), value.requirementKey(), value.title(),
                value.status() == null ? null : value.status().name(), value.currentVersionId(), value.updatedAt(),
                version == null ? null : new VersionData(version.text(), version.createdAt()));
    }
}
'''

BRIDGE_TEST = '''package com.taxonomy.composition.interop;

import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.IntegrationPortfolioPort;
import com.taxonomy.interop.IntegrationPortfolioPort.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioInteropAdapterTest {
    private final ProjectPortfolioService projects = mock(ProjectPortfolioService.class, RETURNS_DEEP_STUBS);
    private final PortfolioGitService portfolio = mock(PortfolioGitService.class);
    private final IntegrationPortfolioPort port = new PortfolioInteropAdapter(projects, portfolio);
    private final WorkspaceContext scope = new WorkspaceContext("alice", "private", "draft", "repo");
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");

    @ParameterizedTest
    @EnumSource(RequirementStatus.class)
    void preservesRequirementValuesAndTheExistingFingerprint(RequirementStatus status) {
        RequirementView value = mock(RequirementView.class);
        RequirementVersionView version = mock(RequirementVersionView.class);
        when(value.id()).thenReturn(7L); when(value.requirementKey()).thenReturn("key");
        when(value.title()).thenReturn("Title"); when(value.status()).thenReturn(status);
        when(value.currentVersionId()).thenReturn(9L); when(value.updatedAt()).thenReturn(time);
        when(value.currentVersion()).thenReturn(version); when(version.text()).thenReturn("Body");
        when(version.createdAt()).thenReturn(time);
        when(projects.listRequirements(3L, "alice", scope)).thenReturn(List.of(value));
        var mapped = port.listRequirements(3L, "alice", scope).getFirst();
        assertThat(mapped).isEqualTo(new RequirementData(7L, "key", "Title", status.name(), 9L, time, new VersionData("Body", time)));
        var json = new IntegrationJson(JsonMapper.builder().build());
        assertThat(json.fingerprint(List.of(List.of(value.id(), value.title(), value.status(), value.currentVersionId(), value.updatedAt()))))
                .isEqualTo(json.fingerprint(List.of(List.of(mapped.id(), mapped.title(), mapped.status(), mapped.currentVersionId(), mapped.updatedAt()))));
        verify(projects).listRequirements(3L, "alice", scope);
    }

    @Test
    void optionalValuesAreNotInvented() {
        RequirementView value = mock(RequirementView.class);
        when(projects.getRequirement(3L, 7L, "alice", scope)).thenReturn(value);
        var mapped = port.getRequirement(3L, 7L, "alice", scope);
        assertThat(mapped.status()).isNull(); assertThat(mapped.currentVersion()).isNull();
        verify(projects).getRequirement(3L, 7L, "alice", scope);
    }

    @Test
    void delegatesProjectReadsLocksAndFailuresWithTheExactScope() {
        ProjectView project = mock(ProjectView.class);
        when(project.id()).thenReturn(3L); when(project.title()).thenReturn("Project");
        when(projects.listProjects("alice", scope)).thenReturn(List.of(project));
        when(projects.getProject(3L, "alice", scope)).thenReturn(project);
        assertThat(port.listProjects("alice", scope)).containsExactly(new ProjectData(3L, "Project"));
        assertThat(port.getProject(3L, "alice", scope)).isEqualTo(new ProjectData(3L, "Project"));
        port.requireProject(3L, "alice", scope); port.requireProjectForUpdate(3L, "alice", scope);
        verify(projects).requireProject(3L, "alice", scope);
        verify(projects).requireProjectForUpdate(3L, "alice", scope);
        var failure = new IllegalArgumentException("not accessible");
        doThrow(failure).when(projects).requireProjectForUpdate(4L, "alice", scope);
        assertThatThrownBy(() -> port.requireProjectForUpdate(4L, "alice", scope)).isSameAs(failure);
    }

    @Test
    void retainsApprovedPaginationAndProjection() {
        RequirementView value = mock(RequirementView.class);
        when(projects.listApprovedRequirements(3L, "alice", scope, 2, 10).requirements()).thenReturn(List.of(value));
        when(projects.listApprovedRequirements(3L, "alice", scope, 2, 10).hasNext()).thenReturn(true);
        var result = port.listApprovedRequirements(3L, "alice", scope, 2, 10);
        assertThat(result.requirements()).hasSize(1); assertThat(result.hasNext()).isTrue();
        when(portfolio.contributeTo("source", "alice", scope)).thenReturn("projection");
        assertThat(port.contributeTo("source", "alice", scope)).isEqualTo("projection");
        verify(portfolio).contributeTo("source", "alice", scope);
    }

    @Test
    void retainsImportedDefaultsAndProvenance() {
        when(projects.createRequirement(eq(3L), any(CreateRequirementRequest.class), eq("alice"), same(scope)))
                .thenReturn(mock(RequirementView.class));
        port.createRequirement(3L, new ImportedRequirement("EXT-key", "Title", "Body", "Reason",
                new ImportProvenance("integration:source", "Original")), "alice", scope);
        var request = ArgumentCaptor.forClass(CreateRequirementRequest.class);
        verify(projects).createRequirement(eq(3L), request.capture(), eq("alice"), same(scope));
        assertThat(request.getValue()).isEqualTo(new CreateRequirementRequest("EXT-key", "Title", "Body",
                RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED, "alice", "Reason",
                new SourceReference(null, null, List.of(), "integration:source", null, "Original")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void updatesOnlyTheFieldsTheExistingImportRequests(boolean review) {
        port.updateRequirement(3L, 7L, "Title", review, "alice", scope);
        var request = ArgumentCaptor.forClass(UpdateRequirementRequest.class);
        verify(projects).updateRequirement(eq(3L), eq(7L), request.capture(), eq("alice"), same(scope));
        assertThat(request.getValue()).isEqualTo(new UpdateRequirementRequest("Title",
                review ? RequirementStatus.DRAFT : null, null, null, null,
                review ? ReviewStatus.PROPOSED : null, null));
    }

    @Test
    void archivesWithoutOverwritingOtherFieldsAndPreservesVersionMetadata() {
        port.archiveRequirement(3L, 7L, "alice", scope);
        verify(projects).updateRequirement(3L, 7L,
                new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null), "alice", scope);
        port.addRequirementVersion(3L, 7L, "Body", "Reason", new ImportProvenance("source", "Original"), "alice", scope);
        verify(projects).addRequirementVersion(3L, 7L,
                new CreateRequirementVersionRequest("Body", "Reason", new SourceReference(null, null, List.of(), "source", null, "Original")), "alice", scope);
    }
}
'''

PORT_TEST = '''package com.taxonomy.interop;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationPortfolioPortTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"DRAFT", "APPROVED", "ARCHIVED", "FUTURE_STATUS"})
    void preservesStatusAndRecognizesOnlyTheRelevantStates(String status) {
        var value = new IntegrationPortfolioPort.RequirementData(1L, "key", "Title", status, null, null, null);
        assertThat(value.status()).isEqualTo(status);
        assertThat(value.archived()).isEqualTo("ARCHIVED".equals(status));
        assertThat(value.approved()).isEqualTo("APPROVED".equals(status));
    }
}
'''

def test():
    write('taxonomy-app/src/test/java/com/taxonomy/ArchitectureInteropModuleTest.java', PHYSICAL_TEST)

def patch():
    assert git('rev-parse', 'HEAD') == BASE
    sources = sorted((APP / 'interop').rglob('*.java'))
    tests = ['IntegrationDiffTest.java', 'IntegrationJsonTest.java', 'IntegrationServiceCheckpointConflictTest.java',
             'controller/IntegrationDownloadTest.java', 'controller/OslcProviderProtocolTest.java',
             'oslc/OslcProviderServiceTest.java', 'oslc/OslcRemoteProfilesTest.java', 'oslc/OslcTransportTest.java']
    moves = []
    for source in sources + [Path('taxonomy-app/src/test/java/com/taxonomy/interop') / p for p in tests]:
        target = Path(str(source).replace('taxonomy-app/', 'taxonomy-interop/', 1))
        target.parent.mkdir(parents=True, exist_ok=True)
        before = git('hash-object', str(source))
        git('mv', str(source), str(target))
        assert git('hash-object', str(target)) == before
        moves.append([str(source), str(target), before])
    for directory in sorted((APP / 'interop').rglob('*'), reverse=True):
        if directory.is_dir(): directory.rmdir()
    if (APP / 'interop').exists(): (APP / 'interop').rmdir()
    move_tree = git('write-tree')
    write('.git/interop-moves.json', json.dumps({'tree': move_tree, 'moves': moves}))
    print('HISTORY:', len(sources), 'production files and', len(tests), 'tests moved byte-identically', flush=True)

    root = MODULE / 'src/main/java/com/taxonomy/interop'
    write(root / 'IntegrationPortfolioPort.java', PORT)
    write('taxonomy-app/src/main/java/com/taxonomy/composition/interop/PortfolioInteropAdapter.java', BRIDGE)
    write('taxonomy-app/src/test/java/com/taxonomy/composition/interop/PortfolioInteropAdapterTest.java', BRIDGE_TEST)
    write(MODULE / 'src/test/java/com/taxonomy/interop/IntegrationPortfolioPortTest.java', PORT_TEST)
    p = root / 'IntegrationDomainAdapter.java'
    s = p.read_text()
    s = re.sub(r'^import com\.taxonomy\.portfolio\.[^\n]+\n', '', s, flags=re.M)
    s = s.replace('import com.taxonomy.dsl.ast.BlockAst;', 'import com.taxonomy.interop.IntegrationPortfolioPort.*;\nimport com.taxonomy.dsl.ast.BlockAst;', 1)
    s = s.replace('ProjectPortfolioService projects', 'IntegrationPortfolioPort projects')
    s = s.replace('    private final PortfolioGitService portfolio;\n', '')
    s = s.replace('IntegrationPortfolioPort projects, PortfolioGitService portfolio, IntegrationJson json', 'IntegrationPortfolioPort projects, IntegrationJson json')
    s = s.replace('this.projects = projects; this.portfolio = portfolio; this.json = json;', 'this.projects = projects; this.json = json;')
    s = s.replace('RequirementView', 'RequirementData')
    for name in ('requirement', 'before'):
        s = s.replace(name + '.status() == RequirementStatus.ARCHIVED', name + '.archived()')
        s = s.replace(name + '.status() != RequirementStatus.ARCHIVED', '!' + name + '.archived()')
    s = s.replace('r.status() != RequirementStatus.ARCHIVED', '!r.archived()')
    old = '''projects.updateRequirement(connection.projectId(), previous.requirementId(),
                    new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null), context.username(), workspace(context))'''
    assert old in s
    s = s.replace(old, 'projects.archiveRequirement(connection.projectId(), previous.requirementId(), context.username(), workspace(context))')
    s = s.replace('SourceReference provenance = new SourceReference(null, null, List.of(), ', 'ImportProvenance provenance = new ImportProvenance(')
    s = s.replace('stableId(connection.id(), value.id()), null, value.text());', 'stableId(connection.id(), value.id()), value.text());')
    old = '''new CreateRequirementRequest(key, value.title(), value.text(),
                    RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, context.username(), rationale, provenance)'''
    assert old in s
    s = s.replace(old, 'new ImportedRequirement(key, value.title(), value.text(), rationale, provenance)')
    old = '''new UpdateRequirementRequest(value.title(), requiresReview ? RequirementStatus.DRAFT : null,
                    null, null, null, requiresReview ? ReviewStatus.PROPOSED : null, null)'''
    assert old in s
    s = s.replace(old, 'value.title(), requiresReview')
    s = s.replace('new CreateRequirementVersionRequest(value.text(), rationale, provenance)', 'value.text(), rationale, provenance')
    s = s.replace('portfolio.contributeTo(', 'projects.contributeTo(')
    assert 'com.taxonomy.portfolio' not in s and 'RequirementStatus' not in s
    p.write_text(s)
    p = root / 'oslc/OslcProviderService.java'
    s = p.read_text().replace('import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;', 'import com.taxonomy.interop.IntegrationPortfolioPort.RequirementData;')
    s = s.replace('import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;\n', '')
    s = s.replace('import com.taxonomy.portfolio.service.ProjectPortfolioService;', 'import com.taxonomy.interop.IntegrationPortfolioPort;')
    s = s.replace('ProjectPortfolioService', 'IntegrationPortfolioPort').replace('RequirementView', 'RequirementData')
    s = s.replace('requirement.status() != RequirementStatus.APPROVED', '!requirement.approved()')
    assert 'com.taxonomy.portfolio' not in s
    p.write_text(s)
    replace(MODULE / 'src/test/java/com/taxonomy/interop/oslc/OslcProviderServiceTest.java',
            'import com.taxonomy.portfolio.service.ProjectPortfolioService;', 'import com.taxonomy.interop.IntegrationPortfolioPort;')
    replace(MODULE / 'src/test/java/com/taxonomy/interop/oslc/OslcProviderServiceTest.java',
            'mock(ProjectPortfolioService.class)', 'mock(IntegrationPortfolioPort.class)')
    replace('taxonomy-app/src/test/java/com/taxonomy/interop/IntegrationArchitectureProjectionTest.java',
            'new IntegrationDomainAdapter(null, null,', 'new IntegrationDomainAdapter(null,')

    dependency = '''        <dependency>
            <groupId>com.taxonomy</groupId>
            <artifactId>{}</artifactId>
            <version>${{project.version}}</version>
        </dependency>
'''
    template = Path('taxonomy-templates/pom.xml').read_text()
    start, end = template.index('    <dependencies>'), template.index('    <build>')
    deps = '    <dependencies>\n' + ''.join(dependency.format(x) for x in ('taxonomy-domain', 'taxonomy-dsl', 'taxonomy-export', 'taxonomy-extension-api', 'taxonomy-workspace'))
    for artifact in ('spring-boot-starter-web', 'spring-boot-starter-data-jpa'):
        deps += '        <dependency>\n            <groupId>org.springframework.boot</groupId>\n            <artifactId>' + artifact + '</artifactId>\n        </dependency>\n'
    deps += '''        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
'''
    pom = (template[:start] + deps + template[end:]).replace('taxonomy-templates', 'taxonomy-interop').replace('Taxonomy Templates', 'Taxonomy Interoperability').replace('enforce-templates-boundary', 'enforce-interop-boundary')
    pom = pom.replace('Document template Git storage, OOXML validation, materialization and WebDAV', 'Reviewed external-tool exchanges, durable mappings and OSLC integration')
    pom = pom.replace('Templates is a library; application composition depends on templates, never the reverse.', 'Interoperability is a library; application composition depends on it, never the reverse.')
    write(MODULE / 'pom.xml', pom)
    replace('pom.xml', '        <module>taxonomy-templates</module>', '        <module>taxonomy-templates</module>\n        <module>taxonomy-interop</module>')
    for path in ('taxonomy-app/pom.xml', 'taxonomy-coverage/pom.xml'):
        replace(path, '    <dependencies>\n', '    <dependencies>\n' + dependency.format('taxonomy-interop'))
    for path in ('pom.xml', '.mvn/verification-suites.json'):
        replace(path, 'ArchitectureTemplatesModuleTest', 'ArchitectureTemplatesModuleTest,ArchitectureInteropModuleTest')
    replace(RATCHET, 'List.of("taxonomy-app", "taxonomy-workspace", "taxonomy-templates")', 'List.of("taxonomy-app", "taxonomy-workspace", "taxonomy-templates", "taxonomy-interop")')
    p = Path('taxonomy-app/src/test/java/com/taxonomy/config/ConfigurationReferenceContractTest.java')
    replace(p, '"taxonomy-templates"', '"taxonomy-templates", "taxonomy-interop"')
    for suffix in ('pom.xml', 'src'):
        anchor = 'COPY taxonomy-templates/' + suffix + ' taxonomy-templates/' + suffix
        replace('Dockerfile', anchor, anchor + '\nCOPY taxonomy-interop/' + suffix + ' taxonomy-interop/' + suffix)
    p = Path('.github/coverage-policy.json'); policy = json.loads(p.read_text())
    assert 'taxonomy-interop' not in policy['expectedGroups']
    policy['expectedGroups'].append('taxonomy-interop'); p.write_text(json.dumps(policy, indent=2) + '\n')
    p = Path('.github/critical-coverage-policy.json'); old = p.read_text()
    p.write_text(old.replace('taxonomy-app/src/main/java/com/taxonomy/interop/', 'taxonomy-interop/src/main/java/com/taxonomy/interop/'))
    for p in Path('.github/workflows').glob('*.yml'):
        s = p.read_text(); updated = s.replace('|templates)', '|templates|interop)')
        updated = re.sub(r'(?m)^(\s*- [\'\"]taxonomy-templates/\*\*[\'\"]\s*)$', lambda m: m[0] + '\n' + m[0].replace('taxonomy-templates', 'taxonomy-interop'), updated)
        if updated != s: p.write_text(updated)
    for p in [Path('docs/dev/01-change-map.md'), *Path('docs/dev/tasks').glob('*.md')]:
        s = p.read_text(); changed = s.replace('taxonomy-app/src/main/java/com/taxonomy/interop/', 'taxonomy-interop/src/main/java/com/taxonomy/interop/')
        if s != changed: p.write_text(changed)
    p = Path('docs/dev/REACTOR_COVERAGE.md')
    replace(p, '7. `taxonomy-templates`', '7. `taxonomy-templates`\n8. `taxonomy-interop`')
    for lang in ('en', 'de'):
        p = Path('docs') / lang / 'MODULE_BOUNDARIES.md'; s = p.read_text()
        s = s.replace('ten modules', 'eleven modules').replace('zehn Module', 'elf Module')
        s = s.replace('`taxonomy-templates/src/main/java/com/taxonomy`:', '`taxonomy-templates/src/main/java/com/taxonomy`, `taxonomy-interop/src/main/java/com/taxonomy`:')
        rows = [line for line in s.splitlines() if line.startswith('| `taxonomy-templates`')]
        assert len(rows) == 1, rows
        row = '| `taxonomy-interop` | Reviewed external-tool interoperability, mappings, checkpoints and OSLC; portfolio access through an explicit port |' if lang == 'en' else '| `taxonomy-interop` | Geprüfte externe Werkzeuganbindung, Mappings, Checkpoints und OSLC; Portfoliozugriff über einen expliziten Port |'
        s = s.replace(rows[0], rows[0] + '\n' + row, 1)
        if lang == 'en':
            s += '\n\n## Physical interoperability module\n\n`taxonomy-interop` now owns all interoperability production packages. It depends on the existing foundations and workspace, not on the application or portfolio implementation. `IntegrationPortfolioPort` exposes only the required scoped project/requirement operations and neutral values. `PortfolioInteropAdapter` in application composition delegates to the unchanged portfolio services, including access checks and locking. The original import decisions, provenance and checkpoint ordering remain in interoperability. Application-level flow, journal and restart tests remain in `taxonomy-app`; eight existing unit-test classes move with the library. Four planned feature modules remain after workspace, templates and interoperability. Physical ownership here describes this source revision, not PR merge status.\n'
        else:
            s += '\n\n## Physisches Interoperabilitätsmodul\n\n`taxonomy-interop` enthält jetzt alle produktiven Interoperabilitätspakete. Es hängt von den bestehenden Grundlagen und Workspace ab, nicht von Anwendung oder Portfolioimplementierung. `IntegrationPortfolioPort` stellt nur die benötigten gescopten Projekt-/Anforderungsoperationen und neutralen Werte bereit. `PortfolioInteropAdapter` in der Anwendungskomposition delegiert einschließlich Zugriffsprüfung und Sperren an die unveränderten Portfoliodienste. Importentscheidungen, Provenienz und Checkpoint-Reihenfolge bleiben in der Interoperabilität. Anwendungsweite Ablauf-, Journal- und Neustarttests bleiben in `taxonomy-app`; acht bestehende Unit-Testklassen ziehen mit der Bibliothek um. Nach Workspace, Templates und Interoperabilität fehlen noch vier geplante Fachmodule. Die physische Zuständigkeit beschreibt diesen Quellstand, nicht den Merge-Status.\n'
        p.write_text(s)
    write(MODULE / 'README.md', '''# Taxonomy Interoperability

Ordinary library for reviewed external-tool exchanges, connector orchestration,
durable identity mappings/checkpoints/events and OSLC application integration.

The production packages retain their names and file histories. The application
still has one Spring Boot deployment. Configuration and SQL migrations stay in
`taxonomy-app`; this library never depends back on the application.

`IntegrationPortfolioPort` owns the small set of required scoped portfolio
operations. Application composition binds it to the existing portfolio services;
authorization, project locks, requirement versions and projection logic retain
their existing authority. Neutral port values contain no portfolio implementation
types. Interoperability consumes workspace ports, not editor persistence.

Eight existing unit-test classes move here. Cross-context flow, journal, restart
and architecture-projection tests remain in the application and exercise this
library. The module participates in the normal reactor and aggregate coverage.

From the repository root:

```sh
./mvnw -B -pl taxonomy-interop -am test
./mvnw -B verify -Pci
```
''')
    s = Path('taxonomy-build/src/test/java/com/taxonomy/build/WorkspaceModulePackagingIT.java').read_text()
    s = s.replace('WorkspaceModulePackagingIT', 'InteropModulePackagingIT').replace('OneWorkspaceLibraryAndNoDuplicateWorkspaceClasses', 'OneInteropLibraryAndNoDuplicateInteropClasses')
    s = s.replace('taxonomy-workspace-', 'taxonomy-interop-').replace('List.of("workspace", "versioning", "editor")', 'List.of("interop")')
    s = s.replace('"com/taxonomy/editor/persistence/EditorJournal.class",\n                    "com/taxonomy/workspace/storage/DslGitRepository.class",\n                    "com/taxonomy/versioning/service/RepositoryStateService.class"', '"com/taxonomy/interop/IntegrationService.class",\n                    "com/taxonomy/interop/persistence/IntegrationStore.class",\n                    "com/taxonomy/interop/oslc/OslcProviderService.class"')
    write('taxonomy-build/src/test/java/com/taxonomy/build/InteropModulePackagingIT.java', s)
    original = RATCHET.read_text(); write('.git/interop-ratchet.java', original)
    method = '''    @Test
    void recordInteropExtractionBaseline() throws Exception {
        Path root = findRepositoryRoot();
        ContextPolicy policy = readAndValidateContextPolicy(root.resolve(".github/architecture-contexts.json"));
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/interop-dependency-baseline.json"), renderBaseline(collectDependencies(policy)));
    }

'''
    replace(RATCHET, '    private ContextPolicy readAndValidateContextPolicy(Path policyPath) throws Exception {', method + '    private ContextPolicy readAndValidateContextPolicy(Path policyPath) throws Exception {')
    git('diff', '--check')

def baseline():
    RATCHET.write_text(Path('.git/interop-ratchet.java').read_text())
    old = json.loads(Path('.github/architecture-dependency-baseline.json').read_text())
    new = json.loads(Path('target/interop-dependency-baseline.json').read_text())
    key = lambda e: (e['fromContext'], e['fromPackage'], e['toContext'], e['toPackage'])
    before = {key(e): e['classDependencyCount'] for e in old['edges']}
    after = {key(e): e['classDependencyCount'] for e in new['edges']}
    for k in sorted(before.keys() | after.keys()):
        if before.get(k) != after.get(k):
            assert k[1] in ('com.taxonomy.interop', 'com.taxonomy.interop.oslc', 'com.taxonomy.composition.interop'), k
            print('REVIEWED EDGE', k, before.get(k, 0), '->', after.get(k, 0), flush=True)
    assert not any(e['fromContext'] == 'interop' and e['toContext'] in ('portfolio', 'app-composition') for e in new['edges'])
    Path('.github/architecture-dependency-baseline.json').write_text(json.dumps(new, indent=2) + '\n')

def publish():
    assert 'recordInteropExtractionBaseline' not in RATCHET.read_text()
    assert git('rev-parse', 'HEAD') == BASE
    git('diff', '--check')
    git('add', '-A')
    final_tree = git('write-tree')
    moves = json.loads(Path('.git/interop-moves.json').read_text())
    def post(endpoint, body):
        req = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/' + endpoint,
            data=json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
            'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as response: return json.load(response)
    def store_tree(tree):
        entries = []
        for line in git('diff', '--name-status', '--no-renames', BASE, tree).splitlines():
            status, path = line.split('\t', 1)
            assert not path.startswith('verification/') and not re.search(r'/628-[^/]+\.yml$', path), path
            if status == 'D': entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': None}); continue
            meta = git('ls-tree', tree, '--', path).split()
            blob = meta[2]
            if status != 'A' or not any(path == m[1] and blob == m[2] for m in moves['moves']):
                content = subprocess.check_output(['git', 'cat-file', 'blob', blob])
                stored = post('git/blobs', {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})['sha']
                assert stored == blob
            entries.append({'path': path, 'mode': meta[0], 'type': 'blob', 'sha': blob})
        result = post('git/trees', {'base_tree': git('rev-parse', BASE + '^{tree}'), 'tree': entries})['sha']
        assert result == tree, (tree, result)
        return result
    result = {'base': BASE, 'move_tree': store_tree(moves['tree']), 'final_tree': store_tree(final_tree), 'moves': moves['moves']}
    write(Path(os.environ['RUNNER_TEMP']) / 'verified-interop.json', json.dumps(result, indent=2))
    print('VERIFIED_INTEROP', json.dumps(result), flush=True)
    print(git('diff', '--stat', BASE, final_tree), flush=True)

{'test': test, 'patch': patch, 'baseline': baseline, 'publish': publish}[sys.argv[1]]()
