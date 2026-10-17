package io.personalassistant.agent.llm;

/** The vendor answered 5xx or not at all: worth retrying later, unlike a refusal or an unusable reply. */
public class LlmUnavailableException extends IllegalStateException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
