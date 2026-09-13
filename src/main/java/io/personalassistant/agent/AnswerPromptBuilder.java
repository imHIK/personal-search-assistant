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

/**
 * Assembles the grounded prompt handed to the LLM: renders the task's {@link PromptTemplate} and packs
 * the retrieved chunks into a budgeted sources block.
 *
 * <p>The prompt <em>text</em> is not here — it lives in {@code config/prompts.json}, keyed by task. This
 * class owns the assembly logic, which is the part with behaviour worth testing; the wording is content
 * and belongs in a file that can be categorised, overridden and eventually user-edited.
 *
 * <p>The context is <em>budgeted</em> rather than silently clipped. The original implementation passed
 * each hit's 280-char display snippet, so the model saw roughly a quarter of every chunk with no
 * indication anything was missing — it would then reproduce a partial list and report the unseen rows as
 * absent from the source. Here the full chunk text is used, sources are filled in rank order until the
 * character budget is spent, and a source that had to be cut carries {@link #TRUNCATION_MARKER} so the
 * model can say what it could not see instead of concluding the data does not exist.
 *
 * <p>Budgets arrive on the {@link TaskSpec} rather than from config fields, because they are per-task:
 * answering and a future summarisation want different values, and a single global key cannot express
 * both. That also keeps this class stateless, so a test builds a {@code TaskSpec} and calls it directly.
 */
@ApplicationScoped
public class AnswerPromptBuilder {

    /** Appended to a source whose text did not fit the budget. Referenced by the system prompt. */
    static final String TRUNCATION_MARKER = " […truncated]";

    /** Fence around each source's text, so document content cannot be read as instructions. */
    private static final String FENCE = "\"\"\"";

    /**
     * Below this many characters a source is not worth including at all — a stub that ends mid-sentence
     * costs budget and tells the model nothing, so the remaining budget is left unspent instead.
     */
    private static final int MIN_SOURCE_CHARS = 200;

    private final PromptCatalog catalog;
    private final FieldSets fieldSets;

    /**
     * Injectable so the date in the prompt is assertable. Not a constant: {@code today} is part of the
     * prompt's meaning, and a test that cannot pin it cannot check the instruction is there.
     */
    Clock clock = Clock.systemDefaultZone();

    @Inject
    public AnswerPromptBuilder(PromptCatalog catalog, FieldSets fieldSets) {
        this.catalog = catalog;
        this.fieldSets = fieldSets;
    }

    /**
     * The instruction half of the prompt. Every clause here answers to an observed failure rather than
     * to general prompt-engineering taste:
     *
     * <ul>
     *   <li><strong>Today's date.</strong> Without it "this year", "last quarter" and "recently" are
     *       unanswerable, and the model resolves them by guessing from whatever the sources happen to
     *       mention. The query that prompted all of this was "give me all the holidays this year".</li>
     *   <li><strong>Completeness.</strong> The model previously listed the rows it could see, stopped,
     *       and then stated the rest were absent from the sources. Reproducing every row present and
     *       distinguishing "not in the sources" from "cut off" is the correction.</li>
     *   <li><strong>Truncation.</strong> Names {@link #TRUNCATION_MARKER} explicitly so a budget cut is
     *       reported as a cut rather than treated as the end of the data.</li>
     *   <li><strong>Markdown, constrained.</strong> A fixed subset — headings, bold, lists, tables — so
     *       tabular answers render as tables and the console needs only a small renderer.</li>
     *   <li><strong>Grouped citations.</strong> {@code [1,2,3]} instead of {@code [1][2][3]}: fewer
     *       inline markers to read, and the console expands the group back into separate links. The
     *       prompt also pins the bracket <em>shape</em> to ASCII, because the console's parser keys
     *       off it — hosted models like to emit the fullwidth CJK pair, which used to land in the
     *       answer as dead text. The console now accepts both, so this is belt and braces.</li>
     *   <li><strong>Fences.</strong> Source text is untrusted input. Telling the model the fenced
     *       regions are data, not instructions, is what stops a document containing prompt-like prose
     *       from steering the answer.</li>
     * </ul>
     */
    String system(TaskSpec task) {
        return system(task, Map.of());
    }

