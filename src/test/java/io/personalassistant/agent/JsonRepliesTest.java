package io.personalassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code response_format} is a request, not a contract, so these cover the shapes a model actually
 * returns when it half-honours it — and the one case that must stay a failure.
 */
class JsonRepliesTest {

    @Test
    void readsAPlainObject() {
        JsonNode node = JsonReplies.object("{\"score\": 8, \"reason\": \"strong match\"}").orElseThrow();

        Assertions.assertEquals(8, node.get("score").asInt());
        Assertions.assertEquals("strong match", JsonReplies.text(node, "reason"));
    }

    @Test
    void readsThroughAMarkdownFence() {
        Optional<JsonNode> node = JsonReplies.object("```json\n{\"facets\": [\"a\", \"b\"]}\n```");

        Assertions.assertTrue(node.isPresent());
        Assertions.assertEquals(2, node.get().get("facets").size());
    }

    @Test
    void readsThroughAFenceWithNoLanguageTag() {
        Assertions.assertTrue(JsonReplies.object("```\n{\"ok\": true}\n```").isPresent());
    }

    @Test
    void readsAnObjectPrefacedByProse() {
        Optional<JsonNode> node =
                JsonReplies.object("Sure! Here is the result:\n{\"score\": 3}\nHope that helps.");

        Assertions.assertEquals(3, node.orElseThrow().get("score").asInt());
    }

    @Test
    void bracesInsideStringsDoNotEndTheObjectEarly() {
        // A reason field quoting the posting is exactly how this breaks in practice.
        JsonNode node = JsonReplies.object("{\"reason\": \"uses {curly} braces\", \"score\": 5}")
                .orElseThrow();

        Assertions.assertEquals(5, node.get("score").asInt());
        Assertions.assertEquals("uses {curly} braces", JsonReplies.text(node, "reason"));
    }

    @Test
    void escapedQuotesInsideStringsAreHandled() {
        JsonNode node = JsonReplies.object("{\"reason\": \"they said \\\"yes\\\" twice\", \"score\": 1}")
                .orElseThrow();

        Assertions.assertEquals(1, node.get("score").asInt());
    }

    @Test
    void nestedObjectsSurvive() {
        JsonNode node = JsonReplies.object("noise {\"outer\": {\"inner\": 2}} more noise").orElseThrow();

        Assertions.assertEquals(2, node.get("outer").get("inner").asInt());
    }

    @Test
    void returnsEmptyRatherThanInventingAnObject() {
        // The caller reports "unscored"; substituting a default would look like a real result.
        Assertions.assertTrue(JsonReplies.object("I am unable to help with that.").isEmpty());
        Assertions.assertTrue(JsonReplies.object("{\"unterminated\": ").isEmpty());
        Assertions.assertTrue(JsonReplies.object("[1, 2, 3]").isEmpty(), "a bare array is not an object");
        Assertions.assertTrue(JsonReplies.object("").isEmpty());
        Assertions.assertTrue(JsonReplies.object(null).isEmpty());
    }
}
