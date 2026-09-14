package io.personalassistant.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A user-written LLM task: an instruction to run over a digest's results, stored in Mongo rather than
 * shipped in {@code config/prompts.json}.
 *
 * <p><strong>Why this exists alongside the bundled catalogue.</strong> That catalogue is loaded eagerly
 * and validated at boot, because a missing or malformed built-in prompt would mean answering with no
 * instructions — a silent quality failure that must stop the application. User tasks arrive after boot
 * and cannot share that posture: a bad one must cost its own run, never startup. So the two are layered
 * rather than merged, and this record is the half that can be created, edited and deleted at runtime.
 * {@code TaskLibrary} resolves an id to one or the other and converts this into the agent's own
 * {@code TaskSpec} — the conversion lives there, not here, because the domain does not depend on the
 * agent adapter.
 *
 * <p><strong>Why {@link Mode#SIMPLE} is not just a smaller prompt box.</strong> Every shipped prompt
 * carries clauses that are not stylistic — that fenced source text is data and never instructions, that
 * the model must not draw on its own knowledge. A user writing a raw system prompt would have to
 * remember those, and a forgotten one turns any indexed document into a way to steer the model. In
 * SIMPLE mode the user supplies only an {@link #instruction} and the shape of the reply; the clauses
 * live in a shipped wrapper prompt that renders around it, so the defence is structural rather than
 * remembered. {@link Mode#RAW} hands that responsibility back deliberately and is surfaced in the
 * console only under the technical-details toggle.
 *
 * @param id           stable id, {@code task_...}. Generated, never user-supplied: the prefix is what
 *                     guarantees a user task cannot shadow a built-in slug like {@code answer}
 * @param name         human label, shown wherever a task is picked
 * @param description  what this task is for, shown as the picker's hint
 * @param mode         whether the prompt is assembled from an instruction or written out in full
 * @param instruction  SIMPLE only: what the model should do with each batch of results
 * @param output       SIMPLE only: whether the reply is one summary or a note per result
 * @param fields       SIMPLE + {@link Output#PER_ITEM} only: what to record about each result. These
 *                     become the JSON contract the model is held to, and then the annotations rendered
 *                     on each item in the console
 * @param system       RAW only: the system message, verbatim
 * @param user         RAW only: the user message, verbatim
 * @param llmProfile   which {@code app.llm.profile.<name>.*} entry runs it
 * @param sourceText   whether the model judges the matching passage or the whole document
 * @param contextChars character budget for the assembled sources block; 0 means unbounded
 * @param maxSources   cap on results handed to the model; 0 means "as many as the budget fits"
 */
public record Task(
        String id,
        String name,
        String description,
        Mode mode,
        String instruction,
        Output output,
        List<Field> fields,
        String system,
        String user,
        String llmProfile,
        SourceText sourceText,
        int contextChars,
        int maxSources,
        Instant createdAt,
        Instant updatedAt) {

    /** How the prompt is assembled. */
    public enum Mode { SIMPLE, RAW }

    /** What shape of reply the task asks for. */
    public enum Output { SUMMARY, PER_ITEM }

    /**
     * Which body of text the model judges. Mirrors the agent's own {@code TaskSpec.SourceText} by name
     * rather than referencing it: the domain does not depend on the agent adapter, and
     * {@code TaskLibrary} maps between the two.
     */
    public enum SourceText { CHUNK, ENTITY }

    /** The type of one recorded field, which decides how the console renders it. */
    public enum FieldType { NUMBER, TEXT }

    /**
     * One thing the model records about each result.
     *
     * @param name        the JSON key, and the annotation key on the run item. Must not be
     *                    {@code source}, which the framework owns
     * @param type        {@code NUMBER} renders as a score badge, {@code TEXT} as a line
     * @param description what the model should put here, rendered into the contract
     * @param optional    whether the model may reply {@code null}. An optional field that comes back
     *                    null is omitted from the item entirely rather than rendered empty
     */
    public record Field(String name, FieldType type, String description, boolean optional) {

        public Field {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description.trim();
            type = type == null ? FieldType.TEXT : type;
        }
    }

    /** The array key the per-item wrapper asks the model to reply with. */
    public static final String ITEMS_ARRAY = "items";

    /** The field carrying the source number. Reserved: it is how a reply is joined back to a result. */
    public static final String SOURCE_FIELD = "source";

    /** Shipped wrapper prompts that render a SIMPLE task's instruction with the safety clauses. */
    public static final String PER_ITEM_PROMPT = "user-task-per-item";
    public static final String SUMMARY_PROMPT = "user-task-summary";

    /** Default budget for a user task — generous enough for ~10 postings, small enough to stay cheap. */
    public static final int DEFAULT_CONTEXT_CHARS = 24000;

    /** Clamping mirrors the agent's TaskSpec: any path producing a task is sanitised, wherever from. */
    public Task {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        mode = mode == null ? Mode.SIMPLE : mode;
        output = output == null ? Output.SUMMARY : output;
        sourceText = sourceText == null ? SourceText.CHUNK : sourceText;
        instruction = instruction == null ? null : instruction.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (llmProfile == null || llmProfile.isBlank()) {
            llmProfile = "lite";
        }
        // Negative is meaningless and would make the budget arithmetic drop every source silently.
        if (contextChars < 0) {
            contextChars = 0;
        }
        if (maxSources < 0) {
            maxSources = 0;
        }
    }

    /**
     * Whether this task asks for a note per result rather than one summary.
     *
     * <p>Everything downstream is <em>derived</em> from this rather than stored beside it: it is what
     * makes the reply JSON, what names the array to read, and what lets the console render annotations.
     * Storing those separately would allow a task that asks for per-item fields in prose — a shape
     * nothing can parse.
     */
    public boolean perItem() {
        return mode == Mode.SIMPLE && output == Output.PER_ITEM;
    }

    /** Which shipped wrapper renders this task, or the task's own id when it carries its own prompt. */
    public String promptId() {
        if (mode != Mode.SIMPLE) {
            return id;
        }
        return perItem() ? PER_ITEM_PROMPT : SUMMARY_PROMPT;
    }

    /**
     * The reply contract handed to the per-item wrapper as {@code {{outputContract}}}.
     *
     * <p>Generated rather than written by the user, which is the point: the console records whatever
     * fields come back, so the shape the model is asked for and the shape the console can render are
     * the same object by construction. A hand-written contract would drift from the field list the
     * moment either changed.
     */
    public String outputContract() {
        StringBuilder example = new StringBuilder("{\"" + ITEMS_ARRAY + "\": [{\"" + SOURCE_FIELD + "\": 1");
        StringBuilder detail = new StringBuilder();
        for (Field field : fields) {
            example.append(", \"").append(field.name()).append("\": ")
                    .append(field.type() == FieldType.NUMBER ? "0" : "\"...\"");
            detail.append("- ").append(field.name()).append(": ")
                    .append(field.type() == FieldType.NUMBER ? "a number" : "text");
            if (field.optional()) {
                detail.append(", or null if there is none");
            }
            if (!field.description().isEmpty()) {
                detail.append(". ").append(field.description());
            }
            detail.append('\n');
        }
        example.append("}]}");

        return "Reply with JSON only, in exactly this shape:\n" + example + "\n\n"
                + "\"" + SOURCE_FIELD + "\" is the bracketed number of the result you are describing. "
                + "Include every result exactly once.\n\n"
                + "For each result, record:\n" + detail;
    }

    /**
     * Why this task cannot be saved, or an empty list.
     *
     * <p>Checked here rather than at render time on purpose. {@code PromptTemplate} throws on an
     * unresolved {@code {{placeholder}}}, so a typo in an instruction would otherwise surface hours
     * later as a failed scheduled run. Every problem below is one a user can make in the console, and
     * every one of them is cheaper as a 400 than as a broken digest.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name.isEmpty()) {
            problems.add("name must not be blank");
        }
        if (mode == Mode.SIMPLE) {
            if (instruction == null || instruction.isEmpty()) {
                problems.add("instruction must not be blank");
            }
            // The wrapper substitutes the instruction in verbatim, so a {{...}} in it would either
            // reach the model as a literal or collide with a framework variable.
            checkNoPlaceholders(instruction, "instruction", problems);
            if (output == Output.PER_ITEM) {
                if (fields.isEmpty()) {
                    problems.add("notes on each result need at least one field");
                }
                List<String> seen = new ArrayList<>();
                for (Field field : fields) {
                    String where = "field \"" + field.name() + "\"";
                    if (!field.name().matches("[a-zA-Z][a-zA-Z0-9_]*")) {
                        problems.add(where + " must be a letter followed by letters, digits or _");
                    }
                    if (field.name().toLowerCase(Locale.ROOT).equals(SOURCE_FIELD)) {
                        problems.add("\"" + SOURCE_FIELD + "\" is reserved: it is how a reply is "
                                + "matched back to a result");
                    }
                    if (!seen.add(field.name()) && seen.indexOf(field.name()) != seen.size() - 1) {
                        problems.add(where + " is listed twice");
                    }
                    checkNoPlaceholders(field.description(), where, problems);
                }
            }
        } else {
            if (system == null || system.isBlank()) {
                problems.add("a raw task needs a system message");
            }
            // Without {{sources}} the model is handed the instruction and nothing to apply it to, and
            // the run "succeeds" with an answer drawn from the model's own knowledge — the exact
            // ungrounded failure the shipped prompts are written to prevent.
            if (user == null || !user.contains("{{sources}}")) {
                problems.add("the user message must contain {{sources}}, or the model is given "
                        + "nothing to work from");
            }
        }
        return problems;
    }

    private static void checkNoPlaceholders(String text, String where, List<String> problems) {
        if (text != null && text.contains("{{")) {
            problems.add(where + " must not contain {{...}} — it is inserted into the prompt verbatim");
        }
    }

    public Task withTimestamps(Instant created, Instant updated) {
        return new Task(id, name, description, mode, instruction, output, fields, system, user,
                llmProfile, sourceText, contextChars, maxSources, created, updated);
    }

    public Task withId(String newId) {
        return new Task(newId, name, description, mode, instruction, output, fields, system, user,
                llmProfile, sourceText, contextChars, maxSources, createdAt, updatedAt);
    }
}
