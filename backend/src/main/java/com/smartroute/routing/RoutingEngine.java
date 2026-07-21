package com.smartroute.routing;

import com.smartroute.config.AppConfig;
import com.smartroute.intent.IntentService;
import com.smartroute.model.Decision;
import com.smartroute.model.IntentResult;
import com.smartroute.model.IntentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routing decision engine.
 *
 * Pure and deliberately dumb: given an IntentResult it returns a Decision.
 * All the "intelligence" lives in IntentService; this class just encodes
 * the business rules so they're easy to audit and unit test.
 */
@Component
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);

    private final AppConfig config;
    private final IntentService intentService;

    public RoutingEngine(AppConfig config, IntentService intentService) {
        this.config = config;
        this.intentService = intentService;
    }

    /** Apply the confidence-gate rule: below threshold, trust keywords instead. */
    public IntentResult resolveIntent(String message, IntentResult result) {
        if (result.confidence() < config.confidenceThreshold() && result.source() == IntentSource.LLM) {
            IntentResult fallback = intentService.keywordFallback(message);
            log.info(
                    "LLM confidence {} < {} threshold; overriding intent={} with keyword intent={}",
                    result.confidence(), config.confidenceThreshold(), result.intent(), fallback.intent());
            return fallback;
        }
        return result;
    }

    public Decision decide(IntentResult result) {
        return switch (result.intent()) {
            // Escalate regardless of sentiment: "urgent" (outage, lockout,
            // safety) is a strong enough signal on its own that it shouldn't
            // hinge on the sentiment classifier also agreeing.
            case URGENT -> Decision.ESCALATE;
            case BILLING -> Decision.BILLING_QUEUE;
            case TECHNICAL -> Decision.TECH_QUEUE;
            case GENERAL -> Decision.AUTO_RESOLVE;
        };
    }
}
