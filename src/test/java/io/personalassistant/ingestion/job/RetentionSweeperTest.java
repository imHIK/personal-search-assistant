package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.retention.RetentionResolver;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The sweeper's two passes, and the properties that keep it from eating a corpus: retention is
 * opt-in, and the clock runs from {@code createdAt} rather than {@code updatedAt}.
 */
class RetentionSweeperTest {

    private static final SourceType TYPE = SourceType.LOCAL_FS;
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    private final InMemoryEntityRepository entities = new InMemoryEntityRepository();
    private final InMemoryKnowledgeRepository knowledges = new InMemoryKnowledgeRepository();

    private RetentionSweeper sweeper(String globalDefault, Duration connectorDefault) {
        StubConnector connector = new StubConnector(TYPE, List.of(new SourceIterable("root", "root", Map.of())));
        connector.withDefaultRetention(connectorDefault);
        RetentionResolver resolver =
                new RetentionResolver(new SingleConnectorRegistry(connector), globalDefault);
        RetentionSweeper sweeper = new RetentionSweeper(entities, knowledges, resolver);
        sweeper.batch = 100;
        return sweeper;
    }

    private Entity store(Entity entity) {
        entities.store.put(entity.id(), entity);
        return entity;
    }

    @Test
    void retentionUnsetNeverExpiresAnything() {
        // The safety property of the whole feature: a document corpus must not delete itself.
        Knowledge kn = TestData.knowledge("kn_1", TYPE, NOW, Map.of());
        knowledges.store.put(kn.id(), kn);
        store(TestData.agedEntity("ent_ancient", kn.id(), "a", NOW.minus(Duration.ofDays(3650)), null));

        sweeper(null, null).sweep(NOW);

        Assertions.assertEquals(EntityStatus.INDEXED, entities.store.get("ent_ancient").status());
    }

    @Test
    void entityOlderThanTheWindowIsTombstoned() {
        Knowledge kn = TestData.knowledgeWithRetention("kn_1", TYPE, "14d");
        knowledges.store.put(kn.id(), kn);
        store(TestData.agedEntity("ent_old", kn.id(), "old", NOW.minus(Duration.ofDays(20)), null));
        store(TestData.agedEntity("ent_fresh", kn.id(), "fresh", NOW.minus(Duration.ofDays(2)), null));

        sweeper(null, null).sweep(NOW);

        Assertions.assertEquals(EntityStatus.DELETED, entities.store.get("ent_old").status());
        Assertions.assertEquals(EntityStatus.INDEXED, entities.store.get("ent_fresh").status());
    }

    @Test
    void tombstoningLeavesChunkRemovalToTheOrdinaryDeletionPath() {
        // The sweeper must never delete directly: a tombstone is what IndexingJob.processDeletions
        // claims under a lease before calling deleteByEntity. Dropping the document instead would
        // orphan its chunks in OpenSearch forever.
        Knowledge kn = TestData.knowledgeWithRetention("kn_1", TYPE, "1d");
        knowledges.store.put(kn.id(), kn);
        store(TestData.agedEntity("ent_old", kn.id(), "old", NOW.minus(Duration.ofDays(5)), null));

        sweeper(null, null).sweep(NOW);

        Entity swept = entities.store.get("ent_old");
        Assertions.assertNotNull(swept, "the document must still exist for the deletion path to claim");
        Assertions.assertEquals(EntityStatus.DELETED, swept.status());
        Assertions.assertTrue(swept.needsReindex(), "needsReindex is what claimForDeletion filters on");
        Assertions.assertEquals(1, entities.claimForDeletion(10, "worker", Duration.ofMinutes(5)).size());
    }

    @Test
    void ageIsMeasuredFromCreatedAtNotUpdatedAt() {
        // An entity re-touched by a walk keeps its original createdAt, so it still ages out. The
        // inverse — clocking on updatedAt — is what would keep stale material alive indefinitely.
        Knowledge kn = TestData.knowledgeWithRetention("kn_1", TYPE, "14d");
        knowledges.store.put(kn.id(), kn);
        Entity old = TestData.agedEntity("ent_old", kn.id(), "old", NOW.minus(Duration.ofDays(20)), null);
        store(new Entity(old.id(), old.knowledgeId(), old.iterableId(), old.entityType(), old.externalId(),
                old.raw(), old.content(), old.metadata(), old.checksum(), old.status(),
                old.needsReindex(), old.needsRefetch(),
                old.index(), old.lease(), old.retry(), old.createdAt(), NOW, old.expiresAt(),
                old.lastSeenGeneration()));

        sweeper(null, null).sweep(NOW);

        Assertions.assertEquals(EntityStatus.DELETED, entities.store.get("ent_old").status());
    }

    @Test
    void explicitExpiryAppliesEvenWithoutAKnowledgeWindow() {
        Knowledge kn = TestData.knowledge("kn_1", TYPE, NOW, Map.of());
        knowledges.store.put(kn.id(), kn);
        store(TestData.agedEntity("ent_closed", kn.id(), "closed",
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(1))));
        store(TestData.agedEntity("ent_open", kn.id(), "open",
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30))));

        sweeper(null, null).sweep(NOW);

        Assertions.assertEquals(EntityStatus.DELETED, entities.store.get("ent_closed").status());
        Assertions.assertEquals(EntityStatus.INDEXED, entities.store.get("ent_open").status());
    }

    @Test
    void explicitExpiryBeatsTheKnowledgeWindow() {
        // The source said this item is valid until well past the window; the window must not
        // override that statement.
        Knowledge kn = TestData.knowledgeWithRetention("kn_1", TYPE, "7d");
        knowledges.store.put(kn.id(), kn);
        store(TestData.agedEntity("ent_long_lived", kn.id(), "long",
                NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(30))));

        sweeper(null, null).sweep(NOW);

        Assertions.assertEquals(EntityStatus.INDEXED, entities.store.get("ent_long_lived").status());
    }

    @Test
    void connectorDefaultDrivesTheSweepWhenTheKnowledgeSaysNothing() {
        Knowledge kn = TestData.knowledge("kn_1", TYPE, NOW, Map.of());
        knowledges.store.put(kn.id(), kn);
        store(TestData.agedEntity("ent_old", kn.id(), "old", NOW.minus(Duration.ofDays(20)), null));

        sweeper(null, Duration.ofDays(14)).sweep(NOW);

        Assertions.assertEquals(EntityStatus.DELETED, entities.store.get("ent_old").status());
    }
}
