package com.taxonomy.dsl.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class JgitStorageDocumentationContractTest {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final List<String> GUIDE_PATHS = List.of(
            "docs/en/JGIT_STORAGE_HIBERNATE.md",
            "docs/de/JGIT_STORAGE_HIBERNATE.md");

    private static final List<String> NORMAL_MIGRATIONS = List.of(
            "V0_1_4__create_core_schema.sql",
            "V0_1_5__establish_versioned_core_schema.sql",
            "V0_1_14__add_repository_lock.sql",
            "V0_1_14_1__add_chunked_pack_storage.sql",
            "V0_1_14_2__add_pack_write_leases.sql",
            "V0_1_17__optimize_reverse_reflog_reads.sql",
            "V0_1_18__persist_pack_description_metadata.sql",
            "V0_9_1__add_reflog_reference_key.sql",
            "V0_9_2__add_reflog_delivery_id.sql");

    private static final List<String> TAXONOMY_ADOPTION_MIGRATIONS = List.of(
            "V1__adopt_pre_library_schema.sql",
            "V2__normalize_legacy_taxonomy_column_lengths.sql");

    private static final List<String> SQL_SERVER_ADOPTION_MIGRATIONS = List.of(
            "V1__adopt_pre_library_schema.sql",
            "V2__normalize_legacy_sandbox_column_types.sql");

    @Test
    void guidesFollowTheReleasedDependencyAndAnonymousRepositoryFromTheRootPom()
            throws IOException {
        Path root = findRepositoryRoot();
        String pom = read(root.resolve("pom.xml"));
        Distribution distribution = distributionFrom(pom);
        String authoritativeCiCommand = authoritativeCiCommand(read(
                root.resolve(".github/copilot-instructions.md")));

        assertThat(distribution.repositoryBlock())
                .contains("<releases><enabled>true</enabled></releases>")
                .contains("<snapshots><enabled>false</enabled></snapshots>");

        for (String guidePath : GUIDE_PATHS) {
            String guide = read(root.resolve(guidePath));

            assertThat(guide)
                    .as(guidePath)
                    .contains(
                            "<jgit-storage-hibernate.version>"
                                    + distribution.version()
                                    + "</jgit-storage-hibernate.version>",
                            "<id>" + distribution.repositoryId() + "</id>",
                            "<url>" + distribution.repositoryUrl() + "</url>",
                            "<releases><enabled>true</enabled></releases>",
                            "<snapshots><enabled>false</enabled></snapshots>",
                            "<artifactId>jgit-storage-hibernate-core</artifactId>",
                            "```bash\n" + authoritativeCiCommand + "\n```")
                    .doesNotContain(
                            "<jgit-storage-hibernate.version>0.1.13"
                                    + "</jgit-storage-hibernate.version>",
                            "maven.pkg.github.com",
                            "GITHUB_ACTOR",
                            "GITHUB_TOKEN",
                            "read:packages",
                            "packages: read",
                            "<id>github</id>");

            assertThat(countOccurrences(guide, distribution.version()))
                    .as("released dependency version occurrences in %s", guidePath)
                    .isEqualTo(1);
            assertThat(countOccurrences(guide, "./mvnw"))
                    .as("canonical Maven command occurrences in %s", guidePath)
                    .isEqualTo(1);
            assertThat(allowedSemanticVersions(distribution.version()))
                    .as("all semantic versions named by %s", guidePath)
                    .containsAll(semanticVersions(guide));
        }
    }

    @Test
    void guidesDescribeThePublicMigrationContractOfTheResolvedCoreArtifact()
            throws IOException {
        Path root = findRepositoryRoot();

        for (String guidePath : GUIDE_PATHS) {
            String guide = read(root.resolve(guidePath));

            assertThat(guide)
                    .as(guidePath)
                    .contains(
                            "CoreSchemaMigrations.HSQLDB_LOCATION",
                            CoreSchemaMigrations.HSQLDB_LOCATION,
                            "CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION",
                            CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION,
                            "CoreSchemaMigrations.POSTGRESQL_LOCATION",
                            CoreSchemaMigrations.POSTGRESQL_LOCATION,
                            "CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION",
                            CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION,
                            "CoreSchemaMigrations.SQL_SERVER_LOCATION",
                            CoreSchemaMigrations.SQL_SERVER_LOCATION,
                            "CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION",
                            CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION,
                            "CoreSchemaMigrations.SCHEMA_HISTORY_TABLE",
                            CoreSchemaMigrations.SCHEMA_HISTORY_TABLE,
                            "CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE",
                            CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE,
                            "CoreSchemaMigrations.LEGACY_ADOPTION_VERSION",
                            "`" + CoreSchemaMigrations.LEGACY_ADOPTION_VERSION + "`");
        }
    }

    @Test
    void resolvedCoreArtifactContainsEveryMigrationResourceNamedByTheGuides() {
        assertResources(CoreSchemaMigrations.HSQLDB_LOCATION, NORMAL_MIGRATIONS);
        assertResources(CoreSchemaMigrations.POSTGRESQL_LOCATION, NORMAL_MIGRATIONS);
        assertResources(CoreSchemaMigrations.SQL_SERVER_LOCATION, NORMAL_MIGRATIONS);

        assertResources(
                CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION,
                TAXONOMY_ADOPTION_MIGRATIONS);
        assertResources(
                CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION,
                TAXONOMY_ADOPTION_MIGRATIONS);
        assertResources(
                CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION,
                SQL_SERVER_ADOPTION_MIGRATIONS);
    }

    @Test
    void profileCommentsDistinguishUpstreamAssetsFromTaxonomyActivation()
            throws IOException {
        Path root = findRepositoryRoot();
        String migrationConfig = read(root.resolve(
                "taxonomy-app/src/main/java/com/taxonomy/dsl/storage/"
                        + "JgitStorageSchemaMigrationConfig.java"));
        String mssql = read(root.resolve(
                "taxonomy-app/src/main/resources/application-mssql.properties"));
        String oracle = read(root.resolve(
                "taxonomy-app/src/main/resources/application-oracle.properties"));

        assertThat(migrationConfig)
                .contains(
                        "HSQLDB(",
                        "POSTGRESQL(",
                        "CoreSchemaMigrations.HSQLDB_LOCATION",
                        "CoreSchemaMigrations.POSTGRESQL_LOCATION")
                .doesNotContain("SQL_SERVER(", "ORACLE(");

        assertThat(mssql)
                .contains(
                        "now packages SQL Server Core and legacy-adoption",
                        "selects only HSQLDB and PostgreSQL",
                        "spring.flyway.enabled=false")
                .doesNotContain(
                        "does not currently publish SQL Server Core migrations");

        assertThat(oracle)
                .contains(
                        "exposes no Oracle Core or legacy-adoption",
                        "spring.flyway.enabled=false");
    }

    private static Set<String> allowedSemanticVersions(String dependencyVersion) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(dependencyVersion);
        for (String migration : NORMAL_MIGRATIONS) {
            int separator = migration.indexOf("__");
            allowed.add(migration.substring(1, separator).replace('_', '.'));
        }
        return allowed;
    }

    private static Set<String> semanticVersions(String text) {
        Set<String> versions = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                        "(?<![\\d.])\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?(?![\\d.])")
                .matcher(text);
        while (matcher.find()) {
            versions.add(matcher.group());
        }
        return versions;
    }

    private static int countOccurrences(String text, String candidate) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(candidate, offset)) >= 0) {
            count++;
            offset += candidate.length();
        }
        return count;
    }

    private static void assertResources(String location, List<String> fileNames) {
        String directory = withoutClasspathPrefix(location);
        ClassLoader classLoader = JgitStorageDocumentationContractTest.class.getClassLoader();

        for (String fileName : fileNames) {
            String resource = directory + "/" + fileName;
            assertThat(classLoader.getResource(resource))
                    .as("migration resource %s", resource)
                    .isNotNull();
        }
    }

    private static String withoutClasspathPrefix(String location) {
        assertThat(location)
                .as("Flyway location")
                .startsWith(CLASSPATH_PREFIX);
        return location.substring(CLASSPATH_PREFIX.length());
    }

    private static String authoritativeCiCommand(String instructions) {
        Matcher matcher = Pattern.compile(
                        "### CI Command.*?```bash\\R(\\./mvnw[^\\r\\n]+)\\R```",
                        Pattern.DOTALL)
                .matcher(instructions);
        assertThat(matcher.find())
                .as("authoritative CI command in .github/copilot-instructions.md")
                .isTrue();
        return matcher.group(1).trim();
    }

    private static Distribution distributionFrom(String pom) {
        String version = requiredTagValue(pom, "jgit-storage-hibernate.version");
        Matcher repositories = Pattern.compile(
                        "<repository>(.*?)</repository>",
                        Pattern.DOTALL)
                .matcher(pom);

        while (repositories.find()) {
            String block = repositories.group(1);
            if (!block.contains("jgit-storage-hibernate")) {
                continue;
            }
            return new Distribution(
                    version,
                    requiredTagValue(block, "id"),
                    requiredTagValue(block, "url"),
                    block);
        }

        throw new AssertionError(
                "Root POM has no jgit-storage-hibernate release repository");
    }

    private static String requiredTagValue(String xml, String tagName) {
        Matcher matcher = Pattern.compile(
                        "<" + Pattern.quote(tagName) + ">\\s*([^<]+?)\\s*</"
                                + Pattern.quote(tagName) + ">")
                .matcher(xml);
        assertThat(matcher.find())
                .as("POM tag <%s>", tagName)
                .isTrue();
        return matcher.group(1).trim();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve(".github"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate Taxonomy repository root");
    }

    private record Distribution(
            String version,
            String repositoryId,
            String repositoryUrl,
            String repositoryBlock) {
    }
}
