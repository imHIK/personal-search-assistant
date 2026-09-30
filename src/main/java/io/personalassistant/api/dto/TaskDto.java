package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.Task;
import io.personalassistant.domain.service.Patched;
import io.personalassistant.domain.service.TaskService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Absent fields are left untouched and null clears them; {@link #patchOnto} reads the body directly to tell
 * the two apart.
 *
 * @param builtIn outbound only; true for everything shipped in {@code config/prompts.json}
 * @param mode {@code SIMPLE} builds the prompt from instruction and fields; {@code RAW} carries system and
 *             user verbatim
 * @param output {@code SUMMARY} or {@code PER_ITEM}, SIMPLE only. PER_ITEM makes the reply JSON and the
 *               results annotatable
 */
public record TaskDto(
        String id,
        String name,
        String description,
        Boolean builtIn,
        List<String> usedBy,
        Boolean usableInDigest,
        String mode,
        String instruction,
        String output,
        List<FieldDto> fields,
        String system,
        String user,
        String llmProfile,
        String sourceText,
        Integer contextChars,
        Integer maxSources,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * @param type {@code NUMBER} renders as a score badge, {@code TEXT} as a line
     * @param optional the model may reply null, and the field is then not shown
     */
    public record FieldDto(String name, String type, String description, Boolean optional) {}

    /** @throws IllegalArgumentException on an unknown enum name */
    public Task toDomain() {
        List<Task.Field> domainFields = new ArrayList<>();
        if (fields != null) {
            for (FieldDto field : fields) {
                domainFields.add(new Task.Field(field.name(),
                        enumOrNull(Task.FieldType.class, field.type()),
                        field.description(),
                        Boolean.TRUE.equals(field.optional())));
            }
        }
        return new Task(id, name, description,
                enumOrNull(Task.Mode.class, mode),
                instruction,
                enumOrNull(Task.Output.class, output),
                domainFields,
                system, user, llmProfile,
                enumOrNull(Task.SourceText.class, sourceText),
                contextChars == null ? 0 : contextChars,
                maxSources == null ? 0 : maxSources,
                createdAt, updatedAt);
    }

    public static TaskDto from(TaskService.LibraryEntry entry) {
        Task task = entry.task();
        if (task == null) {
            return new TaskDto(entry.id(), entry.name(), entry.description(), true, entry.usedBy(),
                    entry.usableInDigest(), null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }
        return new TaskDto(entry.id(), entry.name(), entry.description(), false, entry.usedBy(),
                entry.usableInDigest(), task.mode().name(), task.instruction(), task.output().name(),
                fieldsOf(task), task.system(), task.user(), task.llmProfile(),
                task.sourceText().name(), task.contextChars(), task.maxSources(),
                task.createdAt(), task.updatedAt());
    }

    public static TaskDto from(Task task) {
        return new TaskDto(task.id(), task.name(), task.description(), false, List.of(), true,
                task.mode().name(), task.instruction(), task.output().name(), fieldsOf(task),
                task.system(), task.user(), task.llmProfile(), task.sourceText().name(),
                task.contextChars(), task.maxSources(), task.createdAt(), task.updatedAt());
    }

    /**
     * Overlays only the keys this body carries: past this point an unset field and a defaulted one look
     * identical.
     *
     * @throws IllegalArgumentException on an unknown enum name or a wrong-typed field
     */
    public static Task patchOnto(Task existing, JsonNode body) {
        PatchBody patch = new PatchBody(body);
        String mode = patch.text("mode").orElse(existing.mode().name());
        String output = patch.text("output").orElse(existing.output().name());
        String sourceText = patch.text("sourceText").orElse(existing.sourceText().name());
        Patched<JsonNode> fields = patch.node("fields");
        return new Task(
                existing.id(),
                blankToCurrent(patch.text("name"), existing.name()),
                patch.text("description").orElse(existing.description()),
                enumOrNull(Task.Mode.class, mode),
                patch.text("instruction").orElse(existing.instruction()),
                enumOrNull(Task.Output.class, output),
                fields.present() ? parseFields(fields.value()) : existing.fields(),
                patch.text("system").orElse(existing.system()),
                patch.text("user").orElse(existing.user()),
                patch.text("llmProfile").orElse(existing.llmProfile()),
                enumOrNull(Task.SourceText.class, sourceText),
                zeroToCurrent(patch.integer("contextChars"), existing.contextChars()),
                zeroToCurrent(patch.integer("maxSources"), existing.maxSources()),
                existing.createdAt(),
                Instant.now());
    }

    private static String blankToCurrent(Patched<String> patched, String current) {
        String value = patched.orElse(current);
        return value == null || value.isBlank() ? current : value;
    }

    /** A cleared budget keeps the stored one: zero would silently drop every source. */
    private static int zeroToCurrent(Patched<Integer> patched, int current) {
        Integer value = patched.orElse(current);
        return value == null || value <= 0 ? current : value;
    }

    private static List<Task.Field> parseFields(JsonNode array) {
        if (array == null) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new IllegalArgumentException("fields must be an array");
        }
        List<Task.Field> out = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            if (!element.isObject()) {
                throw new IllegalArgumentException("fields must be an array of objects");
            }
            PatchBody field = new PatchBody(element);
            out.add(new Task.Field(
                    field.text("name").value(),
                    enumOrNull(Task.FieldType.class, field.text("type").value()),
                    field.text("description").value(),
                    Boolean.TRUE.equals(field.bool("optional").value())));
        }
        return out;
    }

    private static List<FieldDto> fieldsOf(Task task) {
        List<FieldDto> out = new ArrayList<>(task.fields().size());
        for (Task.Field field : task.fields()) {
            out.add(new FieldDto(field.name(), field.type().name(), field.description(),
                    field.optional()));
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value.trim().toUpperCase());
    }
}
