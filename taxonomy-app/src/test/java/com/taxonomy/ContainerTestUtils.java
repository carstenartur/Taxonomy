package com.taxonomy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Future;

import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.selenium.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared utilities for container-based integration tests.
 */
final class ContainerTestUtils {

    /**
     * Lazily built Docker image that is shared across <em>all</em> container
     * integration tests. The image is built exactly once (the first time any
     * test needs it) and then reused, avoiding the creation of many identical
     * Docker images that waste disk space.
     * <p>
     * The deterministic image name {@code taxonomy-app-it:latest} lets Docker
     * recognise a cache hit even across JVM restarts as long as the underlying
     * app JAR has not changed.
     */
    private static final Future<String> SHARED_IMAGE = new ImageFromDockerfile(
            "taxonomy-app-it", false)
            .withFileFromPath("app.jar", findApplicationJar())
            .withDockerfileFromBuilder(builder -> builder
                    .from("eclipse-temurin:21-jre")
                    .workDir("/app")
                    .copy("app.jar", "app.jar")
                    .expose(8080)
                    .entryPoint("java", "-jar", "app.jar")
                    .build());

    private ContainerTestUtils() {
    }

    /**
     * Returns the shared Docker image future that all container tests should
     * use. The image is built at most once.
     */
    static Future<String> sharedImage() {
        return SHARED_IMAGE;
    }

    /**
     * Resolves the Spring Boot fat JAR in the {@code target/} directory.
     * <p>
     * When run via Maven Failsafe, the {@code project.build.finalName} system
     * property is set (e.g. {@code taxonomy-app-1.1.3-SNAPSHOT}), so the JAR
     * name is constructed deterministically as {@code <finalName>.jar}.
     * <p>
     * When launched from an IDE (system property absent), a regex scan of
     * {@code target/} and {@code taxonomy-app/target/} is used as a fallback,
     * excluding {@code -original}, {@code -sources}, and {@code -javadoc} JARs
     * and preferring the largest remaining match.
     */
    static Path findApplicationJar() {
        Path moduleTarget = Path.of("target");
        Path repoRootTarget = Path.of("taxonomy-app", "target");

        // --- Deterministic path via Maven system property (set by Failsafe) ---
        String finalName = System.getProperty("project.build.finalName");
        if (finalName != null) {
            String jarName = finalName + ".jar";
            for (Path targetDir : new Path[]{moduleTarget, repoRootTarget}) {
                Path candidate = targetDir.resolve(jarName);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "Expected JAR '" + jarName + "' not found in " + moduleTarget + "/ or "
                            + repoRootTarget + "/. Run 'mvn package -DskipTests' first.");
        }

        // --- Fallback for IDE runs: scan target/ directories ---
        for (Path targetDir : new Path[]{moduleTarget, repoRootTarget}) {
            if (!Files.isDirectory(targetDir)) {
                continue;
            }
            try (var stream = Files.list(targetDir)) {
                var jar = stream
                        .filter(p -> p.getFileName().toString().matches("taxonomy-app-.*\\.jar"))
                        .filter(p -> !p.getFileName().toString().contains("original"))
                        .filter(p -> !p.getFileName().toString().contains("sources"))
                        .filter(p -> !p.getFileName().toString().contains("javadoc"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length()));
                if (jar.isPresent()) {
                    return jar.get();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to scan " + targetDir + "/ directory for application JAR", e);
            }
        }
        throw new IllegalStateException(
                "No taxonomy-app-*.jar found in " + moduleTarget + "/ or " + repoRootTarget
                        + "/. Run 'mvn package -DskipTests' first.");
    }

    // ── Docker image constants ──────────────────────────────────────────────

    static final String POSTGRES_IMAGE = "postgres:16-alpine";
    static final String ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart";
    static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-CU18-ubuntu-22.04";
    private static final String SELENIUM_IMAGE_PROPERTY = "selenium.container.image";

    /** Strong password required by SQL Server's complexity rules. */
    static final String MSSQL_PASSWORD = "A_Str0ng_Required_Password";

    // ── Selenium container factory ───────────────────────────────────────────────

    /**
     * Starts a Selenium browser container and creates its matching remote driver.
     * The caller owns the returned session and must close it before closing the
     * Docker network.
     */
    static BrowserSession startBrowser(Network network) {
        BrowserWebDriverContainer container = new BrowserWebDriverContainer(seleniumImage())
                .withNetwork(network);
        container.start();
        try {
            RemoteWebDriver driver = new RemoteWebDriver(container.getSeleniumAddress(), chromeOptions());
            return new BrowserSession(container, driver);
        } catch (RuntimeException e) {
            try {
                container.stop();
            } catch (RuntimeException stopFailure) {
                e.addSuppressed(stopFailure);
            }
            throw e;
        }
    }

    /**
     * Resolves the configured image and rejects a client/image version mismatch.
     * Failsafe supplies the pinned image from the root POM; the fallback keeps IDE
     * runs convenient while still using the Selenium client version on the classpath.
     */
    static DockerImageName seleniumImage() {
        String seleniumVersion = new BuildInfo().getReleaseLabel();
        String configuredImage = System.getProperty(
                SELENIUM_IMAGE_PROPERTY, "selenium/standalone-chrome:" + seleniumVersion);
        DockerImageName image = DockerImageName.parse(configuredImage);
        String imageTag = image.getVersionPart();
        if (!imageTag.equals(seleniumVersion) && !imageTag.startsWith(seleniumVersion + "-")) {
            throw new IllegalStateException("Selenium client " + seleniumVersion
                    + " does not match browser image " + configuredImage);
        }
        return image;
    }

    private static ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        // Chrome 145+ aggressively upgrades http:// to https:// (HTTPS-First Mode).
        // The application container intentionally serves plain HTTP on its private
        // test network, so disable the upgrade variants and trust that test origin.
        options.addArguments(
                "--disable-features=HttpsUpgrades,HttpsFirstMode,HttpsFirstModeV2,"
                        + "HttpsFirstBalancedMode,HttpsFirstBalancedModeAutoEnable,"
                        + "HttpsFirstModeForTypedNavigations,"
                        + "HttpsFirstModeInterstitial",
                "--unsafely-treat-insecure-origin-as-secure=http://app:8080",
                "--ignore-certificate-errors",
                "--allow-running-insecure-content");
        options.setAcceptInsecureCerts(true);
        return options;
    }

