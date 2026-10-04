package io.personalassistant.common.concurrency;

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
