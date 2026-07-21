package com.smartroute.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartroute.config.AppConfig;
import com.smartroute.model.Intent;
import com.smartroute.model.IntentResult;
import com.smartroute.model.IntentSource;
import com.smartroute.model.Sentiment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Intent + sentiment detection.
 *
 * Primary path: GPT-4o, forced into strict JSON output.
 * Fallback path: keyword matching, used when
 *   - there is no OPENAI_API_KEY configured,
 *   - the OpenAI call fails/times out, or
 *   - RoutingEngine decides the LLM's confidence was too low.
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private static final String SYSTEM_PROMPT = """
            You are an intent classifier for a contact center router.
            Classify the customer message and respond ONLY with a JSON object of the exact shape:
            {"intent": "billing"|"technical"|"urgent"|"general", "sentiment": "positive"|"neutral"|"negative", "confidence": <float 0.0-1.0>}

            Guidance:
            - "urgent": outages, account lockouts, safety issues, anything the customer frames as time-critical.
            - "billing": invoices, charges, refunds, payment methods, subscription cost.
            - "technical": product not working, errors, bugs, setup/configuration help.
            - "general": anything else, including small talk and simple questions.
            - confidence should reflect how certain you are, not just be a constant value.
            """;

    private static final String AUTO_RESOLVE_SYSTEM_PROMPT = """
            You are a concise, friendly customer support agent. Answer the customer's \
            question directly in 2-3 sentences. If you cannot resolve it without human \
            help, say so plainly.""";

    // Keyword fallback tables, checked in this priority order (urgent first).
    private static final List<Intent> CHECK_ORDER = List.of(Intent.URGENT, Intent.BILLING, Intent.TECHNICAL);

    private static final Map<Intent, List<String>> KEYWORDS = Map.of(
            Intent.URGENT, List.of(
                    "urgent", "emergency", "asap", "immediately", "right now", "outage",
                    "down", "locked out", "can't access", "cannot access", "critical"),
            Intent.BILLING, List.of(
                    "bill", "invoice", "charge", "charged", "refund", "payment",
                    "subscription", "price", "cost", "overcharge", "credit card"),
            Intent.TECHNICAL, List.of(
                    "error", "bug", "crash", "broken", "not working", "doesn't work",
                    "issue", "install", "setup", "configure", "failed", "glitch"));

    private static final List<String> NEGATIVE_WORDS = List.of(
            "angry", "furious", "terrible", "awful", "worst", "hate", "frustrated",
            "unacceptable", "disappointed", "scam", "ridiculous", "horrible");

    private static final List<String> POSITIVE_WORDS = List.of(
            "thanks", "thank you", "great", "awesome", "love", "appreciate", "good job");

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public IntentService(AppConfig config, ObjectMapper objectMapper, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl("https://api.openai.com/v1").build();
    }

    /** Coarse heuristic used when the LLM path is unavailable or untrustworthy. */
    public IntentResult keywordFallback(String message) {
        String text = message.toLowerCase();

        Intent intent = Intent.GENERAL;
        for (Intent candidate : CHECK_ORDER) {
            if (KEYWORDS.get(candidate).stream().anyMatch(text::contains)) {
                intent = candidate;
                break;
            }
        }

        Sentiment sentiment;
        if (NEGATIVE_WORDS.stream().anyMatch(text::contains)) {
            sentiment = Sentiment.NEGATIVE;
        } else if (POSITIVE_WORDS.stream().anyMatch(text::contains)) {
            sentiment = Sentiment.POSITIVE;
        } else {
            sentiment = Sentiment.NEUTRAL;
        }

        // Keyword matching is a coarse heuristic, so we cap its own reported
        // confidence well below the LLM path -- it should win a decision on
        // correctness (e.g. "urgent"), never on confidence.
        double confidence = intent != Intent.GENERAL ? 0.6 : 0.4;

        return new IntentResult(intent, sentiment, confidence, IntentSource.KEYWORD_FALLBACK);
    }

    public Mono<IntentResult> detectIntent(String message) {
        if (config.openaiApiKey() == null || config.openaiApiKey().isBlank()) {
            log.info("No OPENAI_API_KEY configured; using keyword fallback");
            return Mono.just(keywordFallback(message));
        }

        Map<String, Object> body = Map.of(
                "model", config.openaiModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", message)),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(config.openaiApiKey()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::parseIntentResponse)
                // Deliberately broad: json_object mode guarantees valid JSON syntax
                // but not our schema, so the model can still return a missing key,
                // an unexpected enum value, or a non-numeric confidence. Any of
                // those -- plus network/timeout/HTTP errors -- should degrade to
                // keyword matching rather than surfacing as a 500.
                .onErrorResume(exc -> {
                    log.warn("GPT-4o intent detection failed ({}); falling back to keywords", exc.toString());
                    return Mono.just(keywordFallback(message));
                });
    }

    private IntentResult parseIntentResponse(JsonNode response) {
        String raw = response.path("choices").get(0).path("message").path("content").asText();
        JsonNode data;
        try {
            data = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Model response was not valid JSON: " + raw, e);
        }

        if (!data.hasNonNull("intent") || !data.hasNonNull("sentiment") || !data.hasNonNull("confidence")) {
            throw new IllegalStateException("Model response missing required field(s): " + raw);
        }

        Intent intent = Intent.fromValue(data.get("intent").asText());
        Sentiment sentiment = Sentiment.fromValue(data.get("sentiment").asText());
        double confidence = data.get("confidence").asDouble();

        return new IntentResult(intent, sentiment, confidence, IntentSource.LLM);
    }

    /** Used only for the AUTO_RESOLVE path: ask GPT-4o to answer directly. */
    public Mono<String> generateAutoResolution(String message) {
        if (config.openaiApiKey() == null || config.openaiApiKey().isBlank()) {
            return Mono.just("Thanks for reaching out! A general support answer would go here (no OPENAI_API_KEY configured).");
        }

        Map<String, Object> body = Map.of(
                "model", config.openaiModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", AUTO_RESOLVE_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", message)),
                "temperature", 0.3);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(config.openaiApiKey()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .map(response -> response.path("choices").get(0).path("message").path("content").asText().strip())
                .onErrorResume(exc -> {
                    log.warn("GPT-4o auto-resolution failed ({})", exc.toString());
                    return Mono.just("Sorry, I couldn't generate an automated answer right now. Routing to a human agent.");
                });
    }
}
