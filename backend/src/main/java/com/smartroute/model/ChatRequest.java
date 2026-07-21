package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatRequest(String message, @JsonProperty("customer_id") String customerId) {
}
