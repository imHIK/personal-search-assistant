package io.personalassistant.common.id;

import java.util.UUID;

public final class Ids {

    public static final String KNOWLEDGE_PREFIX = "kn_";
    public static final String CONNECTION_PREFIX = "conn_";
    public static final String CURSOR_PREFIX = "cur_";
    public static final String ENTITY_PREFIX = "ent_";
    public static final String DISCOVERY_PREFIX = "dsc_";
    public static final String DIGEST_PREFIX = "dig_";
    public static final String DIGEST_RUN_PREFIX = "run_";
    public static final String TASK_PREFIX = "task_";
    public static final String CHANNEL_PREFIX = "chn_";
    public static final String DELIVERY_PREFIX = "dlv_";

    private Ids() {
    }

    public static String knowledge() {
        return KNOWLEDGE_PREFIX + token();
    }

    public static String connection() {
        return CONNECTION_PREFIX + token();
    }

    public static String cursor() {
        return CURSOR_PREFIX + token();
    }

    public static String entity() {
        return ENTITY_PREFIX + token();
    }

    public static String digest() {
        return DIGEST_PREFIX + token();
    }

    public static String digestRun() {
        return DIGEST_RUN_PREFIX + token();
    }

    /** The prefix separates user tasks from bundled slugs, so a user task can never shadow a built-in. */
    public static String task() {
        return TASK_PREFIX + token();
    }

    public static String channel() {
        return CHANNEL_PREFIX + token();
    }

    public static String delivery() {
        return DELIVERY_PREFIX + token();
    }

    /** Deterministic, so the same logical cursor is never created twice. */
    public static String cursorFor(String knowledgeId, String iterableId, String direction) {
        return CURSOR_PREFIX + knowledgeId + ":" + iterableId + ":" + direction;
    }

    public static String discoveryFor(String knowledgeId, String direction) {
        return DISCOVERY_PREFIX + knowledgeId + ":" + direction;
    }

    /** Stable, so re-indexing a chunk overwrites it instead of duplicating it. */
    public static String chunk(String entityId, int ordinal) {
        return entityId + "_" + ordinal;
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
