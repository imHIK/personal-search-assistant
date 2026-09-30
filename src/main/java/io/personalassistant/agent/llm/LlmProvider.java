package io.personalassistant.agent.llm;

import java.util.List;

public interface LlmProvider {

    /** Matched against app.llm.provider to select this implementation. */
    String providerId();

    String model();

    String complete(String system, List<Message> messages);

    /** Applies only the fields the profile sets. */
    default String complete(LlmProfile profile, String system, List<Message> messages) {
        return complete(system, messages);
    }

    /** JSON_OBJECT is a hint: callers still parse defensively (see JsonReplies). */
    default String complete(LlmProfile profile, ResponseFormat format, String system,
                            List<Message> messages) {
        return complete(profile, system, messages);
    }

    enum ResponseFormat { TEXT, JSON_OBJECT }

    /** @param role "user" or "assistant" */
    record Message(String role, String content) {}
}
