package io.personalassistant.common.concurrency;

/**
 * A concurrency ceiling for one scope: at most {@code max} live permits may occupy
 * {@code key} at once. Compose several (global + connector + knowledge) to throttle at
 * multiple levels so one source can't starve the rest.
 *
 * @param key the scope identifier, e.g. {@code "connector:SLACK"} or {@code "knowledge:kn_1"}
 * @param max maximum number of concurrent permits allowed in this scope
 */
public record ScopeLimit(String key, int max) {

    public static final String GLOBAL = "global";

    public static ScopeLimit global(int max) {
        return new ScopeLimit(GLOBAL, max);
    }

    public static ScopeLimit connector(String connectorType, int max) {
        return new ScopeLimit("connector:" + connectorType, max);
    }

    public static ScopeLimit knowledge(String knowledgeId, int max) {
        return new ScopeLimit("knowledge:" + knowledgeId, max);
    }
}
