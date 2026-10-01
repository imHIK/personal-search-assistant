package io.personalassistant.agent.llm;

import io.personalassistant.domain.model.Connection;
import java.util.Map;
import java.util.Optional;

/** The shape of an {@code LLM} connection: {@code auth.apiKey}, and the endpoint and model in config. */
public final class LlmConnections {

    /** Stored as {@code Connection.type}: renaming it orphans every stored LLM connection. */
    public static final String TYPE = "LLM";

    public static final String API_KEY = "apiKey";
    public static final String BASE_URL = "baseUrl";
    public static final String MODEL = "model";
    public static final String PROFILE = "profile";
    public static final String TEMPERATURE = "temperature";
    public static final String MAX_TOKENS = "maxTokens";

    private LlmConnections() {
    }

    public static Optional<String> apiKey(Connection c) {
        return text(c.auth(), API_KEY);
    }

    public static Optional<String> baseUrl(Connection c) {
        return text(c.config(), BASE_URL);
    }

    public static Optional<String> model(Connection c) {
        return text(c.config(), MODEL);
    }

    /** Absent means the connection serves only as the default for profiles nobody else claims. */
    public static Optional<String> profile(Connection c) {
        return text(c.config(), PROFILE);
    }

    /** @throws IllegalArgumentException if the value is not a number */
    public static Optional<Double> temperature(Connection c) {
        return text(c.config(), TEMPERATURE).map(v -> number(TEMPERATURE, v).doubleValue());
    }

    /** @throws IllegalArgumentException if the value is not a whole number */
    public static Optional<Integer> maxTokens(Connection c) {
        return text(c.config(), MAX_TOKENS).map(v -> {
            double n = number(MAX_TOKENS, v).doubleValue();
            if (n != Math.rint(n) || n < 1) {
                throw new IllegalArgumentException(MAX_TOKENS + " must be a positive whole number, got " + v);
            }
            return (int) n;
        });
    }

    private static Number number(String field, String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number, got " + value);
        }
    }

    /** JSON numbers arrive as Integer or Double, so everything is read through its text form. */
    private static Optional<String> text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
