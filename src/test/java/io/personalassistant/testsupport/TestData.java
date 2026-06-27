package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
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
                new Knowledge.ConnectorDetails(type, Map.of()), inputs,
                Knowledge.Config.defaults(), anchor, KnowledgeStatus.ACTIVE, null,
                Knowledge.Stats.zero(), now, now);
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
                Entity.Retry.zero(), now, now);
    }

    public static Entity ingestedText(String id, String knowledgeId, String externalId, String text) {
        Instant now = Instant.now();
        return new Entity(id, knowledgeId, "root", EntityType.MESSAGE, externalId,
                Map.of(), Entity.Content.ofText(text), Map.of("title", externalId, "uri", "test://" + externalId),
                "sha256:" + externalId, EntityStatus.INGESTED, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), now, now);
    }

    public static Entity ingestedFile(String id, String knowledgeId, String externalId, String fileRef, String contentType) {
        Instant now = Instant.now();
        return new Entity(id, knowledgeId, "root", EntityType.FILE, externalId,
                Map.of("contentType", contentType), Entity.Content.ofFile(fileRef),
                Map.of("title", externalId, "uri", "file://" + externalId),
                "sha256:" + externalId, EntityStatus.INGESTED, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), now, now);
    }
}
