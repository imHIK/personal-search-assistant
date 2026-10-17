package io.personalassistant.agent;

import io.personalassistant.domain.model.Task;
import java.util.Map;

public interface MetadataEnricher {

    /**
     * Runs a METADATA task over one entity's text and returns its fields, coerced to their declared types.
     * Values the model left null, or that do not fit the field, are omitted.
     *
     * @throws io.personalassistant.common.ratelimit.RateLimitedException when the LLM's window is full and
     *                                                                    waiting would exceed the cap
     * @throws io.personalassistant.agent.llm.LlmUnavailableException when the vendor answered 5xx or not at all
     * @throws IllegalStateException when the reply is unusable or a required field has no usable value
     */
    Map<String, Object> enrich(Task task, String title, String text);
}
