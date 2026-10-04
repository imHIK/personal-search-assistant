package io.personalassistant.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.common.ratelimit.RateLimitRule;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectionDtoJsonTest {

    /**
     * Configured like Quarkus's REST mapper (modules registered, unknown properties ignored); Jackson's bare
     * defaults would test the wrong thing.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void bindsARateLimitOffTheCreateBody() throws Exception {
        String json = """
                {"name":"Work Gmail","type":"GMAIL","auth":{"refreshToken":"r"},
                 "rateLimit":{"rules":[{"permits":10,"windowSeconds":1},
                                       {"permits":500,"windowSeconds":60}]},
                 "makeDefault":true}
                """;

        ConnectionDto dto = mapper.readValue(json, ConnectionDto.class);

        assertEquals(2, dto.rateLimit().rules().size());
        assertEquals(new RateLimitRule(10, 1), dto.rateLimit().rules().get(0));
        assertEquals(500, dto.toRequest().rateLimit().rules().get(1).permits());
        assertEquals("GMAIL", dto.toRequest().type());
    }

    @Test
    void anAbsentRateLimitBindsAsNullRatherThanEmpty() throws Exception {
        ConnectionEditDto dto = mapper.readValue("{\"name\":\"Renamed\"}", ConnectionEditDto.class);

        assertNull(dto.rateLimit(), "absent must stay null so the service reads it as 'unchanged'");
    }

    @Test
    void anEmptyRuleListBindsAsAnEmptyPolicy() throws Exception {
        ConnectionEditDto dto =
                mapper.readValue("{\"rateLimit\":{\"rules\":[]}}", ConnectionEditDto.class);

        assertNotNull(dto.rateLimit());
        assertTrue(dto.rateLimit().isUnlimited());
    }

    /**
     * The derived "unlimited" field (from {@code isUnlimited()}) is left in on purpose. It must be ignored on
     * the way back in, since the console PATCHes back what it read.
     */
    @Test
    void serializesTheStoredPolicyBackInTheSameShapeItAccepts() throws Exception {
        Connection connection = new Connection("conn_1", "Work Gmail", "GMAIL", Map.of(),
                Map.of(), new RateLimitPolicy(List.of(new RateLimitRule(500, 60))), true,
                ConnectionStatus.ACTIVE, null, Instant.now(), Instant.now());

        String json = mapper.writeValueAsString(connection);

        assertTrue(json.contains("\"rateLimit\":{\"rules\":[{\"permits\":500,\"windowSeconds\":60}]"), json);
        assertEquals(new RateLimitPolicy(List.of(new RateLimitRule(500, 60))),
                mapper.readValue(json, ConnectionEditDto.class).rateLimit(),
                "what the API emits must be accepted back verbatim on the next PATCH");
    }
}
