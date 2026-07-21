package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Sentiment {
    POSITIVE("positive"),
    NEUTRAL("neutral"),
    NEGATIVE("negative");

    private final String value;

    Sentiment(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Sentiment fromValue(String value) {
        for (Sentiment sentiment : values()) {
            if (sentiment.value.equalsIgnoreCase(value)) {
                return sentiment;
            }
        }
        throw new IllegalArgumentException("Unknown sentiment: " + value);
    }
}
