package io.personalassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Finds the JSON object in an LLM reply that was asked for JSON; models still wrap it in fences or prose.
 * Never invents one: a reply with no balanced object is empty, not a default.
 */
public final class JsonReplies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonReplies() {
    }

    public static Optional<JsonNode> object(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String candidate = stripFence(reply.trim());
        Optional<JsonNode> direct = parse(candidate);
        if (direct.isPresent()) {
            return direct;
        }
        int start = candidate.indexOf('{');
        while (start >= 0) {
            int end = matchingBrace(candidate, start);
            if (end > start) {
                Optional<JsonNode> parsed = parse(candidate.substring(start, end + 1));
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
            start = candidate.indexOf('{', start + 1);
        }
        return Optional.empty();
    }

    public static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Optional<JsonNode> parse(String candidate) {
        try {
            JsonNode node = MAPPER.readTree(candidate);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String stripFence(String reply) {
        if (!reply.startsWith("```")) {
            return reply;
        }
        int firstNewline = reply.indexOf('\n');
        int closing = reply.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return reply;
        }
        return reply.substring(firstNewline + 1, closing).trim();
    }

    /** String literals are tracked, so a brace inside a quoted value does not close the object. */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
