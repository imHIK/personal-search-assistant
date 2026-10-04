package io.personalassistant.agent.prompt;

/**
 * @param contextChars character budget for the sources block; 0 means unbounded
 * @param maxSources cap on sources; 0 means as many as the budget fits
 * @param annotatesArray key of the JSON reply's array that describes the sources one by one, each naming its
 *                       source in a 1-based, positional {@code source} field; null when the reply is read
 *                       whole
 */
public record TaskSpec(
        String id,
        String promptId,
        String llmProfile,
        int contextChars,
        int maxSources,
        SourceText sourceText,
        io.personalassistant.agent.llm.LlmProvider.ResponseFormat responseFormat,
        String annotatesArray) {

    public enum SourceText { CHUNK, ENTITY }

    public TaskSpec(String id, String promptId, String llmProfile, int contextChars, int maxSources) {
        this(id, promptId, llmProfile, contextChars, maxSources, SourceText.CHUNK,
                io.personalassistant.agent.llm.LlmProvider.ResponseFormat.TEXT, null);
    }

    public TaskSpec(String id, String promptId, String llmProfile, int contextChars, int maxSources,
                    SourceText sourceText,
                    io.personalassistant.agent.llm.LlmProvider.ResponseFormat responseFormat) {
        this(id, promptId, llmProfile, contextChars, maxSources, sourceText, responseFormat, null);
    }

    public TaskSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TaskSpec id must not be blank");
        }
        if (promptId == null || promptId.isBlank()) {
            promptId = id;
        }
        if (llmProfile == null || llmProfile.isBlank()) {
            llmProfile = "default";
        }
        if (contextChars < 0) {
            contextChars = 0;
        }
        if (maxSources < 0) {
            maxSources = 0;
        }
        if (sourceText == null) {
            sourceText = SourceText.CHUNK;
        }
        if (responseFormat == null) {
            responseFormat = io.personalassistant.agent.llm.LlmProvider.ResponseFormat.TEXT;
        }
        if (annotatesArray != null && annotatesArray.isBlank()) {
            annotatesArray = null;
        }
    }
}
