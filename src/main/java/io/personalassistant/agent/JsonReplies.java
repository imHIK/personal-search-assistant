package io.personalassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Reads a JSON object out of an LLM reply that was <em>asked</em> to be JSON.
 *
 * <p>{@code response_format} is a request, not a contract: not every OpenAI-compatible vendor honours
 * it, and models that do still wrap the object in a ```json fence or preface it with a sentence often
 * enough to matter. A caller that simply calls {@code readTree} on the raw reply therefore fails on
 * output that is perfectly usable — and for a bulk task scored per item, one such failure should cost
 * that item, not the run.
 *
 * <p>So this is deliberately forgiving in one direction only: it will find an object inside noise, but
 * it will not invent one. A reply with no balanced object yields {@link Optional#empty()}, which the
 * caller reports as "unscored" rather than substituting a default that would look like a real result.
 */
public final class JsonReplies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonReplies() {
    }

    /** The first balanced JSON object in {@code reply}, or empty when there is none. */
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

    /** Convenience: a text field from the object, or null. */
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

    /** Drop a surrounding ```json … ``` fence if one is present. */
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

    /**
     * Index of the brace closing the one at {@code start}, or -1. String literals are tracked so a brace
     * inside a quoted value — a reason field quoting the posting, say — does not end the object early.
     */
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
