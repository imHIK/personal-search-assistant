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
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rate-limit wire contract, both directions.
 *
 * <p>Worth pinning because nothing else covers it: {@code ConnectionResource} returns the domain record
 * directly, so the response shape is whatever Jackson makes of it, and the request shape is whatever
 * Jackson can bind. The window is held as {@code windowSeconds} rather than a {@link java.time.Duration}
 * precisely so both sides read as plain numbers — a {@code Duration} would serialize as {@code "PT1M"}
 * and quietly diverge from what the console sends.
 */
class ConnectionDtoJsonTest {

    /**
     * Configured to match Quarkus's REST mapper rather than Jackson's bare defaults, or this tests the
     * wrong thing: {@code findAndRegisterModules} is what lets the record's {@code Instant} fields
     * serialize at all, and Quarkus ships with unknown properties ignored.
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
        assertEquals(SourceType.GMAIL, dto.toRequest().type());
    }

    @Test
    void anAbsentRateLimitBindsAsNullRatherThanEmpty() throws Exception {
        ConnectionEditDto dto = mapper.readValue("{\"name\":\"Renamed\"}", ConnectionEditDto.class);

        assertNull(dto.rateLimit(), "absent must stay null so the service reads it as 'unchanged'");
    }

    /** The only way to remove a limit; if this bound as null it would silently mean "keep it". */
    @Test
    void anEmptyRuleListBindsAsAnEmptyPolicy() throws Exception {
        ConnectionEditDto dto =
                mapper.readValue("{\"rateLimit\":{\"rules\":[]}}", ConnectionEditDto.class);

        assertNotNull(dto.rateLimit());
        assertTrue(dto.rateLimit().isUnlimited());
    }

    /**
     * The response also carries a derived {@code "unlimited"} field, because Jackson reads
     * {@code isUnlimited()} as a getter. That is left alone deliberately: every other derived predicate
     * on a domain record does the same ({@code Entity.Content.isFile()}, {@code CursorPosition.isStart()}),
     * and suppressing this one would put the codebase's first Jackson annotation in {@code common} to fix
     * cosmetics. It is additive and ignored on the way back in — which this asserts, since the console
     * reads a connection and PATCHes it back.
     */
    @Test
    void serializesTheStoredPolicyBackInTheSameShapeItAccepts() throws Exception {
        Connection connection = new Connection("conn_1", "Work Gmail", SourceType.GMAIL, Map.of(),
                Map.of(), new RateLimitPolicy(List.of(new RateLimitRule(500, 60))), true,
                ConnectionStatus.ACTIVE, null, Instant.now(), Instant.now());

        String json = mapper.writeValueAsString(connection);

        assertTrue(json.contains("\"rateLimit\":{\"rules\":[{\"permits\":500,\"windowSeconds\":60}]"), json);
        assertEquals(new RateLimitPolicy(List.of(new RateLimitRule(500, 60))),
                mapper.readValue(json, ConnectionEditDto.class).rateLimit(),
                "what the API emits must be accepted back verbatim on the next PATCH");
    }
}
