package io.personalassistant.domain.model.search;

import java.util.List;

/**
 * @param answerError why answering failed; the hits are still valid
 * @param vectorError why the query could not be embedded; the search ran lexically and the hits are real
 */
public record SearchResponse(List<SearchHit> hits, String answer, String answerError, String vectorError,
                             long tookMs) {}
