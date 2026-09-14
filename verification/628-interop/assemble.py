from pathlib import Path
import json, re, shutil, subprocess, sys

CONTROL = Path(__file__).resolve().parent
BASE = 'c121be0701ad5dd4716e1e6922be23a6db2f6d9f'

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)

def replace(text, old, new, count=1):
    assert text.count(old) == count, (old, text.count(old), count)
    return text.replace(old, new)

def constructor_arguments(text, start):
    depth, quote, escape, parts, mark = 0, None, False, [], start
    for index in range(start, len(text)):
        char = text[index]
        if quote:
            if escape: escape = False
            elif char == '\\': escape = True
            elif char == quote: quote = None
        elif char in ('"', "'"): quote = char
        elif char in '([{': depth += 1
        elif char in ')]}':
            if char == ')' and depth == 0:
                parts.append(text[mark:index].strip())
                return parts, index
            depth -= 1
        elif char == ',' and depth == 0:
            parts.append(text[mark:index].strip()); mark = index + 1
    raise AssertionError('unterminated constructor')

assert git('rev-parse', 'HEAD') == BASE
path = Path('taxonomy-app/src/main/java/com/taxonomy/interop/IntegrationDomainAdapter.java')
s = path.read_text()
start = s.index('    public AppliedRequirement applyRequirement(')
end = s.index('    public List<ArchitectureCommand> architectureCommands(', start)
mutation = s[start:end].rstrip()
# Retain the existing requirement mutation verbatim apart from the original DTO's qualification.
mutation = mutation.replace('RequirementView created', 'PortfolioDtos.RequirementView created').replace('RequirementView before', 'PortfolioDtos.RequirementView before')
adapter = (CONTROL / 'PortfolioInteropAdapter.java').read_text()
adapter = replace(adapter, '    // @REQUIREMENT_MUTATION@', '    @Override\n' + mutation)
write('taxonomy-app/src/main/java/com/taxonomy/composition/interop/PortfolioInteropAdapter.java', adapter)
s = s[:start] + '''    public AppliedRequirement applyRequirement(RepositoryContext context, Connection connection, Artifact value, Identity previous, String rationale) {
        return projects.applyRequirement(context, connection, value, previous, rationale);
    }

''' + s[end:]
for imported in ('com.taxonomy.portfolio.dto.PortfolioDtos.*', 'com.taxonomy.portfolio.model.PortfolioTypes.*',
                 'com.taxonomy.portfolio.service.PortfolioGitService', 'com.taxonomy.portfolio.service.ProjectPortfolioService'):
    s = replace(s, 'import ' + imported + ';\n', '')
s = replace(s, 'import com.taxonomy.interop.persistence.IntegrationStore.Connection;',
            'import com.taxonomy.interop.InteropPortfolioPort.AppliedRequirement;\nimport com.taxonomy.interop.InteropPortfolioPort.RequirementView;\nimport com.taxonomy.interop.persistence.IntegrationStore.Connection;')
s = replace(s, '    private final ProjectPortfolioService projects;\n    private final PortfolioGitService portfolio;', '    private final InteropPortfolioPort projects;')
s = replace(s, 'public IntegrationDomainAdapter(ProjectPortfolioService projects, PortfolioGitService portfolio, IntegrationJson json)', 'public IntegrationDomainAdapter(InteropPortfolioPort projects, IntegrationJson json)')
s = replace(s, 'this.projects = projects; this.portfolio = portfolio; this.json = json;', 'this.projects = projects; this.json = json;')
s = replace(s, '    public record AppliedRequirement(String businessIdentity, Long requirementId) {}\n', '')
s = replace(s, 'projects.listRequirements(connection.projectId(), context.username(), workspace(context))', 'projects.listRequirements(context, connection.projectId())', 2)
s = replace(s, 'projects.requireProject(projectId, context.username(), workspace(context))', 'projects.requireProject(context, projectId)')
s = replace(s, 'projects.requireProjectForUpdate(projectId, context.username(), workspace(context))', 'projects.lockProject(context, projectId)')
s = replace(s, 'requirement.status() == RequirementStatus.ARCHIVED', 'requirement.archived()')
s = replace(s, 'requirement.status() != RequirementStatus.ARCHIVED', '!requirement.archived()')
s = replace(s, 'r.status() != RequirementStatus.ARCHIVED', '!r.archived()')
s = replace(s, 'return source -> ArchitectureSemanticPatch.applyProjection(source, portfolio.contributeTo(source, context.username(), workspace(context)));', 'return projects.portfolioContribution(context);')
assert 'com.taxonomy.portfolio' not in s
path.write_text(s)

