package com.smartroute;

import com.smartroute.config.AppConfig;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoReactiveDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

// Mongo autoconfiguration is excluded because AuditService manages its own
// MongoClient/ReactiveMongoTemplate lifecycle (connect-with-timeout, then
// fall back to an in-memory store on failure) instead of using Spring
// Boot's eagerly-configured, always-on Mongo beans.
@SpringBootApplication(exclude = {MongoReactiveAutoConfiguration.class, MongoReactiveDataAutoConfiguration.class})
@ConfigurationPropertiesScan
public class SmartRouteApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartRouteApplication.class, args);
    }

    @Bean
    public CorsWebFilter corsWebFilter(AppConfig config) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(config.corsOrigins());
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
