package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.common.ratelimit.RateLimitRules;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.TestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmProfilesTest {

    private final InMemoryConnectionRepository repo = new InMemoryConnectionRepository();
    private final LlmProfiles profiles = new LlmProfiles(repo, PromptCatalog.bundled());

    private Connection save(String id, String profile, boolean isDefault, ConnectionStatus status) {
        return repo.save(TestData.llmConnection(id, "https://" + id + ".example/v1", profile, isDefault, status));
    }

    @Test
    void theConnectionServingAProfileWinsOverTheDefault() {
        save("groq", null, true, ConnectionStatus.ACTIVE);
        save("gemini", "lite", false, ConnectionStatus.ACTIVE);

        LlmProfile lite = profiles.get("lite");
        assertEquals("gemini", lite.connectionId());
        assertEquals("https://gemini.example/v1", lite.baseUrl());
        assertEquals("gemini-model", lite.model());
        assertEquals(Optional.of("gemini-key"), lite.apiKey());
        assertEquals("lite", lite.name());

        assertEquals("groq", profiles.get("answer").connectionId(), "an unclaimed profile takes the default");
        assertEquals("groq", profiles.get(null).connectionId());
    }

    @Test
    void blankTemperatureAndMaxTokensAreLeftOut() {
        save("groq", null, true, ConnectionStatus.ACTIVE);

        LlmProfile profile = profiles.get("answer");
        assertTrue(profile.temperature().isEmpty(), "nothing is invented: the vendor default applies");
        assertTrue(profile.maxTokens().isEmpty());
    }

    @Test
    void readsTemperatureMaxTokensAndRateLimitFromTheConnection() {
        Connection c = TestData.llmConnection("gemini", "https://g/v1", "lite", true, ConnectionStatus.ACTIVE);
        Map<String, Object> config = new HashMap<>(c.config());
        config.put("temperature", 0.0);
        config.put("maxTokens", 4096);
        repo.save(c.withEdits(c.name(), c.auth(), config, RateLimitRules.parse("15/1m"), c.updatedAt()));

        LlmProfile lite = profiles.get("lite");
        assertEquals(Optional.of(0.0), lite.temperature());
        assertEquals(Optional.of(4096), lite.maxTokens());
        assertEquals(1, lite.rateLimit().rules().size());
    }

    @Test
    void anErroredConnectionIsStillUsedButADisabledOneIsNot() {
        save("flaky", "lite", false, ConnectionStatus.ERROR);
        save("off", null, true, ConnectionStatus.DISABLED);

        assertEquals("flaky", profiles.get("lite").connectionId(),
                "with no fallback, a failed health check must not take the only connection away");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> profiles.get("answer"));
        assertTrue(e.getMessage().contains("Accounts"), e.getMessage());
    }

    @Test
    void withNoConnectionEveryProfileFailsNamingWhereToAddOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> profiles.get("lite"));

        assertTrue(e.getMessage().contains("\"lite\""), e.getMessage());
    }

    @Test
    void namesAreConnectionProfilesPlusTheBundledTasksProfiles() {
        save("ollama", "local", false, ConnectionStatus.ACTIVE);

        assertTrue(profiles.names().containsAll(List.of("local", "answer", "lite")), profiles.names().toString());
    }
}
