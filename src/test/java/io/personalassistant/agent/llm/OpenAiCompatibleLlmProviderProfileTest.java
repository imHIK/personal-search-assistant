package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.testsupport.RecordingRateLimiter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * How a {@link LlmProfile} is folded into the provider's own configuration. No HTTP is involved — these
 * assert the resolution rules through {@code callSummary}, which reports exactly what the request was
 * built from. The rules matter because a profile is partial by design: getting inheritance wrong would
 * silently move a call to a different model, endpoint, or temperature than the caller asked for.
 */
class OpenAiCompatibleLlmProviderProfileTest {

    private static OpenAiCompatibleLlmProvider provider() {
        // No request is ever sent here — these assert resolution, not transport.
        OpenAiCompatibleLlmProvider p = new OpenAiCompatibleLlmProvider(
                new OutboundHttp(new RecordingRateLimiter()), RateLimitPolicies.unlimited());
        p.baseUrl = "https://api.groq.com/openai/v1";
        p.modelName = "llama-3.3-70b-versatile";
        p.temperature = 0.2;
        p.timeoutSeconds = 60;
        p.apiKey = Optional.of("provider-key");
        return p;
    }

    private static String summary(OpenAiCompatibleLlmProvider p, LlmProfile profile) {
        String endpoint = profile.baseUrl().orElse(p.baseUrl);
        String model = profile.model().orElse(p.modelName);
        return p.callSummary(profile, endpoint, model, true);
    }

    @Test
    void anInheritEverythingProfileChangesNothing() {
        String summary = summary(provider(), LlmProfile.inherit("default"));

        assertTrue(summary.contains("model=llama-3.3-70b-versatile"), summary);
        assertTrue(summary.contains("base-url=https://api.groq.com/openai/v1"), summary);
        assertTrue(summary.contains("temperature=0.2"), summary);
        assertTrue(summary.contains("max-tokens=<unset>"),
                "no max_tokens is sent unless asked for — the vendor default is the prior behaviour");
    }

    @Test
    void aProfileOverridesOnlyWhatItSets() {
        LlmProfile lite = new LlmProfile("lite", Optional.empty(), Optional.of("llama-3.1-8b-instant"),
                Optional.empty(), Optional.of(512), Optional.empty(), Optional.empty());

        String summary = summary(provider(), lite);

        assertTrue(summary.contains("model=llama-3.1-8b-instant"), summary);
        assertTrue(summary.contains("max-tokens=512"), summary);
        assertTrue(summary.contains("temperature=0.2"), "temperature inherits: " + summary);
        assertTrue(summary.contains("base-url=https://api.groq.com/openai/v1"),
                "endpoint inherits: " + summary);
    }

    @Test
    void namesTheProfileSoAFailureReportsTheModelThatActuallyFailed() {
        LlmProfile rerank = new LlmProfile("rerank", Optional.empty(), Optional.of("some-model"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertTrue(summary(provider(), rerank).contains("profile=rerank"));
    }

    /**
     * A profile that redirects {@code base-url} must bring its own credential. Inheriting the provider's
     * key would send one vendor's secret to another vendor's host — a credential leak, not a fallback.
     */
    @Test
    void aRedirectedProfileDoesNotInheritTheProvidersKey() {
        LlmProfile ollama = new LlmProfile("local", Optional.of("http://localhost:11434/v1"),
                Optional.of("llama3.1:8b"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());

        assertNull(provider().resolveKey(ollama),
                "a redirected endpoint gets no key unless the profile supplies one");
    }

    @Test
    void aRedirectedProfileUsesItsOwnKeyWhenItHasOne() {
        LlmProfile elsewhere = new LlmProfile("elsewhere", Optional.of("https://api.openai.com/v1"),
                Optional.of("gpt-4o-mini"), Optional.empty(), Optional.empty(),
                Optional.of("other-key"), Optional.empty());

        assertEquals("other-key", provider().resolveKey(elsewhere));
    }

    @Test
    void aProfileWithoutARedirectFallsBackToTheProvidersKey() {
        assertEquals("provider-key", provider().resolveKey(LlmProfile.inherit("answer")));
    }

    @Test
    void aBlankProfileKeyIsIgnoredRatherThanSentAsEmpty() {
        LlmProfile blank = new LlmProfile("answer", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("   "), Optional.empty());

        assertEquals("provider-key", provider().resolveKey(blank));
    }

    @Test
    void reportsAnAbsentKeyWithoutLeakingIt() {
        String summary = provider().callSummary(LlmProfile.inherit("answer"),
                "http://localhost:11434/v1", "llama3.1:8b", false);

        assertFalse(summary.contains("api-key=present"));
        assertFalse(summary.contains("provider-key"), "the key itself must never reach a log or error");
    }

    @Test
    void reportsTheModelName() {
        assertEquals("llama-3.3-70b-versatile", provider().model());
        assertEquals("openai-compat", provider().providerId());
    }

    /** The stub inherits the default method, so selecting {@code none} still disables answering. */
    @Test
    void theStubProviderIgnoresProfilesAndStillRefuses() {
        LlmProvider stub = new StubLlmProvider();

        assertThrows(UnsupportedOperationException.class,
                () -> stub.complete(LlmProfile.inherit("answer"), "sys", List.of()));
    }
}
