package com.smartroute.web;

import com.smartroute.audit.AuditService;
import com.smartroute.intent.IntentService;
import com.smartroute.metrics.MetricsService;
import com.smartroute.model.ChatRequest;
import com.smartroute.model.Decision;
import com.smartroute.model.RoutingResult;
import com.smartroute.model.StatsResponse;
import com.smartroute.routing.RoutingEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * SmartRoute REST API.
 *
 * Endpoints:
 *   POST /api/chat        submit a customer message, get back the routing decision
 *   GET  /api/audit-log   recent audit entries (for the AuditLog view)
 *   GET  /api/stats       aggregated dashboard numbers (for the Dashboard view)
 *   GET  /metrics         Prometheus scrape endpoint
 *   GET  /health          liveness/readiness probe
 */
@RestController
public class SmartRouteController {

    private final AuditService auditService;
    private final IntentService intentService;
    private final RoutingEngine routingEngine;
    private final MetricsService metricsService;

    public SmartRouteController(
            AuditService auditService,
            IntentService intentService,
            RoutingEngine routingEngine,
            MetricsService metricsService) {
        this.auditService = auditService;
        this.intentService = intentService;
        this.routingEngine = routingEngine;
        this.metricsService = metricsService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("using_memory_fallback", auditService.isUsingMemoryFallback());
        return body;
    }

    @PostMapping("/api/chat")
    public Mono<RoutingResult> chat(@RequestBody ChatRequest req) {
        String rawMessage = req.message();
        if (rawMessage == null || rawMessage.isEmpty() || rawMessage.length() > 4000) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "message must be between 1 and 4000 characters");
        }
        String message = rawMessage.strip();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "message must not be empty");
        }

        return intentService.detectIntent(message)
                .map(raw -> routingEngine.resolveIntent(message, raw))
                .flatMap(result -> {
                    Decision decision = routingEngine.decide(result);
                    Mono<String> resolutionMono = decision == Decision.AUTO_RESOLVE
                            ? intentService.generateAutoResolution(message)
                            : Mono.empty();

                    return resolutionMono
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(resolutionOpt -> {
                                RoutingResult routingResult = new RoutingResult(
                                        AuditService.newId(),
                                        message,
                                        result.intent(),
                                        result.sentiment(),
                                        result.confidence(),
                                        result.source(),
                                        decision,
                                        resolutionOpt.orElse(null),
                                        AuditService.now());

                                return auditService
                                        .append(routingResult)
                                        .doOnNext(saved -> metricsService.record(
                                                decision, result.intent(), result.source(), result.confidence()));
                            });
                });
    }

    @GetMapping("/api/audit-log")
    public Mono<List<RoutingResult>> auditLog(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "limit must be between 1 and 500");
        }
        return auditService.recent(limit).collectList();
    }

    @GetMapping("/api/stats")
    public Mono<StatsResponse> stats() {
        return auditService.allEntries().collectList().map(entries -> {
            int total = entries.size();
            if (total == 0) {
                return new StatsResponse(0, 0.0, 0.0, Map.of(), 0.0, List.of());
            }

            Map<String, Integer> queueCounts = new LinkedHashMap<>();
            double confidenceSum = 0;
            for (RoutingResult e : entries) {
                queueCounts.merge(e.decision().value(), 1, Integer::sum);
                confidenceSum += e.confidence();
            }

            int autoResolved = queueCounts.getOrDefault(Decision.AUTO_RESOLVE.value(), 0);
            int escalated = queueCounts.getOrDefault(Decision.ESCALATE.value(), 0);

            double autoResolveRate = Math.round((100.0 * autoResolved / total) * 10) / 10.0;
            double escalationRate = Math.round((100.0 * escalated / total) * 10) / 10.0;
            double avgConfidence = Math.round((confidenceSum / total) * 1000) / 1000.0;

            List<RoutingResult> recentSorted = entries.stream()
                    .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                    .limit(20)
                    .toList();

            return new StatsResponse(total, autoResolveRate, escalationRate, queueCounts, avgConfidence, recentSorted);
        });
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok().header("Content-Type", MetricsService.CONTENT_TYPE).body(metricsService.renderLatest());
    }
}
