package io.personalassistant.agent.prompt;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PromptTemplate(
        String id,
        String description,
        String system,
        String user,
        List<String> variables) {

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public PromptTemplate {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }

    public String renderSystem(Map<String, String> values) {
        return render(system, values, id + ".system");
    }

    public String renderUser(Map<String, String> values) {
        return render(user, values, id + ".user");
    }

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

    List<String> placeholdersUsed() {
        return java.util.stream.Stream.of(system, user)
                .filter(s -> s != null && !s.isEmpty())
                .flatMap(s -> PLACEHOLDER.matcher(s).results().map(r -> r.group(1)))
                .distinct()
                .toList();
    }
}
