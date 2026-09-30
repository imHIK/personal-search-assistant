package io.personalassistant.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A user-written LLM task stored in Mongo; bundled ones live in {@code config/prompts.json}. In SIMPLE mode
 * the user writes only an instruction and a reply shape, and a shipped wrapper prompt adds the safety clauses
 * (fenced source text is data, not instructions); RAW hands that responsibility back.
 *
 * @param id generated, never user-supplied: the prefix stops it shadowing a built-in
 * @param instruction SIMPLE only
 * @param output SIMPLE only
 * @param fields SIMPLE and PER_ITEM only: the JSON contract the model is held to, and the annotations shown
 *               on each item
 * @param system RAW only, verbatim
 * @param user RAW only, verbatim
 * @param contextChars 0 means unbounded
 * @param maxSources 0 means as many as the budget fits
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

    public enum Mode { SIMPLE, RAW }

    public enum Output { SUMMARY, PER_ITEM }

    /** Mirrors TaskSpec.SourceText by name: the domain does not depend on the agent adapter. */
    public enum SourceText { CHUNK, ENTITY }

    public enum FieldType { NUMBER, TEXT }

    /**
     * @param name must not be {@code source}, which the framework owns
     * @param type NUMBER renders as a score badge, TEXT as a line
     * @param optional a null reply omits the field from the item
     */
    public record Field(String name, FieldType type, String description, boolean optional) {

        public Field {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description.trim();
            type = type == null ? FieldType.TEXT : type;
        }
    }

    public static final String ITEMS_ARRAY = "items";

    /** Reserved: it is how a reply is joined back to a result. */
    public static final String SOURCE_FIELD = "source";

    public static final String PER_ITEM_PROMPT = "user-task-per-item";
    public static final String SUMMARY_PROMPT = "user-task-summary";

    public static final int DEFAULT_CONTEXT_CHARS = 24000;

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
        if (contextChars < 0) {
            contextChars = 0;
        }
        if (maxSources < 0) {
            maxSources = 0;
        }
    }

    /**
     * Everything downstream derives from this rather than being stored: it makes the reply JSON, names the
     * array and lets the console render annotations.
     */
    public boolean perItem() {
        return mode == Mode.SIMPLE && output == Output.PER_ITEM;
    }

    /** The shipped wrapper for a SIMPLE task; the task's own id for RAW. */
    public String promptId() {
        if (mode != Mode.SIMPLE) {
            return id;
        }
        return perItem() ? PER_ITEM_PROMPT : SUMMARY_PROMPT;
    }

    /**
     * Generated, not user-written, so the shape the model is asked for and the shape the console renders
     * cannot drift.
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

    /** Checked on save: a placeholder typo would otherwise fail a scheduled run hours later. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name.isEmpty()) {
            problems.add("name must not be blank");
        }
        if (mode == Mode.SIMPLE) {
            if (instruction == null || instruction.isEmpty()) {
                problems.add("instruction must not be blank");
            }
            // The wrapper substitutes the instruction verbatim, so a {{...}} in it would reach the model
            // literally or collide with a framework variable.
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
            // Without {{sources}} the model gets nothing to apply the instruction to and answers from its own
            // knowledge.
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