path = Path('taxonomy-app/src/main/java/com/taxonomy/interop/IntegrationService.java')
s = replace(path.read_text(), 'IntegrationDomainAdapter.AppliedRequirement', 'InteropPortfolioPort.AppliedRequirement')
path.write_text(s)
path = Path('taxonomy-app/src/main/java/com/taxonomy/interop/oslc/OslcProviderService.java')
s = path.read_text()
s = replace(s, 'import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;', 'import com.taxonomy.interop.InteropPortfolioPort;\nimport com.taxonomy.interop.InteropPortfolioPort.RequirementView;')
s = replace(s, 'import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;\n', '')
s = replace(s, 'import com.taxonomy.portfolio.service.ProjectPortfolioService;\n', '')
s = s.replace('ProjectPortfolioService projects', 'InteropPortfolioPort projects')
s = replace(s, 'projects.listProjects(context.username(), IntegrationDomainAdapter.workspace(context))', 'projects.listProjects(context)')
s = replace(s, 'projects.getProject(projectId, context.username(), IntegrationDomainAdapter.workspace(context))', 'projects.getProject(context, projectId)')
s = replace(s, 'projects.listApprovedRequirements(projectId, context.username(), IntegrationDomainAdapter.workspace(context), page, pageSize)', 'projects.listApprovedRequirements(context, projectId, page, pageSize)')
s = replace(s, 'projects.getRequirement(projectId, requirementId, context.username(), IntegrationDomainAdapter.workspace(context))', 'projects.getRequirement(context, projectId, requirementId)')
s = replace(s, 'requirement.status() != RequirementStatus.APPROVED', '!requirement.approved()')
s = replace(s, 'import com.taxonomy.interop.IntegrationDomainAdapter;\n', '')
path.write_text(s)
write('taxonomy-app/src/main/java/com/taxonomy/interop/InteropPortfolioPort.java', (CONTROL / 'InteropPortfolioPort.java').read_text())

# Adapt direct test construction; retain every assertion and all Spring integration tests.
for path in Path('taxonomy-app/src/test/java').rglob('*.java'):
    s = original = path.read_text()
    for kind in ('IntegrationDomainAdapter', 'OslcProviderService'):
        marker = 'new ' + kind + '('; cursor = 0
        while (position := s.find(marker, cursor)) >= 0:
            start = position + len(marker)
            args, end = constructor_arguments(s, start)
            if kind == 'IntegrationDomainAdapter':
                assert len(args) == 3, (path, args)
                args = ['new PortfolioInteropAdapter(' + args[0] + ', ' + args[1] + ')', args[2]]
            else:
                assert len(args) == 5, (path, args)
                args = ['new PortfolioInteropAdapter(' + args[0] + ', null)', *args[1:]]
            s = s[:start] + ', '.join(args) + s[end:]
            cursor = start + len(', '.join(args)) + 1
    if s != original:
        s = re.sub(r'^(package [^;]+;)', r'\1\n\nimport com.taxonomy.composition.interop.PortfolioInteropAdapter;', s, count=1, flags=re.M)
        path.write_text(s)
        print('TEST_CONSTRUCTOR', path)
write('taxonomy-app/src/test/java/com/taxonomy/composition/interop/PortfolioInteropAdapterTest.java', (CONTROL / 'PortfolioInteropAdapterTest.java').read_text())

# Save the seam-only tree: these files still live in taxonomy-app. A distinct
# subsequent relocation tree allows Git to follow every existing moved source exactly.
git('add', '-A')
seam_tree = git('write-tree')
write(Path('/tmp/interop-seam-tree'), seam_tree)

old = Path('taxonomy-app/src/main/java/com/taxonomy/interop')
new = Path('taxonomy-interop/src/main/java/com/taxonomy/interop')
new.parent.mkdir(parents=True, exist_ok=True)
shutil.move(str(old), str(new))
for relative in ('IntegrationDiffTest.java', 'IntegrationJsonTest.java', 'oslc/OslcRemoteProfilesTest.java', 'oslc/OslcTransportTest.java'):
    old = Path('taxonomy-app/src/test/java/com/taxonomy/interop') / relative
    new = Path('taxonomy-interop/src/test/java/com/taxonomy/interop') / relative
    new.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(old), str(new))
write('taxonomy-app/src/test/java/com/taxonomy/ArchitectureInteropModuleTest.java', (CONTROL / 'ArchitectureInteropModuleTest.java').read_text())

