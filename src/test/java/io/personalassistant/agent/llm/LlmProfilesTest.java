package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Profiles are resolved from dynamic config keys rather than fixed injection points, which is what lets
 * a new LLM-using feature pick its model in {@code application.properties} instead of in code. These
 * pin the two properties that makes safe: an unset key inherits rather than overriding, and a name with
 * no configuration at all degrades to the provider defaults instead of taking the caller down.
 */
class LlmProfilesTest {

    private static LlmProfiles profiles(Map<String, String> properties) {
        return new LlmProfiles(new SmallRyeConfigBuilder().withDefaultValues(properties).build());
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

    /** A profile that names only a model must not silently reset temperature, timeout or endpoint. */
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

    /**
     * A blank value is how a {@code ${ENV_VAR:}}-backed key looks when the variable is unset. It has to
     * mean "inherit", not "override with empty" — an empty model would be sent to the endpoint verbatim.
     */
    @Test
    void treatsBlankAsUnset() {
        LlmProfile profile = profiles(Map.of(
                "app.llm.profile.lite.model", "llama-3.1-8b-instant",
                "app.llm.profile.lite.api-key", "   ")).get("lite");

        assertTrue(profile.apiKey().isEmpty(), "blank is absent, not an empty credential");
    }

    /**
     * Degrading beats failing here: a misspelled profile on a background feature would otherwise take
     * out that feature entirely, when running on the provider's default model is a working outcome.
     */
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
