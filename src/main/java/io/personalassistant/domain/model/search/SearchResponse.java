package io.personalassistant.domain.model.search;

import java.util.List;

/**
 * Result of a search. {@code answer} is populated only when the query asked for
 * synthesis; {@code hits} are always the grounding/citation set.
 *
 * @param hits        ranked results
 * @param answer      optional LLM-synthesized answer, grounded in {@code hits}
 * @param answerError why answering failed, when it was asked for and did not produce an answer. Kept
 *                    beside the hits rather than thrown: retrieval already succeeded at that point, and
 *                    letting the failure propagate discarded a perfectly good result set over an
 *                    unavailable LLM
 * @param tookMs      server-side latency
 */
public record SearchResponse(List<SearchHit> hits, String answer, String answerError, long tookMs) {}