module_dependencies = ''.join('''        <dependency>
            <groupId>com.taxonomy</groupId>
            <artifactId>''' + name + '''</artifactId>
            <version>${project.version}</version>
        </dependency>
''' for name in ('taxonomy-domain', 'taxonomy-dsl', 'taxonomy-export', 'taxonomy-extension-api', 'taxonomy-workspace'))
write('taxonomy-interop/pom.xml', '''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.taxonomy</groupId>
        <artifactId>taxonomy</artifactId>
        <version>1.4.0-SNAPSHOT</version>
    </parent>
    <artifactId>taxonomy-interop</artifactId>
    <name>Taxonomy Interoperability</name>
    <description>Reviewed external-tool connections, exchange operations, mappings, checkpoints and OSLC resources</description>
    <dependencies>
''' + module_dependencies + '''        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.httpcomponents.client5</groupId>
            <artifactId>httpclient5</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-enforcer-plugin</artifactId>
                <executions>
                    <execution>
                        <id>enforce-interop-boundary</id>
                        <phase>validate</phase>
                        <goals><goal>enforce</goal></goals>
                        <configuration>
                            <rules>
                                <bannedDependencies>
                                    <searchTransitive>true</searchTransitive>
                                    <excludes><exclude>com.taxonomy:taxonomy-app</exclude></excludes>
                                    <message>Interoperability is a library; portfolio integration is supplied through its scoped port.</message>
                                </bannedDependencies>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
''')
path = Path('pom.xml')
s = replace(path.read_text(), '<module>taxonomy-templates</module>', '<module>taxonomy-templates</module>\n        <module>taxonomy-interop</module>')
s = replace(s, 'ArchitectureTemplatesModuleTest', 'ArchitectureTemplatesModuleTest,ArchitectureInteropModuleTest')
path.write_text(s)
for filename in ('taxonomy-app/pom.xml', 'taxonomy-coverage/pom.xml'):
    path = Path(filename); s = path.read_text()
    blocks = [m.group(0) for m in re.finditer(r'        <dependency>.*?</dependency>', s, re.S) if '<artifactId>taxonomy-templates</artifactId>' in m.group(0)]
    assert len(blocks) == 1
    s = s.replace(blocks[0], blocks[0] + '\n' + blocks[0].replace('taxonomy-templates','taxonomy-interop'))
    path.write_text(s)
path = Path('.mvn/verification-suites.json')
s = replace(path.read_text(), 'ArchitectureTemplatesModuleTest', 'ArchitectureTemplatesModuleTest,ArchitectureInteropModuleTest')
path.write_text(s)
path = Path('.github/coverage-policy.json')
policy = json.loads(path.read_text()); policy['expectedGroups'].append('taxonomy-interop')
path.write_text(json.dumps(policy, indent=2) + '\n')

for filename in ('taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java',
                 'taxonomy-app/src/test/java/com/taxonomy/config/ConfigurationReferenceContractTest.java'):
    path = Path(filename); s = path.read_text()
    assert '"taxonomy-templates"' in s
    s = s.replace('"taxonomy-templates"', '"taxonomy-templates", "taxonomy-interop"')
    path.write_text(s)

path = Path('Dockerfile')
lines = path.read_text().splitlines(keepends=True)
path.write_text(''.join(line + (line.replace('taxonomy-templates','taxonomy-interop') if line.lstrip().startswith('COPY ') and 'taxonomy-templates' in line else '') for line in lines))
for path in Path('.github/workflows').glob('*.yml'):
    s = path.read_text(); lines = []
    for line in s.splitlines(keepends=True):
        lines.append(line)
        if re.match(r'\s*-\s*[\'\"]?taxonomy-templates/', line):
            lines.append(line.replace('taxonomy-templates','taxonomy-interop'))
    changed = ''.join(lines).replace('taxonomy-templates/pom.xml taxonomy-templates/src/main',
                                     'taxonomy-templates/pom.xml taxonomy-templates/src/main taxonomy-interop/pom.xml taxonomy-interop/src/main')
    if changed != s: path.write_text(changed)

