package io.personalassistant.domain.model.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One result: an entity, represented by its best-matching chunk, with its relevance score and citation info.
 *
 * <p>{@code text} and {@code snippet} are deliberately separate. {@code text} is the chunk exactly as
 * indexed and is what grounding must be built from; {@code snippet} is a display excerpt, short enough
 * for a result card. Collapsing the two loses data irreversibly at the adapter boundary: the agent was
 * previously handed snippets and so answered from ~28% of each chunk, truncating lists mid-item and then
 * reporting the missing rows as absent from the source.
 *
 * <p>Retrieval legs produce hits with no {@code moreMatches}; {@code EntityGrouper} folds an entity's
 * other matching chunks into its best one. A result is an entity rather than a chunk because the user is
 * looking for documents, postings and mails — ranking chunks let one matching item fill every slot with
 * its own siblings.
 *
 * @param chunkId     the best-matching chunk
 * @param entityId    owning entity (for grouping / dedup)
 * @param knowledgeId owning knowledge
 * @param ordinal     position of this chunk within its entity — orders multiple hits from one document,
 *                    and is what a future expansion tool needs to ask for neighbouring chunks
 * @param title       entity title for display
 * @param text        the full chunk text as indexed; with {@code moreMatches}, the grounding set for answering
 * @param snippet     short display excerpt (highlight fragment when available, else the head of the text)
 * @param uri         locator so the user can open the original
 * @param score       relevance score (post-grouping, post-rerank if reranking is on)
 * @param metadata    facets carried through for display/filtering
 * @param moreMatches the entity's other matching chunks, best first; empty for a single-chunk match
 * @param ranking     how the result reached {@code score}, stage by stage; never null
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

    /** Separates non-adjacent passages in {@link #groundingText()}, so a model does not read them as contiguous. */
    static final String PASSAGE_SEPARATOR = "\n\n[…]\n\n";

    public SearchHit {
        moreMatches = moreMatches == null ? List.of() : List.copyOf(moreMatches);
        ranking = ranking == null ? Ranking.of(score) : ranking;
    }

    /** A single-chunk hit — the shape every retrieval leg produces, before grouping. */
    public SearchHit(String chunkId, String entityId, String knowledgeId, int ordinal, String title,
                     String text, String snippet, String uri, double score, Map<String, Object> metadata) {
        this(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri, score, metadata, List.of(), null);
    }

    /** Returns a copy of this hit with a new score; the ranking breakdown is kept as it was. */
    public SearchHit withScore(double newScore) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                newScore, metadata, moreMatches, ranking);
    }

    /** Returns a copy carrying an entity's further matching chunks, rescored for them. */
    public SearchHit withMoreMatches(double newScore, List<Match> matches) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                newScore, metadata, matches, ranking);
    }

    /** Returns a copy with a new ranking breakdown. */
    public SearchHit withRanking(Ranking newRanking) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                score, metadata, moreMatches, newRanking);
    }

    /**
     * The text an answer should be grounded in: this chunk and every further match, in document order.
     *
     * <p>Document order, not score order, because the passages are pieces of one item and read wrongly
     * shuffled — a "requirements" list would appear above the heading that introduces it.
     */
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

    /**
     * Another chunk of the same entity that matched the query.
     *
     * @param chunkId the matching chunk
     * @param ordinal its position within the entity
     * @param text    the full chunk text, for grounding; never sent to the console
     * @param snippet display excerpt
     * @param score   the chunk's own retrieval score
     */
    public record Match(String chunkId, int ordinal, String text, String snippet, double score) {}

    /**
     * How a result reached its score. Diagnostic only — nothing ranks on it — and the reason it exists is
     * tuning: when the wrong item comes first, this says whether word matching, meaning, the further-matches
     * bonus or freshness put it there, so the one setting that matters is the one that gets changed.
     *
     * @param lexicalRank    1-based rank of the best chunk in the word-match leg; null when that leg did not
     *                       return it or did not run. Always null for a document search, whose legs run once
     *                       per facet and so have no single rank
     * @param vectorRank     the same for the meaning leg
     * @param facetsMatched  for a document search, how many derived facet queries returned the best chunk;
     *                       0 for a typed query
     * @param retrievalScore the best chunk's score out of retrieval — the fused RRF score, or the single leg's
     *                       own score in LEXICAL / SEMANTIC mode
     * @param groupedScore   after the further-matches bonus; equal to {@code retrievalScore} when there is none
     * @param recencyFactor  the freshness multiplier applied on top; 1 when the result carries no date
     */
    public record Ranking(Integer lexicalRank, Integer vectorRank, int facetsMatched, double retrievalScore,
                          double groupedScore, double recencyFactor) {

        /** A breakdown that says only "retrieval scored it this". */
        public static Ranking of(double score) {
            return new Ranking(null, null, 0, score, score, 1.0);
        }

        public Ranking withLegRanks(Integer lexical, Integer vector, double fusedScore) {
            return new Ranking(lexical, vector, facetsMatched, fusedScore, fusedScore, recencyFactor);
        }

        public Ranking withFacetsMatched(int facets, double fusedScore) {
            return new Ranking(null, null, facets, fusedScore, fusedScore, recencyFactor);
        }

        public Ranking withGroupedScore(double grouped) {
            return new Ranking(lexicalRank, vectorRank, facetsMatched, retrievalScore, grouped, recencyFactor);
        }

        public Ranking withRecencyFactor(double factor) {
            return new Ranking(lexicalRank, vectorRank, facetsMatched, retrievalScore, groupedScore, factor);
        }
    }
}
