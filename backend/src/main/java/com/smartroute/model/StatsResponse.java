package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record StatsResponse(
        @JsonProperty("total_messages") int totalMessages,
        @JsonProperty("auto_resolve_rate") double autoResolveRate,
        @JsonProperty("escalation_rate") double escalationRate,
        @JsonProperty("messages_per_queue") Map<String, Integer> messagesPerQueue,
        @JsonProperty("avg_confidence") double avgConfidence,
        List<RoutingResult> recent) {
}
