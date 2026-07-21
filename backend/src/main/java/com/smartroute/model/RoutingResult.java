package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "audit_log")
public record RoutingResult(
        @Id String id,
        String message,
        Intent intent,
        Sentiment sentiment,
        double confidence,
        @JsonProperty("intent_source") @Field("intent_source") IntentSource intentSource,
        Decision decision,
        String resolution,
        Instant timestamp) {
}
