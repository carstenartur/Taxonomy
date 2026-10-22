package com.taxonomy.workspace.signing;

import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitSigningProperties.class)
public class GitSigningConfiguration {
    @Bean
    GitCommitSignatureService gitCommitSignatureService(GitSigningProperties properties) throws IOException {
        return new GitCommitSignatureService(properties);
    }
}
