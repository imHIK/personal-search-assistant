package io.personalassistant.ingestion.retention;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Three-tier retention resolution, and the never-expire default that keeps document corpora safe. */
class RetentionResolverTest {

    private static final SourceType TYPE = SourceType.LOCAL_FS;

    private StubConnector connector() {
        return new StubConnector(TYPE, List.of(new SourceIterable("root", "root", java.util.Map.of())));
    }

    private RetentionResolver resolver(StubConnector connector, String globalDefault) {
        return new RetentionResolver(new SingleConnectorRegistry(connector), globalDefault);
    }

    @Test
    void unsetAtEveryTierNeverExpires() {
        RetentionResolver resolver = resolver(connector(), null);
        Knowledge kn = TestData.knowledge("kn_1", TYPE, Instant.now(), java.util.Map.of());

        Assertions.assertNull(resolver.resolve(kn));
        Assertions.assertNull(resolver.cutoffFor(kn, Instant.now()),
                "a null cutoff is what tells the sweeper to skip this knowledge entirely");
    }

    @Test
    void connectorDefaultAppliesWhenKnowledgeSetsNothing() {
        RetentionResolver resolver = resolver(connector().withDefaultRetention(Duration.ofDays(14)), null);
        Knowledge kn = TestData.knowledge("kn_1", TYPE, Instant.now(), java.util.Map.of());

        Assertions.assertEquals(Duration.ofDays(14), resolver.resolve(kn));
    }

    @Test
    void knowledgeWindowBeatsConnectorDefault() {
        RetentionResolver resolver = resolver(connector().withDefaultRetention(Duration.ofDays(14)), "30d");
        Knowledge kn = TestData.knowledgeWithRetention("kn_1", TYPE, "2d");

        Assertions.assertEquals(Duration.ofDays(2), resolver.resolve(kn));
    }

    @Test
    void globalDefaultIsTheLastTier() {
        RetentionResolver resolver = resolver(connector(), "30d");
        Knowledge kn = TestData.knowledge("kn_1", TYPE, Instant.now(), java.util.Map.of());

        Assertions.assertEquals(Duration.ofDays(30), resolver.resolve(kn));
    }

    @Test
    void cutoffIsMeasuredBackFromNow() {
        RetentionResolver resolver = resolver(connector(), null);
        Knowledge kn = TestData.knowledgeWithRetention("kn_1", TYPE, "7d");
        Instant now = Instant.parse("2026-08-25T00:00:00Z");

        Assertions.assertEquals(Instant.parse("2026-08-18T00:00:00Z"), resolver.cutoffFor(kn, now));
    }

    @Test
    void unregisteredConnectorFallsThroughInsteadOfThrowing() {
        // The sweeper walks every knowledge; one unknown connector type must not stop the rest.
        RetentionResolver resolver = resolver(connector(), "30d");
        Knowledge kn = TestData.knowledge("kn_1", SourceType.NOTION, Instant.now(), java.util.Map.of());

        Assertions.assertEquals(Duration.ofDays(30), resolver.resolve(kn));
    }
}
