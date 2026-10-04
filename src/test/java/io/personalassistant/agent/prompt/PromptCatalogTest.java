package io.personalassistant.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.agent.llm.LlmProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptCatalogTest {

    private static PromptCatalog from(String json, Path dir) throws IOException {
        Path file = dir.resolve("prompts.json");
        Files.writeString(file, json);
        PromptCatalog catalog = new PromptCatalog();
        catalog.overridePath = Optional.of(file.toString());
        catalog.load();
        return catalog;
    }

    private static String doc(String prompts, String tasks) {
        return "{\"version\":1,\"prompts\":" + prompts + ",\"tasks\":" + tasks + "}";
    }

    @Test
    void loadsTheBundledCatalogue() {
        PromptCatalog catalog = PromptCatalog.bundled();

        assertTrue(catalog.promptIds().contains("answer"), catalog.promptIds().toString());
        assertTrue(catalog.taskIds().contains("answer"));
        assertEquals("answer", catalog.task("answer").promptId());
        assertEquals("answer", catalog.task("answer").llmProfile(),
                "the task references an LLM profile by name rather than restating model settings");
        assertTrue(catalog.task("answer").contextChars() > 0);
    }

    @Test
    void resolvesAPromptThroughItsTask() {
        assertEquals(PromptCatalog.bundled().prompt("answer"), PromptCatalog.bundled().promptForTask("answer"));
    }

    @Test
    void noPromptMentionsAModelOrProvider() {
        List<String> banned = List.of("llama", "gemini", "groq", "gpt-", "claude", "openai", "mistral",
                "bge", "ollama", "anthropic");

        PromptCatalog catalog = PromptCatalog.bundled();
        for (String id : catalog.promptIds()) {
            PromptTemplate prompt = catalog.prompt(id);
            String text = (prompt.system() + " " + prompt.user()).toLowerCase(Locale.ROOT);
            for (String token : banned) {
                assertFalse(text.contains(token),
                        "prompt \"" + id + "\" mentions \"" + token + "\". Prompts are keyed by task and "
                                + "must work on any model; model choice belongs in an LLM profile.");
            }
        }
    }

    @Test
    void anExternalFileReplacesTheBundledCatalogue(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"mine\":{\"system\":\"custom instructions\",\"user\":\"{{query}}\"}}",
                "{\"mine\":{\"prompt\":\"mine\",\"llmProfile\":\"lite\",\"contextChars\":100,\"maxSources\":2}}"),
                dir);

        assertEquals(java.util.Set.of("mine"), catalog.promptIds(),
                "override replaces rather than merges — a half-overridden catalogue is harder to reason about");
        assertEquals("custom instructions", catalog.prompt("mine").system());
        assertEquals("lite", catalog.task("mine").llmProfile());
    }

    @Test
    void anUnreadableOverridePathFailsLoudly() {
        PromptCatalog catalog = new PromptCatalog();
        catalog.overridePath = Optional.of("/nonexistent/prompts.json");

        assertTrue(assertThrows(IllegalStateException.class, catalog::load).getMessage().contains("not readable"));
    }

    @Test
    void rendersDeclaredVariables(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"p\":{\"variables\":[\"today\"],\"system\":\"Today is {{today}}.\",\"user\":\"{{query}}\"}}",
                "{\"t\":{\"prompt\":\"p\"}}"), dir);

        assertEquals("Today is 2026-08-10.",
                catalog.prompt("p").renderSystem(Map.of("today", "2026-08-10")));
        assertEquals("holidays", catalog.prompt("p").renderUser(Map.of("query", "holidays")));
    }

    @Test
    void anUnsuppliedVariableThrowsRatherThanReachingTheModel(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"p\":{\"variables\":[\"today\"],\"system\":\"Today is {{today}}.\",\"user\":\"{{query}}\"}}",
                "{\"t\":{\"prompt\":\"p\"}}"), dir);

        String message = assertThrows(IllegalStateException.class,
                () -> catalog.prompt("p").renderSystem(Map.of())).getMessage();

        assertTrue(message.contains("today"), message);
        assertTrue(message.contains("p.system"), "the message must name which prompt: " + message);
    }

    @Test
    void substitutesValuesContainingRegexMetacharacters(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"p\":{\"system\":\"x\",\"user\":\"{{query}}\"}}", "{\"t\":{\"prompt\":\"p\"}}"), dir);

        assertEquals("cost is $5 \\ 100%",
                catalog.prompt("p").renderUser(Map.of("query", "cost is $5 \\ 100%")),
                "a user query is arbitrary text and must never be treated as a replacement pattern");
    }

    @Test
    void aTaskPointingAtAMissingPromptFailsToLoad(@TempDir Path dir) {
        String json = doc("{\"p\":{\"system\":\"x\"}}", "{\"t\":{\"prompt\":\"gone\"}}");

        String message = assertThrows(IllegalStateException.class, () -> from(json, dir)).getMessage();

        assertTrue(message.contains("gone"), message);
        assertTrue(message.contains("does not exist"), message);
    }

    @Test
    void anUndeclaredVariableFailsToLoad(@TempDir Path dir) {
        String json = doc("{\"p\":{\"system\":\"Today is {{today}}.\"}}", "{\"t\":{\"prompt\":\"p\"}}");

        String message = assertThrows(IllegalStateException.class, () -> from(json, dir)).getMessage();

        assertTrue(message.contains("today") && message.contains("variables"), message);
    }

    @Test
    void aDeclaredButUnusedVariableFailsToLoad(@TempDir Path dir) {
        String json = doc("{\"p\":{\"variables\":[\"unused\"],\"system\":\"x\"}}", "{\"t\":{\"prompt\":\"p\"}}");

        assertTrue(assertThrows(IllegalStateException.class, () -> from(json, dir))
                .getMessage().contains("never uses"));
    }

    @Test
    void callerSuppliedVariablesNeedNoDeclaration(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"p\":{\"system\":\"x\",\"user\":\"Question: {{query}}\\n{{sources}}\"}}",
                "{\"t\":{\"prompt\":\"p\"}}"), dir);

        assertEquals("Question: q\nS", catalog.prompt("p").renderUser(Map.of("query", "q", "sources", "S")));
    }

    @Test
    void aPromptWithNoSystemTextFailsToLoad(@TempDir Path dir) {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> from(doc("{\"p\":{\"user\":\"x\"}}", "{\"t\":{\"prompt\":\"p\"}}"), dir))
                .getMessage().contains("system"));
    }

    @Test
    void anEmptyCatalogueFailsToLoad(@TempDir Path dir) {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> from(doc("{}", "{}"), dir)).getMessage().contains("at least one"));
    }

    @Test
    void anUnknownPromptOrTaskNamesWhatIsAvailable() {
        PromptCatalog catalog = PromptCatalog.bundled();

        assertTrue(assertThrows(NoSuchElementException.class, () -> catalog.prompt("nope"))
                .getMessage().contains("answer"));
        assertTrue(assertThrows(NoSuchElementException.class, () -> catalog.task("nope"))
                .getMessage().contains("answer"));
    }

    @Test
    void aTaskWithoutAnExplicitPromptFallsBackToItsOwnId(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc("{\"t\":{\"system\":\"x\"}}", "{\"t\":{}}"), dir);

        assertEquals("t", catalog.task("t").promptId(), "the 1:1 case needs no restating");
    }

    @Test
    void readsTheOptionalTaskShapeFieldsCaseInsensitively(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"p\":{\"system\":\"s\",\"user\":\"{{query}}\"}}",
                "{\"t\":{\"prompt\":\"p\",\"sourceText\":\"entity\",\"responseFormat\":\"json_object\"}}"), dir);

        assertEquals(TaskSpec.SourceText.ENTITY, catalog.task("t").sourceText());
        assertEquals(LlmProvider.ResponseFormat.JSON_OBJECT, catalog.task("t").responseFormat());
    }

    @Test
    void aTypoInATaskShapeFieldIsFatalRatherThanSilentlyDefaulted(@TempDir Path dir) throws IOException {
        String json = doc("{\"p\":{\"system\":\"s\",\"user\":\"{{query}}\"}}",
                "{\"t\":{\"prompt\":\"p\",\"sourceText\":\"ENTTIY\"}}");

        String message = assertThrows(IllegalStateException.class, () -> from(json, dir)).getMessage();

        assertTrue(message.contains("sourceText"), message);
        assertTrue(message.contains("ENTTIY"), "the message must name the bad value: " + message);
    }

    @Test
    void anOmittedTaskShapeKeepsTheAnsweringDefaults(@TempDir Path dir) throws IOException {
        PromptCatalog catalog = from(doc(
                "{\"p\":{\"system\":\"s\",\"user\":\"{{query}}\"}}",
                "{\"t\":{\"prompt\":\"p\"}}"), dir);

        assertEquals(TaskSpec.SourceText.CHUNK, catalog.task("t").sourceText());
        assertEquals(LlmProvider.ResponseFormat.TEXT, catalog.task("t").responseFormat());
    }
}
