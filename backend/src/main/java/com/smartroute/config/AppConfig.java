package com.smartroute.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central configuration, bound from environment variables via application.yml
 * placeholders (see resources/application.yml) -- the same env var names
 * that gcp/secret-manager-setup.sh and cloudbuild.yaml already provision.
 */
@ConfigurationProperties(prefix = "smartroute")
public record AppConfig(
        String openaiApiKey,
        String openaiModel,
        String mongoUri,
        String mongoDbName,
        double confidenceThreshold,
        List<String> corsOrigins) {
}
