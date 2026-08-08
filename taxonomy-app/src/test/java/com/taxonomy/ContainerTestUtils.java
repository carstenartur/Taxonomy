package com.taxonomy;

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Future;

/** Shared utilities for container-based integration tests. */
final class ContainerTestUtils {

    /**
     * Deterministic credential used only inside isolated disposable test
     * containers. Product code has no corresponding default.
     */
    static final String TEST_ADMIN_PASSWORD = "admin";

    /** Same immutable runtime manifest used by the production Dockerfile. */
    private static final String APP_RUNTIME_IMAGE =
            "eclipse-temurin@sha256:"
                    + "d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13";

    private static final Future<String> SHARED_IMAGE = new ImageFromDockerfile(
            "taxonomy-app-it", false)
            .withFileFromPath("app.jar", findApplicationJar())
            .withDockerfileFromBuilder(builder -> builder
                    .from(APP_RUNTIME_IMAGE)
                    .workDir("/app")
                    .copy("app.jar", "app.jar")
                    .expose(8080)
                    .entryPoint("java", "-jar", "app.jar")
                    .build());

    static final String POSTGRES_IMAGE = "postgres:16-alpine";
    static final String ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart";
    static final String MSSQL_IMAGE =
            "mcr.microsoft.com/mssql/server:2022-CU18-ubuntu-22.04";
    static final String APP_NETWORK_ALIAS = "taxonomy.test";
    static final String APP_ORIGIN = "http://" + APP_NETWORK_ALIAS + ":8080";
    static final String MSSQL_PASSWORD = "A_Str0ng_Required_Password";

    private static final String SELENIUM_IMAGE_PROPERTY = "selenium.container.image";

    private ContainerTestUtils() {
    }

    static Future<String> sharedImage() {
        return SHARED_IMAGE;
    }

    static Path findApplicationJar() {
        Path moduleTarget = Path.of("target");
        Path repositoryTarget = Path.of("taxonomy-app", "target");

        String finalName = System.getProperty("project.build.finalName");
        if (finalName != null) {
            String jarName = finalName + ".jar";
            for (Path targetDirectory : new Path[]{moduleTarget, repositoryTarget}) {
                Path candidate = targetDirectory.resolve(jarName);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "Expected JAR '" + jarName + "' not found in "
                            + moduleTarget + "/ or " + repositoryTarget
                            + "/. Run 'mvn package -DskipTests' first.");
        }

        for (Path targetDirectory : new Path[]{moduleTarget, repositoryTarget}) {
            if (!Files.isDirectory(targetDirectory)) {
                continue;
            }
            try (var stream = Files.list(targetDirectory)) {
                var jar = stream
                        .filter(path -> path.getFileName().toString()
                                .matches("taxonomy-app-.*\\.jar"))
                        .filter(path -> !path.getFileName().toString()
                                .contains("original"))
                        .filter(path -> !path.getFileName().toString()
                                .contains("sources"))
                        .filter(path -> !path.getFileName().toString()
                                .contains("javadoc"))
                        .max(java.util.Comparator.comparingLong(
                                path -> path.toFile().length()));
                if (jar.isPresent()) {
                    return jar.get();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(
                        "Failed to scan " + targetDirectory
                                + "/ for the application JAR",
                        exception);
            }
        }
        throw new IllegalStateException(
                "No taxonomy-app-*.jar found in " + moduleTarget
                        + "/ or " + repositoryTarget
                        + "/. Run 'mvn package -DskipTests' first.");
    }

    static BrowserSession startBrowser(Network network) {
        BrowserWebDriverContainer container = new BrowserWebDriverContainer(seleniumImage())
                .withNetwork(network);
        container.start();
        try {
            RemoteWebDriver driver = new RemoteWebDriver(
                    container.getSeleniumAddress(), chromeOptions());
            return new BrowserSession(container, driver);
        } catch (RuntimeException exception) {
            try {
                container.stop();
            } catch (RuntimeException stopFailure) {
                exception.addSuppressed(stopFailure);
            }
            throw exception;
        }
    }

    static DockerImageName seleniumImage() {
        String seleniumVersion = new BuildInfo().getReleaseLabel();
        String configuredImage = System.getProperty(
                SELENIUM_IMAGE_PROPERTY,
                "selenium/standalone-chrome:" + seleniumVersion);
        DockerImageName image = DockerImageName.parse(configuredImage);
        String imageTag = image.getVersionPart();
        if (!imageTag.equals(seleniumVersion)
                && !imageTag.startsWith(seleniumVersion + "-")) {
            throw new IllegalStateException(
                    "Selenium client " + seleniumVersion
                            + " does not match browser image " + configuredImage);
        }
        return image;
    }

    private static ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--unsafely-treat-insecure-origin-as-secure=" + APP_ORIGIN);
        return options;
    }

