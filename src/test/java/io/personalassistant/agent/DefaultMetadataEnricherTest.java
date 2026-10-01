package io.personalassistant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.agent.llm.LlmProfile;
import io.personalassistant.agent.llm.LlmProfiles;
import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.domain.model.Task;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.InMemoryTaskRepository;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultMetadataEnricherTest {

    private static final class ScriptedLlm implements LlmProvider {
        String reply;
        String system;
        String user;
        LlmProfile profile;
        ResponseFormat format;

        @Override
        public String providerId() {
            return "scripted";
        }

        @Override
        public String model() {
            return "m";
        }

        @Override
        public String complete(String system, List<Message> messages) {
            return complete(LlmProfile.inherit("default"), ResponseFormat.TEXT, system, messages);
        }

        @Override
        public String complete(LlmProfile profile, ResponseFormat format, String system,
                               List<Message> messages) {
            this.profile = profile;
            this.format = format;
            this.system = system;
            this.user = messages.get(0).content();
            return reply;
        }
    }

    private final ScriptedLlm llm = new ScriptedLlm();
    private final DefaultMetadataEnricher enricher = new DefaultMetadataEnricher(llm,
            new LlmProfiles(new SmallRyeConfigBuilder().build(), new InMemoryConnectionRepository()),
            new TaskLibrary(PromptCatalog.bundled(), new InMemoryTaskRepository()));

    private static Task task(Task.Field... fields) {
        return new Task("task_1", "Facts", "", Task.Mode.SIMPLE, "Extract posting facts.",
                Task.Output.METADATA, List.of(fields), null, null, "lite", Task.SourceText.ENTITY, 0, 0,
                null, null);
    }

    @Test
    void asksForJsonAndWaitsForTheWindowAsBackgroundWork() {
        llm.reply = "{\"yoe\": 3}";
        enricher.enrich(task(new Task.Field("yoe", Task.FieldType.NUMBER, "", true)), "Engineer", "Body");

        assertEquals(LlmProvider.ResponseFormat.JSON_OBJECT, llm.format);
        assertEquals(RateLimitMode.WAIT, llm.profile.rateLimitMode().orElseThrow());
        assertTrue(llm.system.contains("Extract posting facts."), llm.system);
        assertTrue(llm.system.contains("\"yoe\": 0"), "the generated contract is in the prompt");
        assertTrue(llm.user.contains("Engineer\n\"\"\"\nBody\n\"\"\""), llm.user);
    }

    @Test
    void coercesEachFieldToItsDeclaredType() {
        llm.reply = "```json\n{\"yoe\": \"5+ years\", \"remote\": \"yes\","
                + " \"skills\": [\"java\", \"Go\", \"cobol\"], \"level\": \"SENIOR\", \"extra\": 1}\n```";

        Map<String, Object> values = enricher.enrich(task(
                new Task.Field("yoe", Task.FieldType.NUMBER, "", true),
                new Task.Field("remote", Task.FieldType.BOOLEAN, "", true),
                new Task.Field("skills", Task.FieldType.LIST, "", true, List.of("Java", "Go")),
                new Task.Field("level", Task.FieldType.TEXT, "", true, List.of("Junior", "Senior"))), "t", "x");

        assertEquals(5L, values.get("yoe"));
        assertEquals(true, values.get("remote"));
        assertEquals(List.of("Java", "Go"), values.get("skills"), "canonical spellings; unknown values drop");
        assertEquals("Senior", values.get("level"));
        assertFalse(values.containsKey("extra"), "fields the task does not declare are dropped");
    }

    @Test
    void anOptionalNullIsOmittedButARequiredOneFails() {
        llm.reply = "{\"yoe\": null, \"team\": null}";

        assertTrue(enricher.enrich(task(new Task.Field("yoe", Task.FieldType.NUMBER, "", true)), "t", "x")
                .isEmpty());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> enricher.enrich(
                task(new Task.Field("team", Task.FieldType.TEXT, "", false)), "t", "x"));
        assertTrue(e.getMessage().contains("team"), e.getMessage());
    }

    @Test
    void aReplyWithoutAnObjectFails() {
        llm.reply = "I could not find anything.";

        assertThrows(IllegalStateException.class,
                () -> enricher.enrich(task(new Task.Field("yoe", Task.FieldType.NUMBER, "", true)), "t", "x"));
    }

    @Test
    void longTextIsCutToTheBudget() {
        String block = DefaultMetadataEnricher.sourceBlock("T", "a".repeat(500), 100);

        assertTrue(block.contains(AnswerPromptBuilder.TRUNCATION_MARKER), block);
        assertTrue(block.length() < 200, "cut to the budget plus title and fences");
    }
}