    /** Owns both sides of a Selenium session and closes the driver before its container. */
    record BrowserSession(BrowserWebDriverContainer container, RemoteWebDriver driver)
            implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                driver.quit();
            } catch (RuntimeException e) {
                failure = e;
            }
            try {
                container.stop();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Closes every resource in order, even if an earlier cleanup fails, then
     * rethrows the first failure with any later failures attached.
     */
    static void closeAll(AutoCloseable... resources) throws Exception {
        Exception failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ── Application container factories ──────────────────────────────────────

    /**
     * Creates a standalone application container (no Docker network).
     * Suitable for HSQLDB-backed tests that need no external database.
     *
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> appContainer() {
        return new GenericContainer<>(SHARED_IMAGE)
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofSeconds(120))
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .forPort(8080));
    }

    /**
     * Creates a pre-configured application container on the given Docker network.
     * The container is built from the Spring Boot fat JAR and exposes port 8080.
     * Callers can add additional env vars (e.g. database configuration) before
     * the container is started.
     *
     * @param network the Docker network to attach the container to
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> appContainer(Network network) {
        return new GenericContainer<>(SHARED_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("app")
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofSeconds(180))
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .forPort(8080));
    }

    /**
     * Creates an application container pre-configured with database connection
     * environment variables. This eliminates the repetitive 5-env-var block
     * that every database-backed container test otherwise needs.
     *
     * @param network  the Docker network to attach the container to
     * @param profile  Spring profile name (e.g. {@code "postgres"}, {@code "oracle"}, {@code "mssql"})
     * @param jdbcUrl  JDBC URL using the Docker network alias (e.g. {@code "jdbc:postgresql://db:5432/taxonomy"})
     * @param username database username
     * @param password database password
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> appContainer(Network network, String profile,
                                            String jdbcUrl, String username, String password) {
        return appContainer(network)
                .withEnv("SPRING_PROFILES_ACTIVE", profile)
                .withEnv("TAXONOMY_DATASOURCE_URL", jdbcUrl)
                .withEnv("SPRING_DATASOURCE_USERNAME", username)
                .withEnv("SPRING_DATASOURCE_PASSWORD", password)
                .withEnv("TAXONOMY_DDL_AUTO", "create");
    }

    // ── Database container factories ─────────────────────────────────────────

    /**
     * Creates a PostgreSQL container with the standard test credentials.
     *
     * @param network the Docker network to attach the container to
     * @return a configured but <em>not yet started</em> {@link PostgreSQLContainer}
     */
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer postgresContainer(Network network) {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("taxonomy")
                .withUsername("taxonomy")
                .withPassword("taxonomy");
    }

    /**
     * Creates an Oracle Database Free container with the standard test credentials.
     *
     * @param network the Docker network to attach the container to
     * @return a configured but <em>not yet started</em> {@link OracleContainer}
     */
    static OracleContainer oracleContainer(Network network) {
        return new OracleContainer(ORACLE_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("taxonomy")
                .withUsername("taxonomy")
                .withPassword("taxonomy");
    }

    /**
     * Creates a Microsoft SQL Server container with the standard test credentials.
     *
     * @param network the Docker network to attach the container to
     * @return a configured but <em>not yet started</em> {@link MSSQLServerContainer}
     */
    @SuppressWarnings({"resource", "rawtypes"})
    static MSSQLServerContainer mssqlContainer(Network network) {
        return new MSSQLServerContainer(MSSQL_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withPassword(MSSQL_PASSWORD)
                .acceptLicense();
    }

    // ── Pre-configured app + DB shortcuts ────────────────────────────────────

    /**
     * Creates an application container pre-configured for PostgreSQL.
     *
     * @param network the Docker network (must also host the Postgres container)
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> postgresAppContainer(Network network) {
        return appContainer(network, "postgres",
                "jdbc:postgresql://db:5432/taxonomy", "taxonomy", "taxonomy");
    }

    /**
     * Creates an application container pre-configured for Oracle.
     *
     * @param network the Docker network (must also host the Oracle container)
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> oracleAppContainer(Network network) {
        return appContainer(network, "oracle",
                "jdbc:oracle:thin:@db:1521/taxonomy", "taxonomy", "taxonomy")
                .withStartupTimeout(Duration.ofSeconds(300));
    }

    /**
     * Creates an application container pre-configured for SQL Server.
     *
     * @param network the Docker network (must also host the MSSQL container)
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> mssqlAppContainer(Network network) {
        return appContainer(network, "mssql",
                "jdbc:sqlserver://db:1433;databaseName=master;encrypt=false;trustServerCertificate=true;loginTimeout=30",
                "sa", MSSQL_PASSWORD);
    }
}
