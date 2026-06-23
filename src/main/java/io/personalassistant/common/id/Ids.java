package io.personalassistant.common.id;

import java.util.UUID;

/**
 * Central factory for the opaque, prefixed identifiers used across the domain. Prefixes make
 * ids self-describing in logs and stores ({@code kn_}, {@code cur_}, {@code ent_}) while the
 * body stays an opaque random token. Keeping id shape in one place avoids leaking store
 * specifics and keeps formats consistent.
 */
public final class Ids {

    public static final String KNOWLEDGE_PREFIX = "kn_";
    public static final String CURSOR_PREFIX = "cur_";
    public static final String ENTITY_PREFIX = "ent_";

    private Ids() {
    }

    public static String knowledge() {
        return KNOWLEDGE_PREFIX + token();
    }

    public static String cursor() {
        return CURSOR_PREFIX + token();
    }

    public static String entity() {
        return ENTITY_PREFIX + token();
    }

    /**
     * Deterministic cursor id for a {@code (knowledgeId, iterableId, direction)} triple so the
     * same logical cursor is never created twice (idempotent discovery / re-arm).
     */
    public static String cursorFor(String knowledgeId, String iterableId, String direction) {
        return CURSOR_PREFIX + knowledgeId + ":" + iterableId + ":" + direction;
    }

    /** Derived, stable chunk id so re-indexing the same chunk overwrites rather than dupes. */
    public static String chunk(String entityId, int ordinal) {
        return entityId + "_" + ordinal;
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
