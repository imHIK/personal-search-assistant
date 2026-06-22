package io.personalassistant.domain.model.search;

import java.util.List;

/**
 * Result of a search. {@code answer} is populated only when the query asked for
 * synthesis; {@code hits} are always the grounding/citation set.
 *
 * @param hits     ranked results
 * @param answer   optional LLM-synthesized answer, grounded in {@code hits}
 * @param tookMs   server-side latency
 */
public record SearchResponse(List<SearchHit> hits, String answer, long tookMs) {}
