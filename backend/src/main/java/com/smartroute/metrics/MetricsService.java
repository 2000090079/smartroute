package com.smartroute.metrics;

import com.smartroute.model.Decision;
import com.smartroute.model.Intent;
import com.smartroute.model.IntentSource;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Prometheus-format metrics for SmartRoute.
 *
 * Exposed at GET /metrics in standard Prometheus text exposition format.
 * The dashboard (GET /api/stats) reads pre-aggregated numbers from the
 * audit store instead, since a browser dashboard wants JSON percentages,
 * not a text scrape format.
 */
@Service
public class MetricsService {

    /** Matches prometheus_client.CONTENT_TYPE_LATEST exactly. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final double[] CONFIDENCE_BUCKETS = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    public void record(Decision decision, Intent intent, IntentSource source, double confidence) {
        registry.counter("smartroute_messages_total", "decision", decision.value()).increment();
        registry.counter("smartroute_intent_total", "intent", intent.value()).increment();
        registry.counter("smartroute_intent_source_total", "source", source.value()).increment();

        DistributionSummary.builder("smartroute_confidence_score")
                .description("Distribution of intent-detection confidence scores.")
                .serviceLevelObjectives(CONFIDENCE_BUCKETS)
                .register(registry)
                .record(confidence);
    }

    public String renderLatest() {
        return registry.scrape();
    }
}
