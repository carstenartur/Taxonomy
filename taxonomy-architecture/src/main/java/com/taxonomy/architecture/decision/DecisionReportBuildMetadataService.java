package com.taxonomy.architecture.decision;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

/**
 * Resolves the exact application version and source commit embedded in generated reports.
 *
 * <p>Packaged builds use Spring Boot's generated {@link BuildProperties} and
 * {@link GitProperties}. Development and narrowly scoped tests fall back to the
 * resource-filtered application version and the optional git-commit property.</p>
 */
@Service
public class DecisionReportBuildMetadataService {

    private final String fallbackVersion;
    private final String fallbackCommit;

    @Autowired(required = false)
    private BuildProperties buildProperties;

    @Autowired(required = false)
    private GitProperties gitProperties;

    public DecisionReportBuildMetadataService(
            @Value("${app.display-version:${info.app.version:unknown}}") String fallbackVersion,
            @Value("${git.commit.id:unknown}") String fallbackCommit) {
        this.fallbackVersion = normalized(fallbackVersion, "unknown");
        this.fallbackCommit = normalized(fallbackCommit, "unknown");
    }

    public BuildMetadata current() {
        String version = buildProperties != null
                ? normalized(buildProperties.getVersion(), fallbackVersion)
                : fallbackVersion;
        String commit = gitProperties != null
                ? normalized(gitProperties.getCommitId(), fallbackCommit)
                : fallbackCommit;
        return new BuildMetadata(version, commit);
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public record BuildMetadata(String version, String commit) {
    }
}
