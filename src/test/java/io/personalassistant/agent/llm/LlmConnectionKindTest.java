package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.RecordingRateLimiter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmConnectionKindTest {

    private final InMemoryConnectionRepository repo = new InMemoryConnectionRepository();
    private final LlmConnectionKind kind =
            new LlmConnectionKind(new OutboundHttp(new RecordingRateLimiter()), repo);

    private static Connection llm(String id, Map<String, Object> config) {
        return new Connection(id, id, LlmConnections.TYPE, Map.of(), config, null, false,
                ConnectionStatus.ACTIVE, null, Instant.now(), Instant.now());
    }

    @Test
    void refusesASecondConnectionForTheSameProfile() {
        repo.save(llm("first", Map.of("baseUrl", "http://a/v1", "model", "m", "profile", "lite")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> kind.verify(
                llm("second", Map.of("baseUrl", "http://b/v1", "model", "m", "profile", "lite"))));

        assertTrue(e.getMessage().contains("lite"), e.getMessage());
    }

    @Test
    void requiresEndpointAndModelBeforeCallingOut() {
        assertThrows(IllegalArgumentException.class, () -> kind.verify(llm("x", Map.of("model", "m"))));
        assertThrows(IllegalArgumentException.class,
                () -> kind.verify(llm("x", Map.of("baseUrl", "http://a/v1"))));
    }

    @Test
    void rejectsANonNumericMaxTokens() {
        assertThrows(IllegalArgumentException.class, () -> kind.verify(llm("x",
                Map.of("baseUrl", "http://a/v1", "model", "m", "maxTokens", "lots"))));
    }
}
