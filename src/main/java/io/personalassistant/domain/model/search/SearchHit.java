package io.personalassistant.domain.model.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One result: an entity shown through its best-matching chunk.
 *
 * @param text the chunk as indexed; with moreMatches, what an answer is grounded in
 * @param snippet a display excerpt only
 * @param moreMatches the entity's other matching chunks, best first
 * @param ranking never null
 */
public record SearchHit(
        String chunkId,
        String entityId,
        String knowledgeId,
        int ordinal,
        String title,
        String text,
        String snippet,
        String uri,
        double score,
        Map<String, Object> metadata,
        List<Match> moreMatches,
        Ranking ranking) {

    /** So a model does not read non-adjacent passages as contiguous. */
    static final String PASSAGE_SEPARATOR = "\n\n[…]\n\n";

    public SearchHit {
        moreMatches = moreMatches == null ? List.of() : List.copyOf(moreMatches);
        ranking = ranking == null ? Ranking.of(score) : ranking;
    }

    public SearchHit(String chunkId, String entityId, String knowledgeId, int ordinal, String title,
                     String text, String snippet, String uri, double score, Map<String, Object> metadata) {
        this(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri, score, metadata, List.of(), null);
    }

    /** The ranking breakdown is kept as it was. */
    public SearchHit withScore(double newScore) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                newScore, metadata, moreMatches, ranking);
    }

    public SearchHit withMoreMatches(double newScore, List<Match> matches) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                newScore, metadata, matches, ranking);
    }

    public SearchHit withRanking(Ranking newRanking) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                score, metadata, moreMatches, newRanking);
    }

    /** Document order, not score order: the passages are pieces of one item. */
    public String groundingText() {
        if (moreMatches.isEmpty()) {
            return text;
        }
        List<Match> passages = new ArrayList<>(moreMatches.size() + 1);
        passages.add(new Match(chunkId, ordinal, text, snippet, score));
        passages.addAll(moreMatches);
        passages.sort(Comparator.comparingInt(Match::ordinal));
        List<String> texts = passages.stream()
                .map(Match::text)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        return String.join(PASSAGE_SEPARATOR, texts);
    }

    /** @param text the full chunk, for grounding; never sent to the console */
    public record Match(String chunkId, int ordinal, String text, String snippet, double score) {}

    /**
     * Diagnostic only: nothing ranks on it.
     *
     * @param lexicalRank null when that leg did not return it or did not run
     * @param retrievalScore the fused RRF score, or the single leg's in LEXICAL or SEMANTIC mode
     * @param recencyFactor 1 when the result carries no date
     */
    public record Ranking(Integer lexicalRank, Integer vectorRank, double retrievalScore,
                          double groupedScore, double recencyFactor) {

        public static Ranking of(double score) {
            return new Ranking(null, null, score, score, 1.0);
        }

        public Ranking withLegRanks(Integer lexical, Integer vector, double fusedScore) {
            return new Ranking(lexical, vector, fusedScore, fusedScore, recencyFactor);
        }

        public Ranking withGroupedScore(double grouped) {
            return new Ranking(lexicalRank, vectorRank, retrievalScore, grouped, recencyFactor);
        }

        public Ranking withRecencyFactor(double factor) {
            return new Ranking(lexicalRank, vectorRank, retrievalScore, groupedScore, factor);
        }
    }
}
