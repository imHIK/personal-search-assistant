package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.ratelimit.RateLimitRules;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmProfilesTest {

    private static LlmProfiles profiles(Map<String, String> properties) {
        return profiles(properties, new InMemoryConnectionRepository());
    }

    private static LlmProfiles profiles(Map<String, String> properties, InMemoryConnectionRepository repo) {
        return new LlmProfiles(new SmallRyeConfigBuilder().withDefaultValues(properties).build(), repo);
    }

    private static Connection llm(String id, String profile, boolean isDefault, ConnectionStatus status) {
        Map<String, Object> config = new HashMap<>();
        config.put(LlmConnections.BASE_URL, "https://" + id + ".example/v1");
        config.put(LlmConnections.MODEL, id + "-model");
        if (profile != null) {
            config.put(LlmConnections.PROFILE, profile);
        }
        return new Connection(id, id, LlmConnections.TYPE, Map.of(LlmConnections.API_KEY, id + "-key"),
                config, RateLimitRules.parse("5/1m"), isDefault, status, null, Instant.now(), Instant.now());
    }

    @Test
    void aConnectionServingTheProfileWinsOverTheDefaultAndConfig() {
        InMemoryConnectionRepository repo = new InMemoryConnectionRepository();
        repo.save(llm("groq", null, true, ConnectionStatus.ACTIVE));
        repo.save(llm("gemini", "lite", false, ConnectionStatus.ACTIVE));
        LlmProfiles resolved = profiles(Map.of(
                "app.llm.profile.lite.model", "configured",
                "app.llm.profile.lite.max-tokens", "4096"), repo);

        LlmProfile lite = resolved.get("lite");
        assertEquals(Optional.of("gemini-model"), lite.model());
        assertEquals(Optional.of("https://gemini.example/v1"), lite.baseUrl());
        assertEquals(Optional.of("gemini-key"), lite.apiKey());
        assertEquals(Optional.of("gemini"), lite.connectionId());
        assertEquals(Optional.of(4096), lite.maxTokens(), "unset on the connection, so config fills it");
        assertEquals(1, lite.rateLimit().orElseThrow().rules().size());

        assertEquals(Optional.of("groq"), resolved.get("answer").connectionId(),
                "an unclaimed profile goes to the default connection");
        assertEquals(Optional.of("groq"), resolved.get(null).connectionId());
    }

    @Test
    void anInactiveConnectionFallsThroughToConfig() {
        InMemoryConnectionRepository repo = new InMemoryConnectionRepository();
        repo.save(llm("gemini", "lite", true, ConnectionStatus.ERROR));

        LlmProfile lite = profiles(Map.of("app.llm.profile.lite.model", "configured"), repo).get("lite");

        assertEquals(Optional.of("configured"), lite.model());
        assertTrue(lite.connectionId().isEmpty());
    }

    @Test
    void namesIncludeTheProfilesConnectionsServe() {
        InMemoryConnectionRepository repo = new InMemoryConnectionRepository();
        repo.save(llm("ollama", "local", false, ConnectionStatus.ACTIVE));

        assertTrue(profiles(Map.of("app.llm.profile.answer.model", "x"), repo).names()
                .containsAll(List.of("answer", "local")));
    }

    @Test
    void readsEveryRecognisedKey() {
        LlmProfile profile = profiles(Map.of(
                "app.llm.profile.answer.base-url", "https://api.groq.com/openai/v1",
                "app.llm.profile.answer.model", "llama-3.3-70b-versatile",
                "app.llm.profile.answer.temperature", "0.2",
                "app.llm.profile.answer.max-tokens", "2048",
                "app.llm.profile.answer.api-key", "sk-test")).get("answer");

        assertEquals("answer", profile.name());
        assertEquals(Optional.of("https://api.groq.com/openai/v1"), profile.baseUrl());
        assertEquals(Optional.of("llama-3.3-70b-versatile"), profile.model());
        assertEquals(Optional.of(0.2), profile.temperature());
        assertEquals(Optional.of(2048), profile.maxTokens());
        assertEquals(Optional.of("sk-test"), profile.apiKey());
    }

    @Test
    void leavesUnsetKeysEmptySoTheProviderDefaultApplies() {
        LlmProfile profile = profiles(Map.of("app.llm.profile.lite.model", "llama-3.1-8b-instant"))
                .get("lite");

        assertEquals(Optional.of("llama-3.1-8b-instant"), profile.model());
        assertTrue(profile.temperature().isEmpty(), "temperature must inherit, not reset");
        assertTrue(profile.baseUrl().isEmpty());
        assertTrue(profile.maxTokens().isEmpty());
        assertTrue(profile.apiKey().isEmpty());
    }

    @Test
    void treatsBlankAsUnset() {
        LlmProfile profile = profiles(Map.of(
                "app.llm.profile.lite.model", "llama-3.1-8b-instant",
                "app.llm.profile.lite.api-key", "   ")).get("lite");

        assertTrue(profile.apiKey().isEmpty(), "blank is absent, not an empty credential");
    }

    @Test
    void unknownProfileInheritsEverything() {
        LlmProfile profile = profiles(Map.of("app.llm.profile.answer.model", "x")).get("rerank");

        assertEquals("rerank", profile.name(), "the name is kept so logs and errors can report it");
        assertTrue(profile.model().isEmpty());
        assertTrue(profile.baseUrl().isEmpty());
    }

    @Test
    void toleratesANullOrBlankName() {
        LlmProfiles resolved = profiles(Map.of("app.llm.profile.answer.model", "x"));

        assertTrue(resolved.get(null).model().isEmpty());
        assertTrue(resolved.get("  ").model().isEmpty());
    }

    @Test
    void profilesAreIndependent() {
        LlmProfiles resolved = profiles(Map.of(
                "app.llm.profile.answer.model", "llama-3.3-70b-versatile",
                "app.llm.profile.lite.model", "llama-3.1-8b-instant"));

        assertEquals(Optional.of("llama-3.3-70b-versatile"), resolved.get("answer").model());
        assertEquals(Optional.of("llama-3.1-8b-instant"), resolved.get("lite").model());
        assertFalse(resolved.get("answer").model().equals(resolved.get("lite").model()));
    }

    @Test
    void inheritFactoryOverridesNothing() {
        LlmProfile profile = LlmProfile.inherit("default");

        assertEquals("default", profile.name());
        assertTrue(profile.model().isEmpty() && profile.baseUrl().isEmpty()
                && profile.temperature().isEmpty() && profile.maxTokens().isEmpty()
                && profile.apiKey().isEmpty());
    }
}
