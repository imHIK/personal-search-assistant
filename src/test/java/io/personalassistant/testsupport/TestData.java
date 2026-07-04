package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;
import java.util.Map;

/** Builders for domain records used across tests. */
public final class TestData {

    private TestData() {
    }

    public static Knowledge knowledge(String id, SourceType type, Instant anchor, Map<String, Object> inputs) {
        Instant now = Instant.now();
        return new Knowledge(id, "test-" + id,
                Knowledge.ConnectorDetails.of(type, Map.of()), inputs,
                Knowledge.Config.defaults(), anchor, null, KnowledgeStatus.ACTIVE, null,
                Knowledge.Stats.zero(), now, now, 0L);
    }

    /** A knowledge bound to a specific connection id (for connection-resolution tests). */
    public static Knowledge knowledgeWithConnection(String id, SourceType type, String connectionId,
                                                    Instant anchor, Map<String, Object> inputs) {
        Instant now = Instant.now();
        return new Knowledge(id, "test-" + id,
                new Knowledge.ConnectorDetails(type, connectionId, Map.of()), inputs,
                Knowledge.Config.defaults(), anchor, null, KnowledgeStatus.ACTIVE, null,
                Knowledge.Stats.zero(), now, now, 0L);
    }

    /** A simple ACTIVE connection for a type (default flag as given). */
    public static Connection connection(String id, SourceType type, boolean isDefault,
                                        Map<String, Object> auth) {
        Instant now = Instant.now();
        return new Connection(id, "test-" + id, type, auth, Map.of(), isDefault,
                ConnectionStatus.ACTIVE, null, now, now);
    }

    /** A knowledge whose config carries an explicit custom {@link Knowledge.ScheduleSettings}. */
    public static Knowledge knowledgeWithSchedule(String id, SourceType type,
                                                  Knowledge.ScheduleSettings schedule) {
        Instant now = Instant.now();
        Knowledge.Config defaults = Knowledge.Config.defaults();
        Knowledge.Config config = new Knowledge.Config(schedule, defaults.webhookSettings(), defaults.backfill());
        return new Knowledge(id, "test-" + id,
                Knowledge.ConnectorDetails.of(type, Map.of()), Map.of(),
                config, now, null, KnowledgeStatus.ACTIVE, null,
                Knowledge.Stats.zero(), now, now, 0L);
    }

    public static Cursor cursor(String knowledgeId, String iterableId, CursorDirection direction, SourceType type) {
        return cursor(knowledgeId, iterableId, Map.of(), direction, type);
    }

    public static Cursor cursor(String knowledgeId, String iterableId, Map<String, Object> attributes,
                                CursorDirection direction, SourceType type) {
        return new Cursor("cur_" + knowledgeId + iterableId + direction, knowledgeId, iterableId,
                attributes, direction, CursorPosition.start(), CursorStatus.AVAILABLE, null, Cursor.Retry.zero(),
                Cursor.Stats.zero(), new Cursor.Scope(type));
    }

    /** An ingested entity in a specific iterable (for cascade-delete / reconcile tests). */
    public static Entity entityInIterable(String id, String knowledgeId, String iterableId, String externalId) {
        Instant now = Instant.now();
        return new Entity(id, knowledgeId, iterableId, EntityType.MESSAGE, externalId,
                Map.of(), Entity.Content.ofText("body"), Map.of("title", externalId, "uri", "test://" + externalId),
                "sha256:" + externalId, EntityStatus.INGESTED, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), now, now, 0L);
    }

    public static Entity ingestedText(String id, String knowledgeId, String externalId, String text) {
        Instant now = Instant.now();
        return new Entity(id, knowledgeId, "root", EntityType.MESSAGE, externalId,
                Map.of(), Entity.Content.ofText(text), Map.of("title", externalId, "uri", "test://" + externalId),
                "sha256:" + externalId, EntityStatus.INGESTED, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), now, now, 0L);
    }

    public static Entity ingestedFile(String id, String knowledgeId, String externalId, String fileRef, String contentType) {
        Instant now = Instant.now();
        return new Entity(id, knowledgeId, "root", EntityType.FILE, externalId,
                Map.of("contentType", contentType), Entity.Content.ofFile(fileRef),
                Map.of("title", externalId, "uri", "file://" + externalId),
                "sha256:" + externalId, EntityStatus.INGESTED, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), now, now, 0L);
    }
}