    /**
     * @param variables values for the prompt's own declared variables, supplied by the caller. The
     *                  framework's own are added in {@link #values} and win, so a caller cannot
     *                  redefine what the assembly code emits
     */
    String system(TaskSpec task, Map<String, String> variables) {
        return catalog.promptForTask(task.id())
                .renderSystem(values(task, null, List.of(), Map.of(), variables));
    }

    String user(TaskSpec task, SearchQuery query, List<SearchHit> hits) {
        return user(task, query, hits, Map.of());
    }

    /**
     * @param textByChunkId text to render in place of a hit's own, keyed by chunk id. Empty for
     *                      answering; populated when the task asks for whole-entity sources, which
     *                      {@code SourceTexts} resolves so this class stays a pure assembler
     */
    String user(TaskSpec task, SearchQuery query, List<SearchHit> hits,
                Map<String, String> textByChunkId) {
        return catalog.promptForTask(task.id())
                .renderUser(values(task, query, hits, textByChunkId, Map.of()));
    }

    /** Both halves of one prompt, rendered together from a single set of values. */
    record Rendered(String system, String user) { }

    /**
     * Render both messages of a prompt supplied by the caller.
     *
     * <p>A user-written task has no entry in the bundled catalogue — its prompt is either a shipped
     * wrapper rendered around the user's instruction, or text the user wrote outright — so the template
     * arrives here rather than being looked up. {@code TaskLibrary} is what resolves the two cases.
     *
     * <p>Both halves render from the <em>same</em> map on purpose. They used to be rendered from two
     * disjoint ones — {@code today}, {@code fence} and {@code truncationMarker} for the system message,
     * {@code query} and {@code sources} for the user message — so a placeholder written into the other
     * half threw at render time. That is a reasonable mistake for someone writing a task's prompt in the
     * console to make, and for a scheduled digest it surfaces hours later as a failed run rather than
     * while its author is present to read the error. Assembling the sources block once and offering
     * every value to both renders costs nothing and removes the trap; the console can then present each
     * placeholder where it usually belongs without that placement being load-bearing.
     */
    Rendered render(PromptTemplate prompt, TaskSpec task, SearchQuery query, List<SearchHit> hits,
                    Map<String, String> textByChunkId, Map<String, String> variables) {
        Map<String, String> values = values(task, query, hits, textByChunkId, variables);
        return new Rendered(prompt.renderSystem(values), prompt.renderUser(values));
    }

    /**
     * The caller's variables first, then the framework's own, which overwrite them.
     *
     * <p>The fence and the truncation marker are supplied here rather than restated in the JSON: the
     * prompt describes them, but the assembly code below is what actually emits them, so a single
     * source of truth keeps the description and the output from drifting apart.
     */
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
     * Render the hits as a numbered source block. Numbering is 1-based and positional in {@code hits},
     * which is the contract the {@code [n]} citations and the console's citation parser both rely on —
     * so a hit dropped for budget reasons must still consume its number.
     */
    private String sources(TaskSpec task, List<SearchHit> hits, Map<String, String> textByChunkId) {
        int limit = task.maxSources() > 0 ? Math.min(task.maxSources(), hits.size()) : hits.size();
        int remaining = task.contextChars() > 0 ? task.contextChars() : Integer.MAX_VALUE;
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < limit; i++) {
            SearchHit hit = hits.get(i);
            String header = header(i + 1, hit);
            String override = textByChunkId.get(hit.chunkId());
            String text = override != null ? override : (hit.text() == null ? "" : hit.text());
            // The fences are part of what the budget has to pay for, or a tight budget overruns it.
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

    /**
     * {@code [n] Title · locator} — kept deliberately thin. The uri, ids and score were considered and
     * left out: the console already links the source, and every extra field spends budget that would
     * otherwise hold source text.
     */
    private String header(int number, SearchHit hit) {
        StringBuilder header = new StringBuilder("[").append(number).append("] ");
        header.append(hit.title() == null || hit.title().isBlank() ? "Untitled source" : hit.title());
        String locator = locator(hit.metadata());
        if (!locator.isEmpty()) {
            header.append(" · ").append(locator);
        }
        return header.append('\n').toString();
    }

    /**
     * The {@code promptLocator} field set, in file order, skipping anything the chunk does not carry.
     * A locator earns its context budget because it lets the answer say <em>where</em> a fact came from;
     * ids, scores and checksums give the model nothing to reason with and are excluded by not being
     * listed. Which keys count is config, not a constant — see {@link FieldSets} for why.
     */
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
