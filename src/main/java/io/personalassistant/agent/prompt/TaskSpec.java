package io.personalassistant.agent.prompt;

/**
 * A resolved LLM task: which prompt, which model profile, and the knobs that only make sense for
 * <em>this</em> task.
 *
 * <p><strong>Why these knobs live here and not in {@code application.properties}.</strong> A context
 * budget and a source cap are meaningless globally — they belong to answering. A future {@code summarise}
 * task wants a smaller budget and fewer sources; a {@code rerank} task wants neither. As flat keys
 * ({@code app.agent.context-chars}) there is exactly one value for the whole application, so the second
 * task that needs a different one forces either a rename or a duplicate key. Filed per task, adding a
 * task is a config edit.
 *
 * <p>Model selection is referenced, never restated: {@code llmProfile} names an entry in
 * {@code app.llm.profile.*} resolved by {@code LlmProfiles}. A task therefore cannot carry model
 * settings, which is what keeps prompts model-agnostic by construction.
 *
 * <p>Clamping lives in the compact constructor, following {@code ChunkingSpec}: any path that produces a
 * spec is sanitised, so a hand-edited config file cannot inject a negative budget into the agent.
 *
 * @param id           the task key, e.g. {@code answer}
 * @param promptId     which {@link PromptTemplate} to render
 * @param llmProfile   which {@code app.llm.profile.<name>.*} entry runs it
 * @param contextChars character budget for the assembled sources block; 0 means unbounded
 * @param maxSources   cap on cited sources; 0 means "as many as the budget fits"
 * @param sourceText   which text fills the sources block. {@code CHUNK} is the retrieved passage and is
 *                     right for answering, where the passage is what matched. {@code ENTITY} is the
 *                     whole document, for a task that must judge an item as a whole — scoring a job
 *                     posting against a profile is meaningless from one arbitrary slice of it. In
 *                     {@code ENTITY} mode hits are collapsed to one per entity, since several chunks of
 *                     one document would otherwise repeat that document verbatim
 * @param responseFormat reply shape asked of the endpoint. {@code JSON_OBJECT} is a hint, not a
 *                     guarantee — a caller still parses defensively
 * @param annotatesArray for a task whose JSON reply describes the sources one by one, the key of the
 *                     array carrying those descriptions; null for a task whose reply is read whole.
 *                     Each element names the source it describes in a {@code source} field, 1-based and
 *                     positional in the block the prompt was given, which is how a digest joins the
 *                     reply back onto the results it ran over. Declared per task for the same reason
 *                     the budgets are: it is a property of this reply's shape, not of the application
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

    /** Which body of text a source entry carries. */
    public enum SourceText { CHUNK, ENTITY }

    /** Convenience for the common answering shape: chunk text, plain-text reply. */
    public TaskSpec(String id, String promptId, String llmProfile, int contextChars, int maxSources) {
        this(id, promptId, llmProfile, contextChars, maxSources, SourceText.CHUNK,
                io.personalassistant.agent.llm.LlmProvider.ResponseFormat.TEXT, null);
    }

    /** Convenience for a task whose reply is read whole rather than per source. */
    public TaskSpec(String id, String promptId, String llmProfile, int contextChars, int maxSources,
                    SourceText sourceText,
                    io.personalassistant.agent.llm.LlmProvider.ResponseFormat responseFormat) {
        this(id, promptId, llmProfile, contextChars, maxSources, sourceText, responseFormat, null);
    }

    public TaskSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TaskSpec id must not be blank");
        }
        // A task with no prompt named falls back to its own id, which is the common 1:1 case.
        if (promptId == null || promptId.isBlank()) {
            promptId = id;
        }
        // A blank profile means "the provider's own configuration", which LlmProfiles renders as an
        // inherit-everything profile — a working outcome, not a reason to fail.
        if (llmProfile == null || llmProfile.isBlank()) {
            llmProfile = "default";
        }
        // Negative is meaningless and would make the budget arithmetic drop every source silently.
        if (contextChars < 0) {
            contextChars = 0;
        }
        if (maxSources < 0) {
            maxSources = 0;
        }
        // Both default to the pre-existing behaviour, so a task entry that names neither is unchanged.
        if (sourceText == null) {
            sourceText = SourceText.CHUNK;
        }
        if (responseFormat == null) {
            responseFormat = io.personalassistant.agent.llm.LlmProvider.ResponseFormat.TEXT;
        }
        // Blank is absent, matching every other optional text field here. A task that names an array
        // but replies in prose simply yields no annotations; that is the caller's degraded path, not
        // an error worth failing the catalogue over.
        if (annotatesArray != null && annotatesArray.isBlank()) {
            annotatesArray = null;
        }
    }
}