    record BrowserSession(BrowserWebDriverContainer container,
                          RemoteWebDriver driver) implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                driver.quit();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                container.stop();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static void closeAll(AutoCloseable... resources) throws Exception {
        Exception failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    static GenericContainer<?> appContainer() {
        return configureApplicationContainer(new GenericContainer<>(SHARED_IMAGE))
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofSeconds(120))
                .waitingFor(Wait.forHttp("/actuator/health/readiness")
                        .forStatusCode(200)
                        .forPort(8080));
    }

    static GenericContainer<?> appContainer(Network network) {
        return configureApplicationContainer(new GenericContainer<>(SHARED_IMAGE))
                .withNetwork(network)
                .withNetworkAliases(APP_NETWORK_ALIAS)
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofSeconds(180))
                .waitingFor(Wait.forHttp("/actuator/health/readiness")
                        .forStatusCode(200)
                        .forPort(8080));
    }

    private static GenericContainer<?> configureApplicationContainer(
            GenericContainer<?> container) {
        return container
                .withEnv("TAXONOMY_ADMIN_PASSWORD", TEST_ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false");
    }

    static GenericContainer<?> appContainer(Network network,
                                             String profile,
                                             String jdbcUrl,
                                             String username,
                                             String password) {
        return appContainer(network)
                .withEnv("SPRING_PROFILES_ACTIVE", profile)
                .withEnv("TAXONOMY_DATASOURCE_URL", jdbcUrl)
                .withEnv("SPRING_DATASOURCE_USERNAME", username)
                .withEnv("SPRING_DATASOURCE_PASSWORD", password)
                .withEnv("TAXONOMY_DDL_AUTO", "create");
    }

    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer postgresContainer(Network network) {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("taxonomy")
                .withUsername("taxonomy")
                .withPassword("taxonomy");
    }

    static OracleContainer oracleContainer(Network network) {
        return new OracleContainer(ORACLE_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("taxonomy")
                .withUsername("taxonomy")
                .withPassword("taxonomy");
    }

    @SuppressWarnings({"resource", "rawtypes"})
    static MSSQLServerContainer mssqlContainer(Network network) {
        return new MSSQLServerContainer(MSSQL_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withPassword(MSSQL_PASSWORD)
                .acceptLicense();
    }

    static GenericContainer<?> postgresAppContainer(Network network) {
        return appContainer(
                network,
                "postgres",
                "jdbc:postgresql://db:5432/taxonomy",
                "taxonomy",
                "taxonomy");
    }

    static GenericContainer<?> oracleAppContainer(Network network) {
        return appContainer(
                network,
                "oracle",
                "jdbc:oracle:thin:@db:1521/taxonomy",
                "taxonomy",
                "taxonomy")
                .withStartupTimeout(Duration.ofSeconds(300));
    }

    static GenericContainer<?> mssqlAppContainer(Network network) {
        return appContainer(
                network,
                "mssql",
                "jdbc:sqlserver://db:1433;databaseName=master;encrypt=false;"
                        + "trustServerCertificate=true;loginTimeout=30",
                "sa",
                MSSQL_PASSWORD);
    }
}
