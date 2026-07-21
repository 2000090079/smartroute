package com.smartroute.audit;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.smartroute.config.AppConfig;
import com.smartroute.model.RoutingResult;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Audit log persistence.
 *
 * Backed by MongoDB (reactive driver) as a local stand-in for the DynamoDB
 * table this would use in production -- same access pattern (append-only
 * writes, reverse-chronological reads by a single partition), swappable
 * driver.
 *
 * If Mongo isn't reachable at startup, we transparently fall back to an
 * in-process list so the app still runs. This is a local dev convenience
 * only: it is not persisted and not safe across multiple instances, which
 * is exactly why it's a *fallback* and logs loudly.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AppConfig config;
    private final List<RoutingResult> memoryStore = new CopyOnWriteArrayList<>();
    private volatile boolean usingMemoryFallback = false;
    private volatile ReactiveMongoTemplate mongoTemplate;

    public AuditService(AppConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void initStore() {
        try {
            MongoClient client = MongoClients.create(config.mongoUri());
            ReactiveMongoTemplate template = new ReactiveMongoTemplate(client, config.mongoDbName());

            template.executeCommand("{ ping: 1 }")
                    .then(template.indexOps(RoutingResult.class)
                            .ensureIndex(new Index().on("timestamp", Sort.Direction.DESC)))
                    .timeout(Duration.ofMillis(1500))
                    .block();

            this.mongoTemplate = template;
            this.usingMemoryFallback = false;
            log.info("Connected to MongoDB at {} (db={})", config.mongoUri(), config.mongoDbName());
        } catch (Exception exc) {
            log.warn(
                    "MongoDB unavailable ({}). Falling back to an in-memory audit store -- "
                            + "fine for a local demo, but entries won't survive a restart and "
                            + "won't be shared across instances.",
                    exc.toString());
            this.mongoTemplate = null;
            this.usingMemoryFallback = true;
        }
    }

    public boolean isUsingMemoryFallback() {
        return usingMemoryFallback;
    }

    public Mono<RoutingResult> append(RoutingResult result) {
        if (mongoTemplate != null) {
            return mongoTemplate.insert(result);
        }
        memoryStore.add(result);
        return Mono.just(result);
    }

    public Flux<RoutingResult> recent(int limit) {
        if (mongoTemplate != null) {
            Query query = new Query().with(Sort.by(Sort.Direction.DESC, "timestamp")).limit(limit);
            return mongoTemplate.find(query, RoutingResult.class);
        }
        return Flux.fromIterable(memoryStore)
                .sort(Comparator.comparing(RoutingResult::timestamp).reversed())
                .take(limit);
    }

    public Flux<RoutingResult> allEntries() {
        if (mongoTemplate != null) {
            return mongoTemplate.findAll(RoutingResult.class);
        }
        return Flux.fromIterable(memoryStore);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static Instant now() {
        return Instant.now();
    }
}
