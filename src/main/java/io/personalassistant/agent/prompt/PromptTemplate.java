package io.personalassistant.agent.prompt;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One prompt, identified by what it is <em>for</em> — {@code answer}, {@code rerank}, {@code rephrase} —
 * never by which model runs it.
 *
 * <p>That distinction is the whole point of this type. A prompt tied to a model cannot be reused when the
 * model changes and cannot be shared by two features on different models; the model belongs in an
 * {@code LlmProfile}, which a {@link TaskSpec} references by name. Nothing here knows or can know which
 * model will receive the text.
 *
 * <p>Held as data loaded from {@code config/prompts.json} rather than as a Java constant so prompts can
 * be categorised, listed, overridden by an external file, and eventually edited by a user — none of which
 * is possible for a string baked into a method.
 *
 * @param id          the task-shaped key this prompt is filed under
 * @param description why this prompt exists, for whoever edits the file next
 * @param system      the system/instruction message, with {@code {{variable}}} placeholders
 * @param user        the user message template, with {@code {{variable}}} placeholders
 * @param variables   placeholders the system message declares; validated at startup against the text
 */
public record PromptTemplate(
        String id,
        String description,
        String system,
        String user,
        List<String> variables) {

    /** {@code {{name}}} — one form, deliberately. A template engine would be disproportionate here. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public PromptTemplate {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }

    /** Render the system message. */
    public String renderSystem(Map<String, String> values) {
        return render(system, values, id + ".system");
    }

    /** Render the user message. */
    public String renderUser(Map<String, String> values) {
        return render(user, values, id + ".user");
    }

    /**
     * Substitute every {@code {{name}}} from {@code values}.
     *
     * <p>An unresolved placeholder throws rather than being passed through. A prompt reaching a model
     * with a literal {@code {{today}}} in it is a silent quality failure — the model sees an instruction
     * it cannot follow and the answer degrades in a way no error surfaces. Typos in a hand-edited config
     * file are exactly the expected cause, so this must be loud.
     */
    private static String render(String template, Map<String, String> values, String where) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = values == null ? null : values.get(name);
            if (value == null) {
                throw new IllegalStateException("Prompt " + where + " uses {{" + name
                        + "}} but no value was supplied. Known values: "
                        + (values == null ? "none" : values.keySet()));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Placeholder names actually appearing in the system and user text, for startup validation. */
    List<String> placeholdersUsed() {
        return java.util.stream.Stream.of(system, user)
                .filter(s -> s != null && !s.isEmpty())
                .flatMap(s -> PLACEHOLDER.matcher(s).results().map(r -> r.group(1)))
                .distinct()
                .toList();
    }
}
