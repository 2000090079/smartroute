package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Decision {
    ESCALATE("ESCALATE"),
    BILLING_QUEUE("BILLING_QUEUE"),
    TECH_QUEUE("TECH_QUEUE"),
    AUTO_RESOLVE("AUTO_RESOLVE");

    private final String value;

    Decision(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Decision fromValue(String value) {
        for (Decision decision : values()) {
            if (decision.value.equalsIgnoreCase(value)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("Unknown decision: " + value);
    }
}
