package com.smartroute.model;

public record IntentResult(Intent intent, Sentiment sentiment, double confidence, IntentSource source) {
}