# Existing per-source/package thresholds and application SQL stay untouched.
assert git('diff', BASE, '--', '.github/critical-coverage-policy.json', 'taxonomy-app/src/main/resources/db') == ''
for language, old_count, new_count, role in (
    ('en','ten modules','eleven modules','Reviewed external-tool interoperability, exchange history and OSLC'),
    ('de','zehn Module','elf Module','Reviewter Austausch mit externen Werkzeugen, Austauschhistorie und OSLC')):
    path = Path('docs') / language / 'MODULE_BOUNDARIES.md'; s = path.read_text()
    s = replace(s, old_count, new_count)
    lines = s.splitlines(keepends=True)
    row = '| `taxonomy-interop` | ' + role + ' |\n'
    s = ''.join(line + (row if line.startswith('| `taxonomy-templates` |') else '') for line in lines)
    s = s.replace('`taxonomy-templates/src/main/java/com/taxonomy`', '`taxonomy-templates/src/main/java/com/taxonomy`, `taxonomy-interop/src/main/java/com/taxonomy`')
    note = ('\n## Physical interoperability module\n\n`taxonomy-interop` contains the exchange services, durable mappings/checkpoints/events and OSLC endpoints. '
            '`InteropPortfolioPort` carries only explicit scope and neutral requirement/project projections. '
            '`PortfolioInteropAdapter` composes the existing portfolio authority in `taxonomy-app`; requirement mutations, '
            'authorization, state fingerprints and Git projection semantics are preserved. The remaining four feature modules '
            'are not yet extracted. Physical presence in this revision does not imply a merged PR.\n') if language == 'en' else (
            '\n## Physisches Interoperabilitätsmodul\n\n`taxonomy-interop` enthält Austauschdienste, dauerhafte Mappings/Checkpoints/Ereignisse und OSLC-Endpunkte. '
            '`InteropPortfolioPort` überträgt den expliziten Kontext und neutrale Projekt-/Anforderungssichten. '
            '`PortfolioInteropAdapter` verbindet in `taxonomy-app` die bestehende Portfolio-Autorität. Anforderungsänderungen, '
            'Autorisierung, Zustands-Fingerprints und Git-Projektionen behalten ihre Semantik. Vier Fachmodule fehlen noch; '
            'physische Existenz in dieser Revision bedeutet nicht, dass der PR bereits gemergt ist.\n')
    path.write_text(s + note)
path = Path('docs/dev/REACTOR_COVERAGE.md'); s = path.read_text()
s = replace(s, '7. `taxonomy-templates`', '7. `taxonomy-templates`\n8. `taxonomy-interop`')
path.write_text(s)
write('taxonomy-interop/README.md', '''# Taxonomy Interoperability

Ordinary Maven library for reviewed exchange connections, imported/exported evidence,
identity mappings, checkpoints, event history, OSLC resources and configured remote transports.
It remains part of the single `taxonomy-app` deployment.

The library uses workspace-owned context/read/integration APIs and framework-free
DSL, exchange and extension contracts. `InteropPortfolioPort` declares its scoped
portfolio requirements; `taxonomy-app` supplies `PortfolioInteropAdapter` using the
existing portfolio services. No portfolio repository or implementation DTO leaks
into the library, and Maven prohibits dependencies back to `taxonomy-app`.

Application-level Spring, protocol, persistence and restart tests remain in
`taxonomy-app`. Pure diff/JSON/remote-profile/transport tests accompany the library.
Global configuration and SQL migrations remain application-owned.

Run validation from the repository root with the Maven Wrapper. The architecture
profile checks physical ownership and the absence of reverse implementation dependencies;
canonical `./mvnw -B verify -Pci` remains required for integration.
''')

# Reuse the already exercised nested-JAR contract rather than introducing a scanner framework.
s = Path('taxonomy-build/src/test/java/com/taxonomy/build/WorkspaceModulePackagingIT.java').read_text()
s = s.replace('WorkspaceModulePackagingIT','InteropModulePackagingIT').replace('bootApplicationContainsOneWorkspaceLibraryAndNoDuplicateWorkspaceClasses','bootApplicationContainsOneInteropLibraryAndNoDuplicateInteropClasses').replace('taxonomy-workspace-', 'taxonomy-interop-')
s = s.replace('List.of("workspace", "versioning", "editor")', 'List.of("interop")')
s = s.replace('"com/taxonomy/editor/persistence/EditorJournal.class",\n                    "com/taxonomy/workspace/storage/DslGitRepository.class",\n                    "com/taxonomy/versioning/service/RepositoryStateService.class"',
              '"com/taxonomy/interop/IntegrationService.class",\n                    "com/taxonomy/interop/persistence/IntegrationStore.class",\n                    "com/taxonomy/interop/oslc/OslcProviderService.class"')
write('taxonomy-build/src/test/java/com/taxonomy/build/InteropModulePackagingIT.java', s)
git('add','-A')
print('ASSEMBLED', git('diff','--cached','--stat'))
print('SEAM_TREE', seam_tree)
