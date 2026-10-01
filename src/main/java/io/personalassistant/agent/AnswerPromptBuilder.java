package io.personalassistant.agent;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.PromptTemplate;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AnswerPromptBuilder {

    /** Appended to a source whose text did not fit the budget. Referenced by the system prompt. */
    static final String TRUNCATION_MARKER = " […truncated]";

    /** Fence around each source's text, so document content cannot be read as instructions. */
    static final String FENCE = "\"\"\"";

    /**
     * A source this short costs budget and tells the model nothing, so the rest of the budget is left
     * unspent.
     */
    private static final int MIN_SOURCE_CHARS = 200;

    private final PromptCatalog catalog;
    private final FieldSets fieldSets;

    Clock clock = Clock.systemDefaultZone();

    @Inject
    public AnswerPromptBuilder(PromptCatalog catalog, FieldSets fieldSets) {
        this.catalog = catalog;
        this.fieldSets = fieldSets;
    }

    String system(TaskSpec task) {
        return system(task, Map.of());
    }

    String system(TaskSpec task, Map<String, String> variables) {
        return catalog.promptForTask(task.id())
                .renderSystem(values(task, null, List.of(), Map.of(), variables));
    }

    String user(TaskSpec task, SearchQuery query, List<SearchHit> hits) {
        return user(task, query, hits, Map.of());
    }

    String user(TaskSpec task, SearchQuery query, List<SearchHit> hits,
                Map<String, String> textByChunkId) {
        return catalog.promptForTask(task.id())
                .renderUser(values(task, query, hits, textByChunkId, Map.of()));
    }

    record Rendered(String system, String user) { }

    /** Both halves render from one map, so any placeholder is legal in either half. */
    Rendered render(PromptTemplate prompt, TaskSpec task, SearchQuery query, List<SearchHit> hits,
                    Map<String, String> textByChunkId, Map<String, String> variables) {
        Map<String, String> values = values(task, query, hits, textByChunkId, variables);
        return new Rendered(prompt.renderSystem(values), prompt.renderUser(values));
    }

    /** Framework values overwrite the task's own, so a prompt cannot redefine what this class emits. */
    private Map<String, String> values(TaskSpec task, SearchQuery query, List<SearchHit> hits,
                                       Map<String, String> textByChunkId,
                                       Map<String, String> variables) {
        Map<String, String> values = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        values.put("today", LocalDate.now(clock).format(DateTimeFormatter.ISO_DATE));
        values.put("fence", FENCE);
        values.put("truncationMarker", TRUNCATION_MARKER.trim());
        values.put("query", query == null || query.text() == null ? "" : query.text());
        values.put("sources", sources(task, hits == null ? List.of() : hits,
                textByChunkId == null ? Map.of() : textByChunkId));
        return values;
    }

    /**
     * Numbering is 1-based and positional in hits: the [n] citations and the console's parser rely on it, so
     * a hit dropped for budget still consumes its number.
     */
    private String sources(TaskSpec task, List<SearchHit> hits, Map<String, String> textByChunkId) {
        int limit = task.maxSources() > 0 ? Math.min(task.maxSources(), hits.size()) : hits.size();
        int remaining = task.contextChars() > 0 ? task.contextChars() : Integer.MAX_VALUE;
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < limit; i++) {
            SearchHit hit = hits.get(i);
            String header = header(i + 1, hit);
            String override = textByChunkId.get(hit.chunkId());
            // groundingText: a result carries all its entity's matching chunks, and the answer needs each.
            String grounding = hit.groundingText();
            String text = override != null ? override : (grounding == null ? "" : grounding);
            int overhead = header.length() + (FENCE.length() + 1) * 2;
            int available = remaining - overhead;
            if (available < MIN_SOURCE_CHARS) {
                break;
            }
            out.append(header).append(FENCE).append('\n');
            if (text.length() <= available) {
                out.append(text);
                remaining -= overhead + text.length();
            } else {
                out.append(text, 0, available - TRUNCATION_MARKER.length()).append(TRUNCATION_MARKER);
                remaining -= overhead + available;
            }
            out.append('\n').append(FENCE).append("\n\n");
        }
        return out.toString();
    }

    private String header(int number, SearchHit hit) {
        StringBuilder header = new StringBuilder("[").append(number).append("] ");
        header.append(hit.title() == null || hit.title().isBlank() ? "Untitled source" : hit.title());
        String locator = locator(hit.metadata());
        if (!locator.isEmpty()) {
            header.append(" · ").append(locator);
        }
        return header.append('\n').toString();
    }

    private String locator(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String key : fieldSets.resolve(FieldSets.PROMPT_LOCATOR)) {
            Object value = metadata.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(key).append(' ').append(value);
        }
        return out.toString();
    }
}
