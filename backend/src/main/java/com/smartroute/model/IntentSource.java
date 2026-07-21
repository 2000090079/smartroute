package com.smartroute.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum IntentSource {
    LLM("llm"),
    KEYWORD_FALLBACK("keyword_fallback");

    private final String value;

    IntentSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static IntentSource fromValue(String value) {
        for (IntentSource source : values()) {
            if (source.value.equalsIgnoreCase(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown intent source: " + value);
    }
}
