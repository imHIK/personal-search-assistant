package io.personalassistant.agent.llm;

import java.util.List;

public interface LlmProvider {

    /** Matched against app.llm.provider to select this implementation. */
    String providerId();

    /** JSON_OBJECT is a hint: callers still parse defensively (see JsonReplies). */
    String complete(LlmProfile profile, ResponseFormat format, String system, List<Message> messages);

    default String complete(LlmProfile profile, String system, List<Message> messages) {
        return complete(profile, ResponseFormat.TEXT, system, messages);
    }

    enum ResponseFormat { TEXT, JSON_OBJECT }

    /** @param role "user" or "assistant" */
    record Message(String role, String content) {}
}
