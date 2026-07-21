package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Intent {
    BILLING("billing"),
    TECHNICAL("technical"),
    URGENT("urgent"),
    GENERAL("general");

    private final String value;

    Intent(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Intent fromValue(String value) {
        for (Intent intent : values()) {
            if (intent.value.equalsIgnoreCase(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("Unknown intent: " + value);
    }
}
